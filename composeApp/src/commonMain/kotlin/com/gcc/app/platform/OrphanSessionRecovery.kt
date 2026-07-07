package com.gcc.app.platform

import com.gcc.shared.cache.CaptureChunkRecord
import com.gcc.shared.cache.CaptureChunkStore
import com.gcc.shared.model.EnvScanManifest
import com.gcc.shared.model.EnvScanPathSample
import com.gcc.shared.serialization.GccJson
import kotlinx.datetime.Clock
import kotlinx.serialization.decodeFromString

/**
 * Parses a session journal's surviving lines into (samples, gateEvents).
 * Any line that fails to decode — a partially-written last line from an app
 * kill mid-`write`, or any other on-disk corruption — is skipped rather than
 * aborting the whole parse: recovery must salvage everything that DID land
 * cleanly, not throw the entire journal away for one bad line.
 */
fun parseSessionJournal(lines: List<String>): Pair<List<EnvScanPathSample>, List<String>> {
    val samples = mutableListOf<EnvScanPathSample>()
    val gateEvents = mutableListOf<String>()
    for (line in lines) {
        if (line.isBlank()) continue
        val entry = try {
            GccJson.decodeFromString<EnvScanJournalEntry>(line)
        } catch (e: Exception) {
            continue
        }
        when (entry.type) {
            "sample" -> entry.sample?.let(samples::add)
            "gate" -> entry.gate?.let(gateEvents::add)
        }
    }
    return samples.toList() to gateEvents.toList()
}

/** Reads and parses the journal for [sessionId], or null if it's missing/unreadable. */
private fun readJournal(sessionId: String): Pair<List<EnvScanPathSample>, List<String>>? {
    val path = sessionJournalPath(sessionId) ?: return null
    val bytes = readLocalFileBytes(path) ?: return null
    return parseSessionJournal(bytes.decodeToString().split("\n"))
}

/**
 * Launch-time orphan recovery (Pillar 6): rebuilds and uploads any
 * [EnvScanSessionRecorder] journal left on disk by an app kill mid-scan —
 * the manifest never got built, so [EnvScanSessionUploader.uploadOrQueue]
 * never ran and the journal never got deleted (see [SpotViewModel]'s
 * `uploadEnvScanSession` / [RouteScanViewModel]'s `uploadLapSession`, which
 * both delete their session's journal right after handing the manifest to
 * `uploadOrQueue`). Call this at the SAME point
 * [EnvScanSessionUploader.flushPending] runs (both ViewModels' `init`).
 *
 * **Active-session exclusion, by construction rather than a marker file:**
 * both call sites run inside a ViewModel constructor, which always finishes
 * before that ViewModel's screen composes and creates an
 * [EnvScanSessionRecorder] (SpotScreen's `remember { }`, or a lap-transition
 * reassignment in RouteScanViewModel) — so no journal a live scan is
 * currently writing can exist yet when this sweep lists the directory. A
 * "session started" marker file would encode the exact same guarantee with
 * more state to keep in sync; the constructor-before-recorder ordering
 * already gives it for free. (RouteScanViewModel is a Koin `factory`, so
 * this can re-run on every route-scan screen open, not just process launch —
 * harmless, since a completed sweep just finds nothing left the next time.)
 *
 * IMPORTANT: call [bookOrphanCaptureSessions] BEFORE this — it reads the
 * same journal (for a best-effort `locationCellId`) that this function
 * deletes once it's uploaded/queued.
 */
suspend fun recoverOrphanScanSessions(
    sessionUploader: EnvScanSessionUploader,
    captureChunkStore: CaptureChunkStore,
) {
    val dir = scanSessionsDirPath() ?: return
    for (path in listFilesInDir(dir)) {
        if (!path.endsWith(".jsonl")) continue
        val sessionId = path.substringAfterLast('/').removeSuffix(".jsonl")
        if (sessionId.isBlank()) continue
        try {
            val bytes = readLocalFileBytes(path) ?: continue
            val (samples, gateEvents) = parseSessionJournal(bytes.decodeToString().split("\n"))
            if (samples.isEmpty()) {
                // Nothing worth restoring (e.g. the scan never got a single
                // GPS fix before the app died) — just clean up.
                deleteLocalFile(path)
                continue
            }
            val chunkKeys = try {
                captureChunkStore.chunksForSession(sessionId).mapNotNull { it.r2Key }
            } catch (e: Exception) {
                DiagnosticsLog.error("env-capture", "recovery: captureChunks lookup failed for $sessionId", e)
                emptyList()
            }
            val manifest = EnvScanManifest(
                sessionId = sessionId,
                deviceId = CoverageDeviceId.value,
                deviceModel = getDeviceModel(),
                osVersion = getOsVersion(),
                startedAtMs = samples.first().atMs,
                completedAtMs = Clock.System.now().toEpochMilliseconds(),
                path = samples,
                scannedCells = emptyList(),
                scannedCellSizeMeters = 3.0,
                observedItems = emptyList(),
                photoKeys = emptyList(),
                lapKind = "recovered",
                gateEvents = gateEvents,
                captureChunks = chunkKeys.ifEmpty { null },
                captureSchema = if (chunkKeys.isNotEmpty()) 1 else null,
            )
            // uploadOrQueue never throws (it queues internally on failure) —
            // safe to delete the journal right after, in every case.
            sessionUploader.uploadOrQueue(manifest)
            deleteLocalFile(path)
            DiagnosticsLog.info("env-capture", "recovered orphaned session $sessionId (${samples.size} samples)")
        } catch (e: Exception) {
            DiagnosticsLog.error(
                "env-capture",
                "orphan journal recovery failed for $path (left in place, retried next launch)",
                e,
            )
        }
    }
}

