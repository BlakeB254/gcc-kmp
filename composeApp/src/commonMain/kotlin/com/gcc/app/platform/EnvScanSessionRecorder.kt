package com.gcc.app.platform

import com.gcc.shared.model.EnvScanCell
import com.gcc.shared.model.EnvScanPathSample
import com.gcc.shared.model.GeoRefEvent
import com.gcc.shared.serialization.GccJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.concurrent.Volatile
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
    /** AR↔world registration pairing (type "georef") — see [GeoRefEvent]. */
    val geoRef: GeoRefEvent? = null,
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
 * journal at next launch.
 *
 * Threading: journal I/O never runs on the caller's thread (SpotScreen calls
 * addSample from a main-thread coroutine). Appends and rewrites are enqueued
 * onto [journalScope] — a private single-writer executor
 * (`limitedParallelism(1)`, so lines land in enqueue order) on
 * Dispatchers.Default. The journal file therefore only comes into existence
 * once the first sample/gate event is recorded AND its async write has run —
 * never at recorder construction (launch recovery relies on this; see
 * [recoverOrphanScanSessions]). The scope is deliberately never cancelled:
 * writes are tiny, and process death mid-line is the accepted crash window
 * (the recovery parser skips a torn last line).
 *
 * A journal write failure never reaches the caller: it's caught on the
 * writer coroutine, logged exactly once (sequential single-writer execution
 * guarantees every later queued write observes the flipped [journalHealthy]
 * flag), and the recorder degrades silently to memory-only — pre-Pillar-6
 * behavior — instead of retrying (and re-logging) a broken path on every
 * sample for the rest of an hour-long scan.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EnvScanSessionRecorder(
    val sessionId: String = generateSessionId(),
    private val journalPath: String? = null,
) {
    val startedAtMs: Long = Clock.System.now().toEpochMilliseconds()

    private val samples = mutableListOf<EnvScanPathSample>()
    private val gateEvents = mutableListOf<String>()
    private val geoRefEvents = mutableListOf<GeoRefEvent>()

    @Volatile
    private var journalHealthy = journalPath != null

    /** Single-writer journal executor — see the class KDoc's threading note. */
    private val journalScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

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
        enqueueJournalAppend(EnvScanJournalEntry(type = "sample", sample = sample))
    }

    /** Privacy purge: drop everything recorded at/after [cutoffMs] — called on
     *  an INDOOR flip so the trailing walk-inside seconds never upload. */
    fun purgeSince(cutoffMs: Long) {
        val before = samples.size
        samples.removeAll { it.atMs >= cutoffMs }
        // The purge audit event goes to MEMORY only here — the full rewrite
        // below carries it to disk, so also appending it would double-write.
        val purgeEvent = "purge@${Clock.System.now().toEpochMilliseconds()} dropped=${before - samples.size}"
        if (gateEvents.size < MAX_GATE_EVENTS) gateEvents.add(purgeEvent)
        // Rare event: rewrite the whole journal from what survived rather
        // than trying to selectively delete lines — a purged sample must
        // never linger on disk for orphan recovery to resurrect later.
        enqueueJournalRewrite()
    }

    /** Audit trail: gate flips, override presses/yields, purges. */
    fun noteGateEvent(event: String) {
        if (gateEvents.size >= MAX_GATE_EVENTS) return // memory capped ⇒ skip the journal too
        gateEvents.add(event)
        enqueueJournalAppend(EnvScanJournalEntry(type = "gate", gate = event))
    }

    /**
     * AR↔world registration pairing from `updateGeoReference` — what lets
     * the DGX splat pipeline place this session's AR-local pose stream into
     * the world splat layer. Pairings arrive on every usable GPS fix (~1Hz);
     * thinned to one per [GEO_REF_MIN_INTERVAL_MS] — registration is solved
     * least-squares over the set, so density beyond that adds bytes, not
     * accuracy.
     */
    fun addGeoRefEvent(event: GeoRefEvent) {
        if (geoRefEvents.size >= MAX_GEO_REF_EVENTS) return
        val last = geoRefEvents.lastOrNull()
        if (last != null && event.atMs - last.atMs < GEO_REF_MIN_INTERVAL_MS) return
        geoRefEvents.add(event)
        enqueueJournalAppend(EnvScanJournalEntry(type = "georef", geoRef = event))
    }

    /**
     * @param coveragePercent [CoverageTracker.coveragePercent] at completion — rides
     *   the draft so downstream summary UI (see [ScanScore]) doesn't need its own
     *   plumbing back to the live tracker. Defaulted for callers that don't score
     *   (route laps, tests).
     * @param closePercent [CoverageTracker.closePercent] at completion.
     * @param steadinessFraction fraction of session tracking ticks that were
     *   NORMAL; 1.0 default (no penalty) when the caller doesn't track it.
     */
    fun draft(
        scannedCellCenters: List<Pair<Double, Double>>,
        coveragePercent: Double = 0.0,
        closePercent: Double = 0.0,
        steadinessFraction: Double = 1.0,
        surfaceBreakdown: Map<String, Int> = emptyMap(),
    ): EnvScanSessionDraft =
        EnvScanSessionDraft(
            sessionId = sessionId,
            startedAtMs = startedAtMs,
            path = samples.toList(),
            scannedCells = scannedCellCenters.map { (lat, lng) -> EnvScanCell(lat, lng) },
            gateEvents = gateEvents.toList(),
            geoRefEvents = geoRefEvents.toList(),
            coveragePercent = coveragePercent,
            closePercent = closePercent,
            steadinessFraction = steadinessFraction,
            surfaceBreakdown = surfaceBreakdown,
        )

    /**
     * Waits until every journal write enqueued so far has executed. Tests
     * only: the single-writer executor runs jobs in FIFO order, so joining a
     * fresh no-op job proves everything queued before it has finished.
     */
    internal suspend fun awaitJournalIdle() {
        journalScope.launch { }.join()
    }

    private fun enqueueJournalAppend(entry: EnvScanJournalEntry) {
        val path = journalPath ?: return
        if (!journalHealthy) return
        journalScope.launch {
            if (!journalHealthy) return@launch // degraded while this was queued
            try {
                appendLineToFile(path, GccJson.encodeToString(entry))
            } catch (e: Throwable) {
                degradeJournal("session journal degraded to memory-only", e)
            }
        }
    }

    private fun enqueueJournalRewrite() {
        val path = journalPath ?: return
        if (!journalHealthy) return
        // Snapshot into serialized lines ON the caller's thread — the mutable
        // lists belong to the recorder's owning thread, so the writer
        // coroutine must not read them later. NOTE: the rewrite groups all
        // samples first, then all gate events (the append path interleaves
        // them chronologically) — deliberate; [parseSessionJournal] buckets
        // by entry type and never depends on line order.
        val lines = buildList {
            samples.forEach { add(GccJson.encodeToString(EnvScanJournalEntry(type = "sample", sample = it))) }
            gateEvents.forEach { add(GccJson.encodeToString(EnvScanJournalEntry(type = "gate", gate = it))) }
            geoRefEvents.forEach { add(GccJson.encodeToString(EnvScanJournalEntry(type = "georef", geoRef = it))) }
        }
        journalScope.launch {
            if (!journalHealthy) return@launch
            try {
                writeFileLines(path, lines)
            } catch (e: Throwable) {
                degradeJournal("session journal rewrite failed, degraded to memory-only", e)
            }
        }
    }

    /** Only ever runs on the single-writer coroutine, so the flag flip is
     *  observed by every later queued write — exactly one log per recorder. */
    private fun degradeJournal(message: String, e: Throwable) {
        journalHealthy = false
        DiagnosticsLog.error("env-capture", "$message ($sessionId)", e)
    }

    companion object {
        /** ~1Hz for an hour — nobody scans longer in one session. */
        private const val MAX_SAMPLES = 3600
        private const val MAX_GATE_EVENTS = 200
        /** ~1 pairing per 5s for an hour; registration is least-squares over
         *  the set, so more density adds bytes, not accuracy. */
        private const val MAX_GEO_REF_EVENTS = 720
        private const val GEO_REF_MIN_INTERVAL_MS = 5_000L

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

/**
 * Immutable snapshot handed to the ViewModel at Complete Scan.
 *
 * [coveragePercent]/[closePercent]/[steadinessFraction] are the session's
 * final [CoverageTracker] + tracking-steadiness readings, defaulted so every
 * existing caller (route laps, tests) that doesn't score keeps compiling
 * unchanged — see [ScanScore.compute] for how the summary card's A-F grade
 * is derived from them.
 */
data class EnvScanSessionDraft(
    val sessionId: String,
    val startedAtMs: Long,
    val path: List<EnvScanPathSample>,
    val scannedCells: List<EnvScanCell>,
    val gateEvents: List<String> = emptyList(),
    /** AR↔world registration pairings — see [GeoRefEvent]. */
    val geoRefEvents: List<GeoRefEvent> = emptyList(),
    val coveragePercent: Double = 0.0,
    val closePercent: Double = 0.0,
    val steadinessFraction: Double = 1.0,
    /** Surface wire name -> credited-cell count at completion (see [SurfaceType]). */
    val surfaceBreakdown: Map<String, Int> = emptyMap(),
)
