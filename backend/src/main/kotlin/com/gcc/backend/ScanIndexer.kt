package com.gcc.backend

import com.gcc.shared.model.EnvScanManifest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Walks R2 (`gcc-scan-storage`) and mirrors every capture session into Neon so
 * the previously-orphaned scan blobs become queryable and linkable:
 *
 *   raw/{cellId}/…/{id}-manifest.json  ->  one `scan_sessions` row (from the
 *                                       @Serializable EnvScanManifest itself)
 *   every artifact the manifest names ->  a `capture_chunks` row (idempotent
 *                                          on r2_key), sized from the listing
 *   reconstructed/{cellId}/{v}/…     ->  a `reconstruction_assets` row
 *
 * The manifest is self-describing (it lists its own photoKeys, captureChunks,
 * capturedFrames, objectCapture), so indexing follows the manifest rather than
 * guessing from prefixes. Fully idempotent — safe to run on a schedule.
 */
class ScanIndexer(
    private val r2: R2Storage,
    private val repo: ScanIndexRepository,
) {
    private val log = LoggerFactory.getLogger("scan-indexer")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    data class Result(
        val sessionsIndexed: Int,
        val chunksIndexed: Int,
        val reconstructionsIndexed: Int,
        val manifestsFailed: Int,
        val durationMs: Long,
        val reconstructionJobsEnqueued: Int = 0,
    )

    fun runOnce(): Result {
        val start = System.currentTimeMillis()
        val raw = r2.listAll("raw/")
        val reconstructed = r2.listAll("reconstructed/")

        var sessions = 0
        var chunks = 0
        var failed = 0

        val manifestKeys = raw.keys.filter { it.endsWith("manifest.json") }
        for (manifestKey in manifestKeys) {
            try {
                val manifest = json.decodeFromString(EnvScanManifest.serializer(), r2.getText(manifestKey))
                val sessionCell = cellIdFromKey(manifestKey) ?: "0:0"

                // Every R2 object this session owns -> a capture_chunk row.
                val chunkRows = buildList {
                    add(chunkRow(manifest.sessionId, manifestKey, "manifest", sessionCell, raw[manifestKey], manifest.completedAtMs))
                    manifest.photoKeys.forEach {
                        add(chunkRow(manifest.sessionId, it, "photo", cellOr(it, sessionCell), raw[it], null))
                    }
                    manifest.captureChunks?.forEach {
                        add(chunkRow(manifest.sessionId, it, artifactTypeForKey(it), cellOr(it, sessionCell), raw[it], null))
                    }
                    manifest.objectCapture?.r2Key?.let {
                        add(chunkRow(manifest.sessionId, it, "usdz", cellOr(it, sessionCell), raw[it], manifest.completedAtMs))
                    }
                    manifest.eventLogKey?.let {
                        add(chunkRow(manifest.sessionId, it, "event_log", cellOr(it, sessionCell), raw[it], manifest.completedAtMs))
                    }
                }

                // Flight recorder (PRD Phase 1 §3): fetch the session's small
                // gzipped event stream and extract headline metrics. Gated on
                // the listing (a manifest can reference a log whose upload is
                // still retrying client-side — skip the guaranteed 404); any
                // fetch/parse failure leaves the metrics NULL and the upsert's
                // COALESCE keeps previously-extracted values, so the next
                // sweep self-heals once the artifact exists.
                val eventMetrics = manifest.eventLogKey?.takeIf { raw.containsKey(it) }?.let { key ->
                    try {
                        EventLogMetricsExtractor.extract(
                            EventLogMetricsExtractor.gunzipLines(r2.getBytes(key)).asSequence()
                        )
                    } catch (e: Exception) {
                        log.warn("failed to extract event log {}: {}", key, e.message)
                        null
                    }
                }

                val totalBytes = chunkRows.mapNotNull { it.byteSize }.sum().takeIf { it > 0 }
                repo.upsertSession(
                    SessionRow(
                        sessionId = manifest.sessionId,
                        deviceId = manifest.deviceId,
                        deviceModel = manifest.deviceModel,
                        platform = platformFor(manifest.deviceModel),
                        osVersion = manifest.osVersion,
                        lapKind = manifest.lapKind,
                        routeId = manifest.routeId,
                        locationCellId = sessionCell,
                        startedAtMs = manifest.startedAtMs,
                        completedAtMs = manifest.completedAtMs,
                        photoCount = manifest.photoKeys.size,
                        chunkCount = manifest.captureChunks?.size ?: 0,
                        observedItemCount = manifest.observedItems.size,
                        scannedCellCount = manifest.scannedCells.size,
                        hasObjectCapture = manifest.objectCapture != null,
                        objectCaptureR2Key = manifest.objectCapture?.r2Key,
                        manifestR2Key = manifestKey,
                        totalBytes = totalBytes,
                        geoRefEventCount = manifest.geoRefEvents?.size ?: 0,
                        eventLogR2Key = manifest.eventLogKey,
                        eventMetrics = eventMetrics,
                    )
                )
                repo.upsertChunks(chunkRows)
                sessions++
                chunks += chunkRows.size
            } catch (e: Exception) {
                failed++
                log.warn("failed to index manifest {}: {}", manifestKey, e.message)
            }
        }

        var reconstructions = 0
        for ((key, _) in reconstructed) {
            if (key.endsWith("/")) continue
            val parsed = parseReconstructedKey(key) ?: continue
            if (repo.insertReconstructionAssetIfAbsent(parsed.cell, parsed.assetType, parsed.format, key, parsed.version)) {
                reconstructions++
            }
        }

        // Phase 3: every freshly-indexed, still-unreconstructed session with a
        // full capture set becomes a durable reconstruction job for the DGX
        // GPU worker. Idempotent on session_id, so re-sweeps are free.
        val jobsEnqueued = repo.enqueueReconstructionJobs()
        if (jobsEnqueued > 0) log.info("enqueued {} reconstruction job(s)", jobsEnqueued)

        return Result(sessions, chunks, reconstructions, failed, System.currentTimeMillis() - start, jobsEnqueued)
    }

    private fun chunkRow(sessionId: String, key: String, type: String, cell: String, size: Long?, capturedAtMs: Long?) =
        ChunkRow(sessionId, cell, type, key, extensionOf(key), size, capturedAtMs)

    private fun cellOr(key: String, fallback: String) = cellIdFromKey(key) ?: fallback

    /** `raw/{cellId}/…` — cellId is the segment after the top-level prefix. */
    private fun cellIdFromKey(key: String): String? {
        val parts = key.split('/')
        return parts.getOrNull(1)?.takeIf { it.matches(CELL_ID_PATTERN) }
    }

    private fun extensionOf(key: String): String? =
        key.substringAfterLast('/').substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }

    private fun artifactTypeForKey(key: String): String = when (extensionOf(key)) {
        "mp4", "mov", "hevc" -> "video"
        "jsonl" -> "pose"
        "png" -> "depth"
        "ply" -> "pointcloud"
        "usdz" -> "usdz"
        "jpg", "jpeg", "heic" -> "photo"
        else -> "other"
    }

    private fun platformFor(deviceModel: String?): String? {
        val m = deviceModel?.lowercase() ?: return null
        return if (m.contains("iphone") || m.contains("ipad") || m.contains("ipod")) "ios" else null
    }

    private data class ReconKey(val cell: String, val version: Int, val assetType: String, val format: String)

    /** `reconstructed/{cellId}/{version}/{assetType}.{format}` */
    private fun parseReconstructedKey(key: String): ReconKey? {
        val parts = key.split('/')
        if (parts.size < 4) return null
        val cell = parts[1].takeIf { it.matches(CELL_ID_PATTERN) } ?: return null
        val version = parts[2].toIntOrNull() ?: return null
        val file = parts.last()
        val assetType = file.substringBeforeLast('.').takeIf { it.isNotEmpty() } ?: return null
        val format = file.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() } ?: return null
        return ReconKey(cell, version, assetType, format)
    }

    companion object {
        private val CELL_ID_PATTERN = Regex("""^-?\d+:-?\d+$""")
    }
}
