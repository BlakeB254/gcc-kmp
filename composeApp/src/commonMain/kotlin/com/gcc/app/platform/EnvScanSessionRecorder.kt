package com.gcc.app.platform

import com.gcc.shared.model.EnvScanCell
import com.gcc.shared.model.EnvScanPathSample
import com.gcc.shared.serialization.GccJson
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.random.Random

/**
 * One line of [EnvScanSessionRecorder]'s on-disk journal (Pillar 6) — a
 * JSONL stream, one entry per line, either a path sample or a gate-event
 * string. Shared with [parseSessionJournal]/[recoverOrphanScanSessions] so
 * the write side (here) and the launch-time read side never drift apart.
 */
@Serializable
data class EnvScanJournalEntry(
    val type: String,
    val sample: EnvScanPathSample? = null,
    val gate: String? = null,
)

/**
 * Accumulates one environment-scan session (screen-open → Complete Scan):
 * the GPS/facing track, and the coverage cells credited — everything the
 * session manifest needs beyond the photos the ViewModel already holds.
 *
 * Pillar 6 (durability): when [journalPath] is non-null (iOS only — see
 * [sessionJournalPath]; desktop/android pass null and stay pure in-memory,
 * matching pre-Pillar-6 behavior) every [addSample]/[noteGateEvent] call also
 * appends one JSONL line to that file, so an app kill mid-scan doesn't lose
 * the track — [recoverOrphanScanSessions] rebuilds and uploads it from the
 * journal at next launch. A journal write failure never reaches the caller:
 * it's caught here, logged exactly once, and [journalHealthy] flips off —
 * every later call degrades silently to memory-only (today's behavior)
 * instead of retrying (and re-logging) a broken path on every sample for the
 * rest of an hour-long scan.
 */
class EnvScanSessionRecorder(
    val sessionId: String = generateSessionId(),
    private val journalPath: String? = null,
) {
    val startedAtMs: Long = Clock.System.now().toEpochMilliseconds()

    private val samples = mutableListOf<EnvScanPathSample>()
    private val gateEvents = mutableListOf<String>()
    private var journalHealthy = journalPath != null

    fun addSample(lat: Double, lng: Double, headingDegrees: Float?, accuracyMeters: Double?) {
        if (samples.size >= MAX_SAMPLES) return
        val sample = EnvScanPathSample(
            lat = lat,
            lng = lng,
            headingDegrees = headingDegrees,
            accuracyMeters = accuracyMeters,
            atMs = Clock.System.now().toEpochMilliseconds(),
        )
        samples.add(sample)
        appendJournalLine(EnvScanJournalEntry(type = "sample", sample = sample))
    }

    /** Privacy purge: drop everything recorded at/after [cutoffMs] — called on
     *  an INDOOR flip so the trailing walk-inside seconds never upload. */
    fun purgeSince(cutoffMs: Long) {
        val before = samples.size
        samples.removeAll { it.atMs >= cutoffMs }
        noteGateEvent("purge@${Clock.System.now().toEpochMilliseconds()} dropped=${before - samples.size}")
        // Rare event: rewrite the whole journal from what survived rather
        // than trying to selectively delete lines — a purged sample must
        // never linger on disk for orphan recovery to resurrect later.
        rewriteJournal()
    }

    /** Audit trail: gate flips, override presses/yields, purges. */
    fun noteGateEvent(event: String) {
        if (gateEvents.size < 200) gateEvents.add(event)
        appendJournalLine(EnvScanJournalEntry(type = "gate", gate = event))
    }

    fun draft(scannedCellCenters: List<Pair<Double, Double>>): EnvScanSessionDraft =
        EnvScanSessionDraft(
            sessionId = sessionId,
            startedAtMs = startedAtMs,
            path = samples.toList(),
            scannedCells = scannedCellCenters.map { (lat, lng) -> EnvScanCell(lat, lng) },
            gateEvents = gateEvents.toList(),
        )

    private fun appendJournalLine(entry: EnvScanJournalEntry) {
        val path = journalPath ?: return
        if (!journalHealthy) return
        try {
            appendLineToFile(path, GccJson.encodeToString(entry))
        } catch (e: Throwable) {
            journalHealthy = false
            DiagnosticsLog.error("env-capture", "session journal degraded to memory-only ($sessionId)", e)
        }
    }

    private fun rewriteJournal() {
        val path = journalPath ?: return
        if (!journalHealthy) return
        try {
            val lines = buildList {
                samples.forEach { add(GccJson.encodeToString(EnvScanJournalEntry(type = "sample", sample = it))) }
                gateEvents.forEach { add(GccJson.encodeToString(EnvScanJournalEntry(type = "gate", gate = it))) }
            }
            writeFileLines(path, lines)
        } catch (e: Throwable) {
            journalHealthy = false
            DiagnosticsLog.error("env-capture", "session journal rewrite failed, degraded to memory-only ($sessionId)", e)
        }
    }

    companion object {
        /** ~1Hz for an hour — nobody scans longer in one session. */
        private const val MAX_SAMPLES = 3600

        /**
         * Same format the primary constructor used to generate inline.
         * Exposed so a call site can compute [sessionJournalPath] from the
         * id BEFORE constructing the recorder — the journal path is a
         * constructor parameter, not something the recorder can derive
         * itself after the fact.
         */
        fun generateSessionId(): String = buildString {
            append("env-")
            append(Clock.System.now().toEpochMilliseconds())
            append("-")
            repeat(6) { append("abcdefghijklmnopqrstuvwxyz0123456789".random(Random)) }
        }
    }
}

/** Immutable snapshot handed to the ViewModel at Complete Scan. */
data class EnvScanSessionDraft(
    val sessionId: String,
    val startedAtMs: Long,
    val path: List<EnvScanPathSample>,
    val scannedCells: List<EnvScanCell>,
    val gateEvents: List<String> = emptyList(),
)
