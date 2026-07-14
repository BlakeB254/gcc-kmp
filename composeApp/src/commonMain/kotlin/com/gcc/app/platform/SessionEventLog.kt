package com.gcc.app.platform

import com.gcc.shared.model.ScanEventJson
import com.gcc.shared.model.ScanSessionEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.concurrent.Volatile

/**
 * The scan session's flight recorder (scan-session-replay PRD Phase 1): a
 * structured, session-relative JSONL event stream — every caption shown,
 * tap→raycast→pill transition, confirm/reject, auto-detection, outdoor-gate
 * change, coverage milestone, lap transition, upload outcome, and
 * diagnostics line — dense enough to replay "what the worker saw and what
 * the system did" without a screen recording.
 *
 * Sibling of [EnvScanSessionRecorder] and deliberately built on the same
 * plumbing: a private single-writer FIFO executor owns BOTH the in-memory
 * event list and the on-disk mirror (`{sessionId}.events.jsonl` next to the
 * session journal, iOS only — see [sessionEventLogPath]), so events from any
 * thread (UI callbacks, upload workers, [DiagnosticsLog]'s sink) serialize
 * without locks and land on disk in order for crash salvage
 * ([recoverOrphanScanSessions] uploads a stranded event log with the
 * recovered manifest).
 *
 * At Complete Scan the ViewModel calls [snapshotLines], gzips the result and
 * uploads it as its own R2 artifact (`events.jsonl.gz`, ALWAYS — unlike the
 * policy-gated capture chunks), referenced from the manifest via
 * `eventLogKey`.
 *
 * Dedupe guards ([caption]/[pillPhase]) and the milestone tracker run on the
 * caller's thread with @Volatile state — captions/pills/coverage all arrive
 * from the main thread in practice; a rare racy duplicate line is harmless
 * and costs a few bytes, never correctness.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionEventLog(
    val sessionId: String,
    private val startedAtMs: Long,
    private val logPath: String? = null,
    private val maxEvents: Int = MAX_EVENTS,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val events = mutableListOf<ScanSessionEvent>()

    @Volatile
    private var enqueuedCount = 0

    @Volatile
    private var droppedCount = 0

    @Volatile
    private var lastCaption: String? = null

    // Seeded with IDLE so the pill collector's initial no-candidate state
    // dedupes away instead of opening every session with a phantom event.
    @Volatile
    private var lastPillPhase: String? = "IDLE"

    @Volatile
    private var highestMilestone = 0

    @Volatile
    private var journalHealthy = logPath != null

    /** Single-writer executor — same pattern as [EnvScanSessionRecorder]. */
    private val writerScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    /** Instruction/status caption shown to the worker; consecutive duplicates collapse. */
    fun caption(text: String) {
        if (text == lastCaption) return
        lastCaption = text
        record(ScanSessionEvent(t = elapsed(), type = "caption", msg = text))
    }

    /**
     * A screen tap seeking a litter candidate (Option A tap authority),
     * emitted at the source ([ArEnvScanOverlay.focusAt]) so EVERY tap is
     * visible — [state] = "suppressed" when the auto-lock confirm card was
     * already up (the tap never reaches the tap-candidate state machine),
     * null for a tap that proceeds normally.
     */
    fun tap(state: String? = null) = record(ScanSessionEvent(t = elapsed(), type = "tap", state = state))

    /** Tap-candidate pill phase transition; consecutive duplicates collapse. */
    fun pillPhase(phase: String, label: String? = null, confidence: Float? = null, distanceM: Double? = null) {
        if (phase == lastPillPhase) return
        lastPillPhase = phase
        record(
            ScanSessionEvent(
                t = elapsed(), type = "pill", state = phase,
                label = label, confidence = confidence, distanceM = distanceM,
            )
        )
    }

    fun confirm(label: String?) = record(ScanSessionEvent(t = elapsed(), type = "confirm", label = label))

    fun reject(label: String?) = record(ScanSessionEvent(t = elapsed(), type = "reject", label = label))

    /**
     * Ground-candidate classification surfaced to the worker
     * (reticle/observation). [source] identifies which intent path produced
     * it (geometry/dwell/tap/vision); [outcome] is the verdict
     * (accepted/excluded:<label>/timeout).
     */
    fun detection(
        label: String?,
        confidence: Float?,
        distanceM: Double?,
        source: String? = null,
        outcome: String? = null,
    ) = record(
        ScanSessionEvent(
            t = elapsed(), type = "detection",
            label = label, confidence = confidence, distanceM = distanceM,
            source = source, outcome = outcome,
        )
    )

    /** Outdoor-gate state change; [cause] explains WHY (scene votes, ceiling plane, override…). */
    fun gate(state: String, cause: String? = null) =
        record(ScanSessionEvent(t = elapsed(), type = "gate", state = state, msg = cause))

    /** Coverage progress: emits one milestone event per 25/50/75/95% crossing. */
    fun coverage(percent: Double) {
        for (milestone in MILESTONES) {
            if (milestone > highestMilestone && percent >= milestone) {
                highestMilestone = milestone
                record(ScanSessionEvent(t = elapsed(), type = "coverage", pct = milestone))
            }
        }
    }

    /** Block-route lap transition (`baseline`/`clean`/review…). */
    fun lap(kind: String, msg: String? = null) =
        record(ScanSessionEvent(t = elapsed(), type = "lap", state = kind, msg = msg))

    /** Upload attempt outcome for a session artifact ([kind] = chunk/photo/…). */
    fun upload(kind: String, ok: Boolean, detail: String? = null, key: String? = null) =
        record(ScanSessionEvent(t = elapsed(), type = "upload", tag = kind, ok = ok, msg = detail, key = key))

    /** A [DiagnosticsLog] entry mirrored into the session stream (via its sink). */
    fun diag(level: String, tag: String, message: String) =
        record(ScanSessionEvent(t = elapsed(), type = "diag", state = level, tag = tag, msg = message))

    /**
     * The full stream as JSONL lines, for gzip + upload at Complete Scan.
     * Runs on the single writer, so every event recorded before this call is
     * included. Appends a synthetic `truncated` marker when the cap dropped
     * events ([ScanSessionEvent.pct] = dropped count).
     */
    suspend fun snapshotLines(): List<String> {
        val deferred = CompletableDeferred<List<String>>()
        writerScope.launch {
            val lines = events.mapTo(mutableListOf()) { ScanEventJson.encodeToString(ScanSessionEvent.serializer(), it) }
            if (droppedCount > 0) {
                lines.add(
                    ScanEventJson.encodeToString(
                        ScanSessionEvent.serializer(),
                        ScanSessionEvent(t = elapsed(), type = "truncated", pct = droppedCount),
                    )
                )
            }
            deferred.complete(lines)
        }
        return deferred.await()
    }

    /** Waits until every write enqueued so far has executed. Tests only. */
    internal suspend fun awaitIdle() {
        writerScope.launch { }.join()
    }

    private fun elapsed(): Long = (nowMs() - startedAtMs).coerceAtLeast(0L)

    private fun record(event: ScanSessionEvent) {
        // Caller-thread cap check: @Volatile counter, so concurrent recorders
        // can overshoot by a few events at worst — bounded, never unbounded.
        if (enqueuedCount >= maxEvents) {
            droppedCount += 1
            return
        }
        enqueuedCount += 1
        writerScope.launch {
            events.add(event)
            val path = logPath ?: return@launch
            if (!journalHealthy) return@launch
            try {
                appendLineToFile(path, ScanEventJson.encodeToString(ScanSessionEvent.serializer(), event))
            } catch (e: Throwable) {
                // Same degrade-once contract as the session journal: flag flips
                // on the single writer, so every later queued write observes it.
                journalHealthy = false
                DiagnosticsLog.error("env-capture", "event log degraded to memory-only ($sessionId)", e)
            }
        }
    }

    companion object {
        /** Comfortably above a busy hour-long session (~1 event/s + bursts)
         *  while keeping the gzipped artifact well under the 200KB budget. */
        private const val MAX_EVENTS = 4000
        private val MILESTONES = intArrayOf(25, 50, 75, 95)
    }
}