private val CHUNK_FILENAME = Regex("""chunk-(\d+)\.(mov|jsonl|depth)""")

/** Placeholder cell id when an orphan capture session has no matching GPS
 *  journal to derive a real one from (see [bookOrphanCaptureSessions]) — the
 *  R2 key still keys uniquely on sessionId, so this only affects which
 *  location-cell bucket the DGX pipeline files the recovered chunks under. */
private const val UNKNOWN_LOCATION_CELL_ID = "0:0"

/**
 * Sweeps Documents/capture-sessions/{sessionId} directories the native
 * recorder wrote chunk files into but that never got booked into
 * [CaptureChunkStore] — the post-teardown-timeout orphan case (Task 4/5):
 * the app died (or the writer's async finalize never completed) before
 * `stopCaptureAndDrain()`'s packets reached [CaptureChunkStore.insertChunk].
 * Any session directory with files on disk but zero existing rows gets its
 * files booked as pending chunks — kind inferred from the native recorder's
 * own filename convention, `chunk-NNN.{mov,jsonl,depth}` (see
 * `ArSessionRecorder.swift`) — so
 * [com.gcc.app.platform.capture.CaptureChunkUploader] picks them up on its
 * next `flushPending()` exactly like a normally-finished chunk.
 *
 * `locationCellId` is recovered best-effort from the matching scan-session
 * journal's first GPS sample (same sessionId — ENV/SPOT scans are the only
 * v1 capture producers, so the two always pair up); when that journal is
 * missing or already gone, chunks book under [UNKNOWN_LOCATION_CELL_ID]
 * rather than blocking the upload entirely.
 *
 * Same launch-ordering guard as [recoverOrphanScanSessions]: runs before any
 * capture session starts, so it can never race a directory a live capture is
 * still writing into.
 */
suspend fun bookOrphanCaptureSessions(captureChunkStore: CaptureChunkStore) {
    val root = captureSessionsDirPath() ?: return
    for (sessionDir in listFilesInDir(root)) {
        val sessionId = sessionDir.substringAfterLast('/')
        if (sessionId.isBlank()) continue
        val files = listFilesInDir(sessionDir)
        if (files.isEmpty()) continue
        try {
            val existing = captureChunkStore.chunksForSession(sessionId)
            if (existing.isNotEmpty()) continue // already booked — leave it alone
            val cellId = readJournal(sessionId)?.first?.firstOrNull()?.let {
                val cell = SessionPointsEstimator.gridCell10m(it.lat, it.lng)
                "${cell.first}:${cell.second}"
            } ?: UNKNOWN_LOCATION_CELL_ID
            val nowMs = Clock.System.now().toEpochMilliseconds()
            var booked = 0
            for (filePath in files) {
                val name = filePath.substringAfterLast('/')
                val match = CHUNK_FILENAME.matchEntire(name) ?: continue
                val index = match.groupValues[1].toLongOrNull() ?: continue
                val kind = when (match.groupValues[2]) {
                    "mov" -> "video"
                    "jsonl" -> "sidecar"
                    "depth" -> "depth"
                    else -> continue
                }
                val bytes = readLocalFileBytes(filePath)
                if (bytes == null || bytes.isEmpty()) continue // nothing worth uploading
                captureChunkStore.insertChunk(
                    CaptureChunkRecord(
                        sessionId = sessionId,
                        chunkIndex = index,
                        kind = kind,
                        filePath = filePath,
                        sizeBytes = bytes.size.toLong(),
                        locationCellId = cellId,
                        r2Key = null,
                        uploadedAtMs = null,
                        createdAtMs = nowMs,
                    ),
                )
                booked++
            }
            if (booked > 0) {
                DiagnosticsLog.info("env-capture", "booked $booked orphan capture chunk(s) for session $sessionId")
            }
        } catch (e: Exception) {
            DiagnosticsLog.error("env-capture", "orphan capture-session booking failed for $sessionId", e)
        }
    }
}
