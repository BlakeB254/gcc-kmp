package com.gcc.app.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tri-state judgement of whether the worker is outdoors. UNCERTAIN never blocks. */
enum class OutdoorState { OUTDOOR, UNCERTAIN, INDOOR }

/** A single scene-classification vote from the AR camera feed. */
enum class SceneVote { INDOOR, OUTDOOR, NONE }

/**
 * Indoor/outdoor gate for the scan surfaces ("go outside" feature).
 *
 * There is no client-side building geometry, so the gate is a heuristic over
 * the two signals the device actually has:
 *  - GPS horizontal accuracy (sustained degradation is the standard indoor proxy)
 *  - Vision scene classification of AR frames (only on AR-capable surfaces)
 *
 * Pure state machine — callers push timestamped samples; timestamps come from
 * the samples themselves so tests drive time explicitly. Hysteresis: flipping
 * either way requires ~[DECISION_SPAN_MS] of consistent evidence, so a single
 * bad fix or one misclassified frame never flaps the UI. Exception: a scene
 * INDOOR vote combined with any degraded-accuracy sample in the window flips
 * INDOOR immediately — when both independent signals agree, waiting helps no one.
 */
class OutdoorGate {

    companion object {
        /** Evidence older than this is forgotten. */
        const val EVIDENCE_WINDOW_MS = 15_000L
        /** Consistent evidence must span at least this long to change state. */
        const val DECISION_SPAN_MS = 5_000L
        /** Horizontal accuracy worse than this is indoor evidence. */
        const val INDOOR_ACCURACY_METERS = 20.0
        /** Consecutive camera INDOOR votes that flip when GPS agrees (no
         *  outdoor-grade fixes) — object words alone never gate. */
        const val SCENE_STREAK_TO_FLIP = 2
        /** GPS fixes inside a mapped building footprint needed to flip alone.
         *  ONE: the map literally shows the dot inside a residence — flip
         *  immediately, including pre-emptively at scan open. */
        const val MAP_STREAK_TO_FLIP = 1
        /** Camera samples with zero outdoor cues (sky/street/vegetation) that
         *  flip INDOOR when GPS also shows no outdoor-grade evidence. */
        const val NO_OUTDOOR_CUES_TO_FLIP = 4
        /** Horizontal accuracy better than this is outdoor evidence. */
        const val OUTDOOR_ACCURACY_METERS = 10.0
        /** Scene votes see the room directly — they outweigh single GPS fixes. */
        private const val SCENE_VOTE_WEIGHT = 2
        /** Consecutive camera OUTDOOR votes that recover from INDOOR without
         *  waiting for 5s of clean GPS (fast auto-recovery — the mitigation
         *  that makes aggressive flipping acceptable). */
        const val OUTDOOR_SCENE_STREAK_TO_RECOVER = 2
        /** A ceiling plane seen within this window blocks recovery. */
        const val CEILING_RECENT_MS = 5_000L
        /** On an INDOOR flip, callers purge data recorded in this trailing
         *  window (frames/observations captured while walking inside). */
        const val PURGE_WINDOW_MS = 10_000L
    }

    private val indoorTs = ArrayDeque<Long>()
    private val outdoorTs = ArrayDeque<Long>()
    private var lastSceneIndoorTs: Long? = null
    private var sceneIndoorStreak = 0
    private var noOutdoorCueStreak = 0
    private var insideBuildingStreak = 0
    private var lastIndoorAccuracyTs: Long? = null
    private var sceneOutdoorStreak = 0
    private var lastCeilingTs: Long? = null

    private val _state = MutableStateFlow(OutdoorState.UNCERTAIN)
    val state: StateFlow<OutdoorState> = _state.asStateFlow()

    private val _captureAllowed = MutableStateFlow(false)
    /** Deny-by-default: capture/credit/persistence allowed ONLY while OUTDOOR.
     *  UNCERTAIN blocks capture (not the screen) — the privacy window closes
     *  structurally instead of racing detection latency. */
    val captureAllowed: StateFlow<Boolean> = _captureAllowed.asStateFlow()

    /** Timestamp of the most recent transition to INDOOR (purge coordination). */
    var lastIndoorFlipAtMs: Long? = null
        private set

    /** Field-report escape hatch: the fused evidence (GPS shadowing, dark
     *  frames, map footprints) can misread a worker who IS outside. The
     *  override pins OUTDOOR for the rest of this gate's life (one scan
     *  session — the gate is remembered per screen). */
    private var manualOverride = false

