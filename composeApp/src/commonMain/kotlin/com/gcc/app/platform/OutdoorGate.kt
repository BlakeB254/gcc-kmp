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
        const val INDOOR_ACCURACY_METERS = 25.0
        /** Horizontal accuracy better than this is outdoor evidence. */
        const val OUTDOOR_ACCURACY_METERS = 10.0
        /** Scene votes see the room directly — they outweigh single GPS fixes. */
        private const val SCENE_VOTE_WEIGHT = 2
    }

    private val indoorTs = ArrayDeque<Long>()
    private val outdoorTs = ArrayDeque<Long>()
    private var lastSceneIndoorTs: Long? = null
    private var lastIndoorAccuracyTs: Long? = null

    private val _state = MutableStateFlow(OutdoorState.UNCERTAIN)
    val state: StateFlow<OutdoorState> = _state.asStateFlow()

    fun onAccuracySample(horizontalAccuracyMeters: Double?, timestampMs: Long) {
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
            }
            SceneVote.OUTDOOR -> repeat(SCENE_VOTE_WEIGHT) { outdoorTs.addLast(timestampMs) }
            SceneVote.NONE -> Unit
        }
        evaluate(timestampMs)
    }

    private fun evaluate(nowMs: Long) {
        prune(nowMs)

        // Both independent signals agree it's indoors → flip immediately.
        val sceneSaysIndoor = lastSceneIndoorTs?.let { nowMs - it <= EVIDENCE_WINDOW_MS } == true
        val gpsSaysIndoor = lastIndoorAccuracyTs?.let { nowMs - it <= EVIDENCE_WINDOW_MS } == true
        if (sceneSaysIndoor && gpsSaysIndoor) {
            _state.value = OutdoorState.INDOOR
            return
        }

        val indoorFlip = span(indoorTs) >= DECISION_SPAN_MS && indoorTs.size > outdoorTs.size
        // Recovery/outdoor requires a sustained run with no indoor evidence inside it.
        val outdoorFlip = span(outdoorTs) >= DECISION_SPAN_MS &&
            indoorTs.none { it >= outdoorTs.first() }

        _state.value = when (_state.value) {
            OutdoorState.INDOOR -> if (outdoorFlip) OutdoorState.OUTDOOR else OutdoorState.INDOOR
            else -> when {
                indoorFlip -> OutdoorState.INDOOR
                outdoorFlip -> OutdoorState.OUTDOOR
                else -> _state.value
            }
        }
    }

    private fun prune(nowMs: Long) {
        val cutoff = nowMs - EVIDENCE_WINDOW_MS
        while (indoorTs.isNotEmpty() && indoorTs.first() < cutoff) indoorTs.removeFirst()
        while (outdoorTs.isNotEmpty() && outdoorTs.first() < cutoff) outdoorTs.removeFirst()
    }

    private fun span(entries: ArrayDeque<Long>): Long =
        if (entries.size < 2) 0L else entries.last() - entries.first()
}
