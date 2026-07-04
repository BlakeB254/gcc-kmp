package com.gcc.app.platform

import com.gcc.shared.api.CaptureStorageNotConfiguredException
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.CaptureUploadUrlRequest
import com.gcc.shared.model.EnvScanManifest
import com.gcc.shared.serialization.GccJson
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Uploads scan-session manifests to R2 via the presigned-URL endpoint, with a
 * persistent retry queue: until R2 env vars are provisioned the endpoint
 * returns 501, and before this queue existed the whole session bundle (GPS +
 * facing track, scanned cells, observed items) was silently discarded. Now a
 * failed manifest persists locally (same `multiplatform-settings` pattern as
 * [CoverageEventQueue]) and re-uploads on the next scan session — nothing is
 * lost while storage is being stood up, and offline sessions catch up too.
 *
 * Photos are NOT queued: their bytes only live in memory during the capture
 * session, and they already reach the server durably via the community-report
 * media upload. The manifest records whichever photoKeys did make it to R2.
 */
class EnvScanSessionUploader(
    private val apiClient: GccApiClient,
    private val settings: Settings = Settings(),
) {
    private val mutex = Mutex()
    private val pending = mutableListOf<EnvScanManifest>().apply { addAll(loadPersisted()) }

    private fun loadPersisted(): List<EnvScanManifest> {
        val json = settings.getStringOrNull(KEY_PENDING_MANIFESTS) ?: return emptyList()
        return try {
            GccJson.decodeFromString(json)
        } catch (e: Exception) {
            emptyList() // corrupt persisted state must never crash startup
        }
    }

    private fun persistLocked() {
        settings.putString(KEY_PENDING_MANIFESTS, GccJson.encodeToString(pending))
    }

    /**
     * Upload one manifest now; on any failure it joins the persistent queue.
     * Returns true when it landed in R2 immediately.
     */
    suspend fun uploadOrQueue(manifest: EnvScanManifest): Boolean {
        return try {
            upload(manifest)
            true
        } catch (e: CaptureStorageNotConfiguredException) {
            DiagnosticsLog.info("env-capture", "R2 not provisioned — manifest ${manifest.sessionId} queued for retry")
            enqueue(manifest)
            false
        } catch (e: Exception) {
            DiagnosticsLog.error("env-capture", "manifest ${manifest.sessionId} upload failed — queued for retry", e)
            enqueue(manifest)
            false
        }
    }

    /**
     * Retry every queued manifest, oldest first. Stops early when storage is
     * still unprovisioned (every retry would 501). Call at scan-session start.
     */
    suspend fun flushPending() {
        val snapshot = mutex.withLock { pending.toList() }
        for (manifest in snapshot) {
            try {
                upload(manifest)
                mutex.withLock {
                    pending.remove(manifest)
                    persistLocked()
                }
                DiagnosticsLog.info("env-capture", "queued manifest ${manifest.sessionId} caught up to R2")
            } catch (e: CaptureStorageNotConfiguredException) {
                return // still 501 — nothing else in the queue can succeed either
            } catch (e: Exception) {
                DiagnosticsLog.error("env-capture", "queued manifest ${manifest.sessionId} retry failed (kept)", e)
            }
        }
    }

    private suspend fun enqueue(manifest: EnvScanManifest) {
        mutex.withLock {
            pending.add(manifest)
            // Bound the queue: manifests carry up to an hour of 1Hz track
            // samples, so cap how much settings storage they can occupy.
            while (pending.size > MAX_QUEUED) pending.removeAt(0)
            persistLocked()
        }
    }

    private suspend fun upload(manifest: EnvScanManifest) {
        val anchor = manifest.path.firstOrNull() ?: manifest.scannedCells.firstOrNull()?.let {
            com.gcc.shared.model.EnvScanPathSample(it.lat, it.lng, atMs = manifest.startedAtMs)
        }
        val cell = SessionPointsEstimator.gridCell10m(anchor?.lat ?: 0.0, anchor?.lng ?: 0.0)
        val ticket = apiClient.createCaptureUploadUrl(
            CaptureUploadUrlRequest(
                locationCellId = "${cell.first}:${cell.second}",
                deviceId = manifest.deviceId,
                capturedAt = "${manifest.sessionId}-manifest",
                ext = "json",
            )
        )
        apiClient.uploadToPresignedUrl(
            ticket.uploadUrl,
            GccJson.encodeToString(manifest).encodeToByteArray(),
            "application/json",
        )
    }

    companion object {
        private const val KEY_PENDING_MANIFESTS = "pending_env_manifests"
        private const val MAX_QUEUED = 12
    }
}
