package com.gcc.app.platform

import com.gcc.shared.api.CaptureStorageNotConfiguredException
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.CaptureUploadUrlRequest
import com.gcc.shared.model.EnvScanManifest
import com.gcc.shared.serialization.GccJson
import com.russhwolf.settings.Settings
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
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
@OptIn(ExperimentalEncodingApi::class)
class EnvScanSessionUploader(
    private val apiClient: GccApiClient,
    private val settings: Settings = Settings(),
) {
    private val mutex = Mutex()
    private val pending = mutableListOf<EnvScanManifest>().apply { addAll(loadPersisted()) }
    private val pendingEventLogs = mutableMapOf<String, PendingEventLog>().apply { putAll(loadPersistedEventLogs()) }

    /** Most recent manifest-upload failure, for the Settings screen. */
    var lastError: String? = null
        private set

    /** A gzipped flight-recorder stream whose upload failed, keyed by
     *  sessionId and persisted beside the manifest queue — the pair retries
     *  together in [flushPending] so an offline session's event log (exactly
     *  the interesting one) is never dropped. gz + base64: ~100KB worst case,
     *  same settings-storage order of magnitude as a queued manifest. */
    @Serializable
    private data class PendingEventLog(val locationCellId: String, val gzBase64: String)

    private fun loadPersisted(): List<EnvScanManifest> {
        val json = settings.getStringOrNull(KEY_PENDING_MANIFESTS) ?: return emptyList()
        return try {
            GccJson.decodeFromString(json)
        } catch (e: Exception) {
            emptyList() // corrupt persisted state must never crash startup
        }
    }

    private fun loadPersistedEventLogs(): Map<String, PendingEventLog> {
        val json = settings.getStringOrNull(KEY_PENDING_EVENT_LOGS) ?: return emptyMap()
        return try {
            GccJson.decodeFromString(json)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun persistLocked() {
        settings.putString(KEY_PENDING_MANIFESTS, GccJson.encodeToString(pending))
        // Event logs only make sense next to their queued manifest — prune
        // any whose manifest has been evicted or delivered.
        val alive = pending.mapTo(mutableSetOf()) { it.sessionId }
        pendingEventLogs.keys.retainAll(alive)
        settings.putString(KEY_PENDING_EVENT_LOGS, GccJson.encodeToString(pendingEventLogs))
    }

    /**
     * Upload one session now: its flight-recorder event log FIRST (so the
     * manifest can reference the R2 key via `eventLogKey`), then the
     * manifest. On any failure both join the persistent queue and retry
     * together. Returns true when the manifest landed in R2 immediately.
     *
     * [eventLogLines] is the SessionEventLog snapshot (JSONL, ungzipped);
     * null/empty for callers that don't record events.
     */
    suspend fun uploadOrQueue(manifest: EnvScanManifest, eventLogLines: List<String>? = null): Boolean {
        var toUpload = manifest
        if (!eventLogLines.isNullOrEmpty() && manifest.eventLogKey == null) {
            val gz = gzipBytes((eventLogLines.joinToString("\n") + "\n").encodeToByteArray())
            val cellId = locationCellIdFor(manifest)
            try {
                val key = uploadEventLog(cellId, manifest.deviceId, manifest.sessionId, gz)
                toUpload = manifest.copy(eventLogKey = key, eventLogSchema = EVENT_LOG_SCHEMA)
                DiagnosticsLog.info("env-capture", "event log ${manifest.sessionId} uploaded (${gz.size}B gz)")
            } catch (e: Exception) {
                if (e !is CaptureStorageNotConfiguredException) {
                    DiagnosticsLog.error("env-capture", "event log ${manifest.sessionId} upload failed — queued with manifest", e)
                }
                mutex.withLock {
                    pendingEventLogs[manifest.sessionId] = PendingEventLog(cellId, Base64.encode(gz))
                }
                enqueue(manifest)
                lastError = "event log: ${e.message ?: e::class.simpleName}"
                return false
            }
        }
        return try {
            upload(toUpload)
            true
        } catch (e: CaptureStorageNotConfiguredException) {
            DiagnosticsLog.info("env-capture", "R2 not provisioned — manifest ${manifest.sessionId} queued for retry")
            enqueue(toUpload)
            false
        } catch (e: Exception) {
            DiagnosticsLog.error("env-capture", "manifest ${manifest.sessionId} upload failed — queued for retry", e)
            lastError = "manifest: ${e.message ?: e::class.simpleName}"
            enqueue(toUpload)
            false
        }
    }

    /**
     * Retry every queued manifest, oldest first — each one's pending event
     * log first, so the delivered manifest still references it. Stops early
     * when storage is still unprovisioned (every retry would 501). Call at
     * scan-session start.
     */
    suspend fun flushPending() {
        val snapshot = mutex.withLock { pending.toList() }
        for (manifest in snapshot) {
            var toUpload = manifest
            val pendingLog = mutex.withLock { pendingEventLogs[manifest.sessionId] }
            if (manifest.eventLogKey == null && pendingLog != null) {
                try {
                    val key = uploadEventLog(
                        pendingLog.locationCellId,
                        manifest.deviceId,
                        manifest.sessionId,
                        Base64.decode(pendingLog.gzBase64),
                    )
                    toUpload = manifest.copy(eventLogKey = key, eventLogSchema = EVENT_LOG_SCHEMA)
                    // Replace the queued entry so a manifest failure below
                    // retries WITH the key instead of re-uploading the log.
                    mutex.withLock {
                        val idx = pending.indexOf(manifest)
                        if (idx >= 0) pending[idx] = toUpload
                        pendingEventLogs.remove(manifest.sessionId)
                        persistLocked()
                    }
                    DiagnosticsLog.info("env-capture", "queued event log ${manifest.sessionId} caught up to R2")
                } catch (e: CaptureStorageNotConfiguredException) {
                    lastError = "scan storage not configured (501)"
                    return
                } catch (e: Exception) {
                    DiagnosticsLog.error("env-capture", "queued event log ${manifest.sessionId} retry failed (kept)", e)
                    lastError = "event log: ${e.message ?: e::class.simpleName}"
                    continue // keep the pair queued; the manifest must not ship without its log
                }
            }
            try {
                upload(toUpload)
                mutex.withLock {
                    pending.remove(manifest)
                    pending.remove(toUpload)
                    persistLocked()
                }
                lastError = null
                DiagnosticsLog.info("env-capture", "queued manifest ${manifest.sessionId} caught up to R2")
            } catch (e: CaptureStorageNotConfiguredException) {
                lastError = "scan storage not configured (501)"
                return // still 501 — nothing else in the queue can succeed either
            } catch (e: Exception) {
                DiagnosticsLog.error("env-capture", "queued manifest ${manifest.sessionId} retry failed (kept)", e)
                lastError = "manifest: ${e.message ?: e::class.simpleName}"
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

    private fun locationCellIdFor(manifest: EnvScanManifest): String {
        val anchor = manifest.path.firstOrNull() ?: manifest.scannedCells.firstOrNull()?.let {
            com.gcc.shared.model.EnvScanPathSample(it.lat, it.lng, atMs = manifest.startedAtMs)
        }
        val cell = SessionPointsEstimator.gridCell10m(anchor?.lat ?: 0.0, anchor?.lng ?: 0.0)
        return "${cell.first}:${cell.second}"
    }

    /** PUTs the gzipped event stream at `raw/{cell}/{sessionId}/events.jsonl.gz`
     *  (the session-artifact key layout, beside the capture chunks) and
     *  returns the R2 key for the manifest to reference. */
    private suspend fun uploadEventLog(locationCellId: String, deviceId: String, sessionId: String, gz: ByteArray): String {
        val ticket = apiClient.createCaptureUploadUrl(
            CaptureUploadUrlRequest(
                locationCellId = locationCellId,
                deviceId = deviceId,
                capturedAt = "$sessionId-events",
                ext = "gz",
                sessionId = sessionId,
                artifactPath = EVENT_LOG_ARTIFACT,
            )
        )
        apiClient.uploadToPresignedUrl(ticket.uploadUrl, gz, "application/gzip")
        return ticket.r2Key
    }

    private suspend fun upload(manifest: EnvScanManifest) {
        val ticket = apiClient.createCaptureUploadUrl(
            CaptureUploadUrlRequest(
                locationCellId = locationCellIdFor(manifest),
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
        private const val KEY_PENDING_EVENT_LOGS = "pending_env_event_logs"
        private const val MAX_QUEUED = 12
        const val EVENT_LOG_ARTIFACT = "events.jsonl.gz"
        const val EVENT_LOG_SCHEMA = 1
    }
}