    fun forceOutdoors() {
        manualOverride = true
        setState(OutdoorState.OUTDOOR, 0L)
        DiagnosticsLog.info("outdoor-gate", "manual override — user confirmed outdoors")
    }

    private fun setState(newState: OutdoorState, nowMs: Long) {
        if (newState == OutdoorState.INDOOR && _state.value != OutdoorState.INDOOR) {
            lastIndoorFlipAtMs = nowMs
            sceneOutdoorStreak = 0
        }
        _state.value = newState
        _captureAllowed.value = newState == OutdoorState.OUTDOOR
    }

    private var sampleCount = 0

    fun onAccuracySample(horizontalAccuracyMeters: Double?, timestampMs: Long) {
        sampleCount += 1
        if (sampleCount % 15 == 1) {
            DiagnosticsLog.info(
                "outdoor-gate",
                "accuracy sample #$sampleCount: " +
                    (horizontalAccuracyMeters?.let { "${(it * 10).toInt() / 10.0}m" } ?: "null") +
                    " (indoor=${indoorTs.size} outdoor=${outdoorTs.size} state=${_state.value})",
            )
        }
        when {
            horizontalAccuracyMeters == null -> Unit
            horizontalAccuracyMeters > INDOOR_ACCURACY_METERS -> {
                indoorTs.addLast(timestampMs)
                lastIndoorAccuracyTs = timestampMs
            }
            horizontalAccuracyMeters < OUTDOOR_ACCURACY_METERS -> outdoorTs.addLast(timestampMs)
        }
        evaluate(timestampMs)
    }

    fun onSceneVote(vote: SceneVote, timestampMs: Long) {
        when (vote) {
            SceneVote.INDOOR -> {
                repeat(SCENE_VOTE_WEIGHT) { indoorTs.addLast(timestampMs) }
                lastSceneIndoorTs = timestampMs
                sceneIndoorStreak += 1
                noOutdoorCueStreak += 1
                sceneOutdoorStreak = 0
            }
            SceneVote.OUTDOOR -> {
                repeat(SCENE_VOTE_WEIGHT) { outdoorTs.addLast(timestampMs) }
                sceneIndoorStreak = 0
                noOutdoorCueStreak = 0
                sceneOutdoorStreak += 1
                // Fast auto-recovery: the camera seeing street/sky twice in a
                // row is stronger outdoor proof than urban GPS accuracy.
                val ceilingRecent = lastCeilingTs?.let { timestampMs - it <= CEILING_RECENT_MS } == true
                if (_state.value == OutdoorState.INDOOR &&
                    sceneOutdoorStreak >= OUTDOOR_SCENE_STREAK_TO_RECOVER &&
                    insideBuildingStreak == 0 && !ceilingRecent
                ) {
                    DiagnosticsLog.info(
                        "outdoor-gate",
                        "OUTDOOR (camera outdoor ×$sceneOutdoorStreak — fast recovery)",
                    )
                    // The camera saw indoors seconds ago; those stamps must not
                    // immediately re-flip via the sustained-indoor path.
                    indoorTs.clear()
                    lastSceneIndoorTs = null
                    lastIndoorAccuracyTs = null
                    setState(OutdoorState.OUTDOOR, timestampMs)
                    return
                }
            }
            SceneVote.NONE -> {
                // Not conclusive alone — but enclosed spaces never show
                // outdoor cues, so the absence accumulates.
                noOutdoorCueStreak += 1
                sceneOutdoorStreak = 0
            }
        }
        // Fusion rules (a computer on a park bench must NOT gate): a positive
        // camera INDOOR streak is unmuted evidence on its own — unlike cue
        // absence (pointing at pavement), a room is unambiguous. Cue absence
        // still requires GPS to agree by omission (zero outdoor-grade fixes).
        if (_state.value != OutdoorState.INDOOR) {
            if (sceneIndoorStreak >= SCENE_STREAK_TO_FLIP) {
                DiagnosticsLog.info("outdoor-gate", "INDOOR (camera indoor ×$sceneIndoorStreak)")
                if (!manualOverride) setState(OutdoorState.INDOOR, timestampMs)
                return
            }
            if (noOutdoorCueStreak >= NO_OUTDOOR_CUES_TO_FLIP && outdoorTs.isEmpty()) {
                DiagnosticsLog.info(
                    "outdoor-gate",
                    "INDOOR (no outdoor cues in $noOutdoorCueStreak samples + no outdoor-grade GPS)",
                )
                if (!manualOverride) setState(OutdoorState.INDOOR, timestampMs)
                return
            }
        }
        evaluate(timestampMs)
    }

