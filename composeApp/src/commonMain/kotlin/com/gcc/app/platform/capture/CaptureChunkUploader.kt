package com.gcc.app.platform.capture

import com.gcc.app.platform.CoverageDeviceId
import com.gcc.app.platform.DiagnosticsLog
import com.gcc.app.platform.deleteLocalFile
import com.gcc.app.platform.readLocalFileBytes
import com.gcc.shared.api.CaptureStorageNotConfiguredException
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.cache.CaptureChunkRecord
import com.gcc.shared.cache.CaptureChunkStore
import com.gcc.shared.model.CaptureUploadUrlRequest
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

/**
 * Uploads background full-session capture chunks (HEVC video + pose/
 * intrinsics sidecar + LiDAR depth keyframes — bookkept in
 * [CaptureChunkStore], written by [com.gcc.app.platform.ArEnvScanOverlay])
 * to R2 once policy allows it. Policy (any one satisfies): Wi-Fi, OR the
 * device is charging, OR the user opted into cellular uploads (multiplatform-
 * settings key [KEY_CELLULAR_UPLOAD_OPT_IN], default false — no UI in this
 * task; see the Pillar 1 Task 5 report). Chunks upload oldest-first, one at
 * a time; a 4xx presign/PUT response poisons the row (`uploadedAtMs = -1`,
 * "skipped") instead of retrying forever, matching [CaptureChunkStore]'s
 * `markUploaded` sentinel convention.
 *
 * Call sites: scan completion (SpotScreen), the app-launch flush hook
 * (SpotViewModel/RouteScanViewModel `init` — same place
 * [com.gcc.app.platform.EnvScanSessionUploader.flushPending] runs), and the
 * native recorder's finished-chunk drain (best-effort kick after each
 * insert). There is deliberately NO reactive "network flipped to Wi-Fi"
 * listener in this task — the checkpoints above already bound staleness to
 * "next scan or next app launch," matching [com.gcc.app.platform.EnvScanSessionUploader]'s
 * existing (also non-reactive) pattern.
 */
class CaptureChunkUploader(
    private val apiClient: GccApiClient,
    private val store: CaptureChunkStore,
    private val settings: Settings = Settings(),
) {
    private val mutex = Mutex()

    suspend fun flushPending() {
        mutex.withLock {
            if (!uploadPolicyAllows()) return
            val pending = try {
                store.pendingChunks()
            } catch (e: Exception) {
                DiagnosticsLog.error("capture-upload", "pendingChunks query failed", e)
                return
            }
            for (chunk in pending) {
                try {
                    uploadOne(chunk)
                } catch (e: CaptureStorageNotConfiguredException) {
                    return // still 501 (R2 not provisioned) — nothing else in the batch can succeed either
                } catch (e: Exception) {
                    DiagnosticsLog.error(
                        "capture-upload",
                        "chunk ${chunk.sessionId}/${chunk.chunkIndex}/${chunk.kind} upload failed (kept pending)",
                        e,
                    )
                }
            }
        }
    }

    private fun uploadPolicyAllows(): Boolean {
        if (isCharging()) return true
        if (settings.getBoolean(KEY_CELLULAR_UPLOAD_OPT_IN, false)) return true
        return currentNetworkStatus() == NetworkStatus.WIFI
    }

    private suspend fun uploadOne(chunk: CaptureChunkRecord) {
        val bytes = readLocalFileBytes(chunk.filePath)
        if (bytes == null) {
            // File already gone (manual clear, prior partial cleanup) —
            // nothing left to upload; drop the row so it stops recurring.
            store.markUploaded(chunk.sessionId, chunk.chunkIndex, chunk.kind, r2Key = null, uploadedAtMs = -1)
            return
        }
        val ext = extensionFor(chunk.kind)
        val ticket = try {
            apiClient.createCaptureUploadUrl(
                CaptureUploadUrlRequest(
                    locationCellId = chunk.locationCellId,
                    deviceId = CoverageDeviceId.value,
                    capturedAt = "${chunk.sessionId}-chunk${chunk.chunkIndex}-${chunk.kind}",
                    ext = ext,
                    sessionId = chunk.sessionId,
                    artifactPath = "capture/chunk-${chunk.chunkIndex}.$ext",
                )
            )
        } catch (e: Exception) {
            if (e !is CaptureStorageNotConfiguredException && isClientErrorMessage(e.message)) {
                store.markUploaded(chunk.sessionId, chunk.chunkIndex, chunk.kind, r2Key = null, uploadedAtMs = -1)
                DiagnosticsLog.error("capture-upload", "chunk presign 4xx — skipped permanently", e)
                return
            }
            throw e
        }
        try {
            apiClient.uploadToPresignedUrl(ticket.uploadUrl, bytes, contentTypeFor(ext))
        } catch (e: Exception) {
            if (isClientErrorMessage(e.message)) {
                store.markUploaded(chunk.sessionId, chunk.chunkIndex, chunk.kind, r2Key = null, uploadedAtMs = -1)
                DiagnosticsLog.error("capture-upload", "chunk PUT 4xx — skipped permanently", e)
                return
            }
            throw e
        }
        store.markUploaded(
            sessionId = chunk.sessionId,
            chunkIndex = chunk.chunkIndex,
            kind = chunk.kind,
            r2Key = ticket.r2Key,
            uploadedAtMs = Clock.System.now().toEpochMilliseconds(),
        )
        deleteLocalFile(chunk.filePath)
    }

    companion object {
        const val KEY_CELLULAR_UPLOAD_OPT_IN = "capture_upload_cellular"

        internal fun extensionFor(kind: String): String = when (kind) {
            "video" -> "mov"
            "sidecar" -> "jsonl"
            "depth" -> "bin"
            else -> "bin"
        }

        internal fun contentTypeFor(ext: String): String = when (ext) {
            "mov" -> "video/quicktime"
            "jsonl" -> "application/x-ndjson"
            else -> "application/octet-stream"
        }

        /**
         * [GccApiClient] has no typed "this was a 4xx" exception — every HTTP
         * failure surfaces as a plain [Exception] whose message embeds the
         * status code (`"HTTP 404: …"` from `httpErrorMessage`, or
         * `"presigned upload failed: 403 …"` from the raw R2 PUT). Parsing it
         * back out is the least invasive way to poison a chunk on 4xx without
         * changing [GccApiClient]'s shared error-surfacing convention (used
         * by many other callers) for this one call site.
         */
        internal fun isClientErrorMessage(message: String?): Boolean {
            val code = message
                ?.let { Regex("""(?:HTTP |failed: )(\d{3})""").find(it) }
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: return false
            return code in 400..499
        }
    }
}
