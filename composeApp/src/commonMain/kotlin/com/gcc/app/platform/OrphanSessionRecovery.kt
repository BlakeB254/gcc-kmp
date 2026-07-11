package com.gcc.app.platform

import com.gcc.shared.cache.CaptureChunkRecord
import com.gcc.shared.cache.CaptureChunkStore
import com.gcc.shared.model.EnvScanManifest
import com.gcc.shared.model.EnvScanPathSample
import com.gcc.shared.model.GeoRefEvent
import com.gcc.shared.serialization.GccJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.decodeFromString

/** Once-per-process guard for [runOrphanRecoverySweepOnce] — see its KDoc. */
private val sweepGuard = Mutex()
private var sweepLaunchedThisProcess = false

/**
 * Runs the two Pillar-6 recovery sweeps ([bookOrphanCaptureSessions] then
 * [recoverOrphanScanSessions] — that order matters, see the latter's KDoc)
 * at most ONCE per process launch. Both scan-screen ViewModels call this
 * from their `init` (SpotViewModel is a Koin single, RouteScanViewModel a
 * factory recreated per screen open, and either can be constructed first) —
 * without this guard two VM instances could race the same directories:
 * double-recovering a journal (duplicate "recovered" manifests) or
 * double-booking chunk rows. The flag flips before the sweep body runs, so
 * a mid-sweep failure is NOT retried until the next process launch — same
 * at-most-once semantics as a launch hook.
 */
suspend fun runOrphanRecoverySweepOnce(
    sessionUploader: EnvScanSessionUploader,
    captureChunkStore: CaptureChunkStore,
) {
    sweepGuard.withLock {
        if (sweepLaunchedThisProcess) return
        sweepLaunchedThisProcess = true
    }
    // Chunk booking MUST precede journal recovery: it reads the (still
    // present) scan-session journal for a best-effort locationCellId, and
    // recovery deletes that journal once uploaded/queued.
    bookOrphanCaptureSessions(captureChunkStore)
    recoverOrphanScanSessions(sessionUploader, captureChunkStore)
}

/** What [parseSessionJournal] salvages from a journal's surviving lines. */
data class ParsedSessionJournal(
    val samples: List<EnvScanPathSample>,
    val gateEvents: List<String>,
    val geoRefEvents: List<GeoRefEvent>,
)

/**
 * Parses a session journal's surviving lines. Any line that fails to decode
 * — a partially-written last line from an app kill mid-`write`, or any other
 * on-disk corruption — is skipped rather than aborting the whole parse:
 * recovery must salvage everything that DID land cleanly, not throw the
 * entire journal away for one bad line.
 */
fun parseSessionJournal(lines: List<String>): ParsedSessionJournal {
    val samples = mutableListOf<EnvScanPathSample>()
    val gateEvents = mutableListOf<String>()
    val geoRefEvents = mutableListOf<GeoRefEvent>()
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
            "georef" -> entry.geoRef?.let(geoRefEvents::add)
        }
    }
    return ParsedSessionJournal(samples.toList(), gateEvents.toList(), geoRefEvents.toList())
}

/** Reads and parses the journal for [sessionId], or null if it's missing/unreadable. */
private fun readJournal(sessionId: String): ParsedSessionJournal? {
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
 * `uploadOrQueue`). Reached only through [runOrphanRecoverySweepOnce] (both
 * ViewModels' `init`, the same point [EnvScanSessionUploader.flushPending]
 * runs).
 *
 * **Active-session exclusion — lazy journal-file creation, not a marker
 * file.** Constructing an [EnvScanSessionRecorder] creates NO file: the
 * journal only appears on disk once the first `addSample`/`noteGateEvent`
 * fires AND its asynchronous single-writer append has executed (see the
 * recorder's threading KDoc). So a recorder that merely exists — including
 * RouteScanViewModel's `lapSessionRecorder` property initializer, which runs
 * BEFORE the `init` block that launches this sweep — is invisible to the
 * directory listing here. Samples can't arrive until a scan actually starts
 * (user navigation + gate OUTDOOR + a GPS fix), which is far after the sweep
 * launched at VM construction; and [runOrphanRecoverySweepOnce]'s
 * once-per-process guard means later VM constructions never re-list the
 * directory at all. Residual race — a first sample landing between VM
 * construction and this coroutine's directory listing — is theoretical
 * (sub-second sweep vs. many-second scan start) and non-destructive if it
 * ever hit: the live session would upload once as a truncated "recovered"
 * manifest and again, completely, at Complete Scan; nothing is lost.
 *
 * IMPORTANT: [bookOrphanCaptureSessions] must run BEFORE this — it reads the
 * same journal (for a best-effort `locationCellId`) that this function
 * deletes once it's uploaded/queued. [runOrphanRecoverySweepOnce] owns that
 * ordering.
 */
internal suspend fun recoverOrphanScanSessions(
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
            val (samples, gateEvents, geoRefEvents) = parseSessionJournal(bytes.decodeToString().split("\n"))
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
                captureSchema = if (chunkKeys.isNotEmpty()) 2 else null,
                geoRefEvents = geoRefEvents.ifEmpty { null },
                arFrameConvention = EnvScanManifest.AR_FRAME_CONVENTION,
                // Crash-adjacent sessions are exactly where the diagnostics
                // tail matters most — though on a fresh launch it holds this
                // run's recovery-sweep lines, not the crashed run's (the ring
                // buffer is in-memory only).
                diagnosticsLog = DiagnosticsLog.tailForManifest(),
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
 * Same exclusion story as [recoverOrphanScanSessions]: capture-session
 * directories are only created by `ArEnvScanOverlay.startCapture` (a live
 * scan actually starting — far after VM construction launches this sweep),
 * and [runOrphanRecoverySweepOnce]'s once-per-process guard prevents any
 * later VM construction from re-listing while a capture runs.
 */
internal suspend fun bookOrphanCaptureSessions(captureChunkStore: CaptureChunkStore) {
    val root = captureSessionsDirPath() ?: return
    for (sessionDir in listFilesInDir(root)) {
        val sessionId = sessionDir.substringAfterLast('/')
        if (sessionId.isBlank()) continue
        val files = listFilesInDir(sessionDir)
        if (files.isEmpty()) continue
        try {
            val existing = captureChunkStore.chunksForSession(sessionId)
            if (existing.isNotEmpty()) continue // already booked — leave it alone
            val cellId = readJournal(sessionId)?.samples?.firstOrNull()?.let {
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