    /**
     * ARKit plane evidence: a ceiling-classified plane above the camera.
     * Outdoors there is essentially never a classified ceiling — this is the
     * cheapest high-precision indoor signal and it overrides the manual pin.
     */
    fun onCeilingPlane(detected: Boolean, timestampMs: Long) {
        if (detected) {
            lastCeilingTs = timestampMs
            repeat(SCENE_VOTE_WEIGHT) { indoorTs.addLast(timestampMs) }
            if (_state.value != OutdoorState.INDOOR) {
                DiagnosticsLog.info("outdoor-gate", "INDOOR (ceiling plane detected)")
                if (manualOverride) {
                    manualOverride = false
                    DiagnosticsLog.info("outdoor-gate", "manual override cleared by ceiling plane")
                }
                setState(OutdoorState.INDOOR, timestampMs)
                return
            }
        }
        evaluate(timestampMs)
    }

    /**
     * Map evidence: the latest GPS fix is inside a mapped building footprint.
     * The most decisive signal — the map literally shows the dot inside a
     * residence; no camera vocabulary required.
     */
    fun onMapEvidence(insideBuilding: Boolean, timestampMs: Long) {
        if (insideBuilding) {
            insideBuildingStreak += 1
            repeat(SCENE_VOTE_WEIGHT) { indoorTs.addLast(timestampMs) }
            if (insideBuildingStreak >= MAP_STREAK_TO_FLIP && _state.value != OutdoorState.INDOOR) {
                DiagnosticsLog.info("outdoor-gate", "INDOOR (GPS inside mapped building ×$insideBuildingStreak)")
                if (manualOverride) {
                    manualOverride = false
                    DiagnosticsLog.info("outdoor-gate", "manual override cleared by map containment")
                }
                setState(OutdoorState.INDOOR, timestampMs)
                return
            }
        } else {
            insideBuildingStreak = 0
        }
        evaluate(timestampMs)
    }

    private fun evaluate(nowMs: Long) {
        prune(nowMs)

        // Both independent signals agree it's indoors → flip immediately.
        val sceneSaysIndoor = lastSceneIndoorTs?.let { nowMs - it <= EVIDENCE_WINDOW_MS } == true
        val gpsSaysIndoor = lastIndoorAccuracyTs?.let { nowMs - it <= EVIDENCE_WINDOW_MS } == true
        if (sceneSaysIndoor && gpsSaysIndoor) {
            if (_state.value != OutdoorState.INDOOR) {
                DiagnosticsLog.info("outdoor-gate", "INDOOR (scene vote + degraded GPS agree)")
            }
            if (!manualOverride) setState(OutdoorState.INDOOR, nowMs)
            return
        }

        val indoorFlip = span(indoorTs) >= DECISION_SPAN_MS && indoorTs.size > outdoorTs.size
        // Recovery/outdoor requires a sustained run with no indoor evidence inside it.
        val outdoorFlip = span(outdoorTs) >= DECISION_SPAN_MS &&
            indoorTs.none { it >= outdoorTs.first() }

        val ceilingRecent = lastCeilingTs?.let { nowMs - it <= CEILING_RECENT_MS } == true
        val newState = when (_state.value) {
            OutdoorState.INDOOR -> if (outdoorFlip && insideBuildingStreak == 0 && !ceilingRecent) {
                sceneIndoorStreak = 0
                noOutdoorCueStreak = 0
                sceneOutdoorStreak = 0
                OutdoorState.OUTDOOR
            } else OutdoorState.INDOOR
            else -> when {
                indoorFlip -> OutdoorState.INDOOR
                outdoorFlip -> OutdoorState.OUTDOOR
                else -> _state.value
            }
        }
        if (newState != _state.value) {
            DiagnosticsLog.info(
                "outdoor-gate",
                "$newState (indoor=${indoorTs.size} outdoor=${outdoorTs.size} " +
                    "indoorSpan=${span(indoorTs)}ms outdoorSpan=${span(outdoorTs)}ms)",
            )
        }
        setState(newState, nowMs)
    }

    private fun prune(nowMs: Long) {
        val cutoff = nowMs - EVIDENCE_WINDOW_MS
        while (indoorTs.isNotEmpty() && indoorTs.first() < cutoff) indoorTs.removeFirst()
        while (outdoorTs.isNotEmpty() && outdoorTs.first() < cutoff) outdoorTs.removeFirst()
    }

    private fun span(entries: ArrayDeque<Long>): Long =
        if (entries.size < 2) 0L else entries.last() - entries.first()
}
