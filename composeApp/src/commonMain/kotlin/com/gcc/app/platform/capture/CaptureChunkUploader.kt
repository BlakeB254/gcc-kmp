package com.gcc.app.platform.capture

import com.gcc.app.platform.CoverageDeviceId
import com.gcc.app.platform.DiagnosticsLog
import com.gcc.app.platform.ScanSessionTelemetry
import com.gcc.app.platform.deleteLocalFile
import com.gcc.app.platform.readLocalFileBytes
import com.gcc.app.platform.scheduleBackgroundScanUpload
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
 * Uploads background full-session capture artifacts (completion JPEGs, HEVC
 * video, pose/intrinsics sidecars, and LiDAR depth keyframes — bookkept in
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
 * listener is necessary here: policy/failure paths schedule the native iOS
 * BGProcessing task, while foreground activation and the next scan remain
 * additional drain opportunities.
 */
class CaptureChunkUploader(
    private val apiClient: GccApiClient,
    private val store: CaptureChunkStore,
    private val settings: Settings = Settings(),
) {
    private val mutex = Mutex()

    /** Human-readable reason the most recent upload attempt failed — the
     *  Settings screen surfaces this so field failures stop being invisible.
     *  Null after any fully successful flush. */
    var lastError: String? = null
        private set

    suspend fun flushPending() {
        mutex.withLock {
            if (!uploadPolicyAllows()) {
                scheduleBackgroundScanUpload()
                return
            }
            val pending = try {
                store.pendingChunks()
            } catch (e: Exception) {
                DiagnosticsLog.error("capture-upload", "pendingChunks query failed", e)
                return
            }
            var failure: String? = null
            for (chunk in pending) {
                try {
                    uploadOne(chunk)
                } catch (e: CaptureStorageNotConfiguredException) {
                    lastError = "scan storage not configured (501)"
                    scheduleBackgroundScanUpload()
                    return // still 501 (R2 not provisioned) — nothing else in the batch can succeed either
                } catch (e: Exception) {
                    DiagnosticsLog.error(
                        "capture-upload",
                        "chunk ${chunk.sessionId}/${chunk.chunkIndex}/${chunk.kind} upload failed (kept pending)",
                        e,
                    )
                    // Flight recorder: a mid-session chunk failure is a
                    // headline metric (upload failures at index time).
                    ScanSessionTelemetry.forSession(chunk.sessionId)?.upload(
                        kind = "chunk-${chunk.kind}",
                        ok = false,
                        detail = e.message?.take(120),
                    )
                    failure = "chunk ${chunk.chunkIndex} ${chunk.kind}: ${e.message ?: e::class.simpleName}"
                    scheduleBackgroundScanUpload()
                }
            }
            lastError = failure
        }
    }

    private fun uploadPolicyAllows(): Boolean {
        if (isCharging()) return true
        // Blake 2026-07-12: scans upload automatically as soon as the scan
        // ends — cellular is allowed BY DEFAULT and the setting is an
        // opt-OUT for anyone who wants to hold big uploads for Wi-Fi.
        if (settings.getBoolean(KEY_CELLULAR_UPLOAD_OPT_IN, true)) return true
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
        val artifactPath = artifactPathFor(chunk)
        val ticket = try {
            apiClient.createCaptureUploadUrl(
                CaptureUploadUrlRequest(
                    locationCellId = chunk.locationCellId,
                    deviceId = CoverageDeviceId.value,
                    capturedAt = "${chunk.sessionId}-chunk${chunk.chunkIndex}-${chunk.kind}",
                    ext = ext,
                    sessionId = chunk.sessionId,
                    artifactPath = artifactPath,
                )
            )
        } catch (e: Exception) {
            if (e !is CaptureStorageNotConfiguredException && isClientErrorMessage(e.message)) {
                poisonChunk(chunk)
                DiagnosticsLog.error("capture-upload", "chunk presign 4xx — skipped permanently", e)
                return
            }
            throw e
        }
        try {
            apiClient.uploadToPresignedUrl(ticket.uploadUrl, bytes, contentTypeFor(ext))
        } catch (e: Exception) {
            if (isClientErrorMessage(e.message)) {
                poisonChunk(chunk)
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
        ScanSessionTelemetry.forSession(chunk.sessionId)?.upload(
            kind = "chunk-${chunk.kind}",
            ok = true,
            key = ticket.r2Key,
        )
        deleteLocalFile(chunk.filePath)
    }

    /** Permanently skip a chunk (4xx poison): sentinel-mark the row AND
     *  delete the file — a skipped chunk will never upload, so keeping its
     *  bytes on disk would leak storage forever. */
    private suspend fun poisonChunk(chunk: CaptureChunkRecord) {
        store.markUploaded(chunk.sessionId, chunk.chunkIndex, chunk.kind, r2Key = null, uploadedAtMs = -1)
        deleteLocalFile(chunk.filePath)
    }

    companion object {
        const val KEY_CELLULAR_UPLOAD_OPT_IN = "capture_upload_cellular"

        internal fun extensionFor(kind: String): String = when (kind) {
            "video" -> "mov"
            "sidecar" -> "jsonl"
            "depth" -> "bin"
            "photo" -> "jpg"
            else -> "bin"
        }

        internal fun contentTypeFor(ext: String): String = when (ext) {
            "mov" -> "video/quicktime"
            "jsonl" -> "application/x-ndjson"
            "jpg" -> "image/jpeg"
            else -> "application/octet-stream"
        }

        internal fun artifactPathFor(chunk: CaptureChunkRecord): String =
            if (chunk.kind == "photo") {
                "photos/photo-${chunk.chunkIndex}.jpg"
            } else {
                "capture/chunk-${chunk.chunkIndex}.${extensionFor(chunk.kind)}"
            }

        /**
         * Exact key the server's `rawSessionArtifactKey` returns for a queued
         * artifact. A manifest can therefore retain its photo relationship
         * while the durable file is still waiting for connectivity.
         */
        internal fun expectedR2Key(chunk: CaptureChunkRecord): String {
            val safeSessionId = chunk.sessionId.replace(Regex("[^0-9A-Za-z._-]"), "-")
            return "raw/${chunk.locationCellId}/$safeSessionId/${artifactPathFor(chunk)}"
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
