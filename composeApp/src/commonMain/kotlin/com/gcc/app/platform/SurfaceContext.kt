package com.gcc.app.platform

/**
 * Ground surface type under the scan, derived from the whole-frame
 * classifier that already runs every analysis tick (zero added Vision cost).
 * Three consumers (build-164 rebuild, Blake's 2026-07-27 ask):
 *
 *  1. Coverage cells are tagged as they're credited ([CoverageTracker]),
 *     giving the minimap/summary real ground texture and the uploaded scan
 *     events reconstruction context.
 *  2. The scan summary reports a surface breakdown of the walked area.
 *  3. Stage 2 (custom litter detector) gets labeled backdrop context per
 *     capture — "identify the litter against its backdrop".
 *
 * [wire] strings are the upload vocabulary — additive, optional field on
 * coverage events; the server stores what it doesn't understand leniently.
 */
enum class SurfaceType(val wire: String) {
    GRASS("grass"),
    PAVEMENT("pavement"),
    STREET("street"),
    DIRT("dirt"),
}

object SurfaceContextClassifier {

    /** Minimum per-label confidence for a surface vote. Deliberately below the
     *  scene floor (0.3): surface labels compete only with each other here,
     *  and a downward-pointed frame often ranks "grass"/"asphalt" at 0.2x. */
    const val SURFACE_CONFIDENCE = 0.20f

    private val GRASS_TOKENS = setOf("grass", "lawn", "turf", "meadow", "vegetation")
    private val STREET_TOKENS = setOf("road", "street", "asphalt", "highway", "alley")
    private val PAVEMENT_TOKENS = setOf("sidewalk", "pavement", "concrete", "paving", "walkway", "brick", "cobblestone")
    private val DIRT_TOKENS = setOf("soil", "dirt", "sand", "mud", "gravel", "ground")

    /**
     * The surface type the whole-frame labels support most (confidence-summed
     * across matching labels), or null when nothing surface-like clears
     * [SURFACE_CONFIDENCE] — e.g. the camera is at the horizon. Token
     * matching mirrors [GroundExclusionRules] (whole words via
     * [TrashDetectionRules.tokensOf]).
     */
    fun fromSceneLabels(labels: List<ClassificationObservation>): SurfaceType? {
        val scores = HashMap<SurfaceType, Float>()
        for (obs in labels) {
            if (obs.confidence < SURFACE_CONFIDENCE) continue
            val tokens = TrashDetectionRules.tokensOf(obs.identifier)
            fun vote(type: SurfaceType, vocab: Set<String>) {
                if (tokens.any { it in vocab }) scores[type] = (scores[type] ?: 0f) + obs.confidence
            }
            vote(SurfaceType.GRASS, GRASS_TOKENS)
            vote(SurfaceType.STREET, STREET_TOKENS)
            vote(SurfaceType.PAVEMENT, PAVEMENT_TOKENS)
            vote(SurfaceType.DIRT, DIRT_TOKENS)
        }
        return scores.maxByOrNull { it.value }?.key
    }
}

/**
 * Rolling majority over the last [windowSize] non-null surface votes —
 * smooths tick-to-tick classifier flicker so a single "asphalt" guess while
 * panning across a lawn doesn't retag cells. Ties resolve to the most
 * recently voted type (fresh evidence wins).
 */
class SurfaceContextTracker(private val windowSize: Int = 5) {

    private val votes = ArrayDeque<SurfaceType>()

    fun vote(surface: SurfaceType?) {
        if (surface == null) return // silence is not evidence of a change
        votes.addLast(surface)
        while (votes.size > windowSize) votes.removeFirst()
    }

    /** The current majority surface, or null before any vote landed. */
    fun current(): SurfaceType? {
        if (votes.isEmpty()) return null
        val counts = votes.groupingBy { it }.eachCount()
        val best = counts.maxOf { it.value }
        // Most recent among the tied-best — walk from the newest vote back.
        return votes.asReversed().first { counts[it] == best }
    }

    fun reset() = votes.clear()
}

/**
 * Process-wide surface context bridge (same shape as [CameraSceneVoteBus]):
 * the AR tick loop votes with each tick's whole-frame labels; consumers
 * (the coverage-fan listener, the summary) read [current]. Reset at env-scan
 * session start.
 */
object SurfaceContextBus {
    private val tracker = SurfaceContextTracker()
    private val _current = kotlinx.coroutines.flow.MutableStateFlow<SurfaceType?>(null)
    val current: kotlinx.coroutines.flow.StateFlow<SurfaceType?> = _current

    fun vote(labels: List<ClassificationObservation>) {
        tracker.vote(SurfaceContextClassifier.fromSceneLabels(labels))
        _current.value = tracker.current()
    }

    fun reset() {
        tracker.reset()
        _current.value = null
    }
}

/**
 * Visible truth for the thermal Vision shed: at thermal state >= serious the
 * tick loop drops ALL detection work, which used to be silent — a long
 * summer scan just stopped identifying with no cue (PR #219 follow-up #3).
 * The scan screen shows a "detection paused — phone cooling" chip while set.
 */
object DetectionThermalBus {
    private val _paused = kotlinx.coroutines.flow.MutableStateFlow(false)
    val paused: kotlinx.coroutines.flow.StateFlow<Boolean> = _paused

    fun setPaused(value: Boolean) {
        _paused.value = value
    }
}

/**
 * Visible truth for the LiDAR ground sensor (build 169): the heartbeat
 * already logs packet counts to diagnostics, but the field failure mode is
 * a user staring at obvious litter with no idea whether the SENSOR is dead
 * or the decision layer is dropping candidates. Two consecutive silent
 * heartbeat windows (~60s of zero packets while analysis is live) flips
 * [idle] and the scan screen shows an honest chip.
 */
object GroundSensorBus {
    private val _idle = kotlinx.coroutines.flow.MutableStateFlow(false)
    val idle: kotlinx.coroutines.flow.StateFlow<Boolean> = _idle

    fun reportWindow(packetsInWindow: Int) {
        if (packetsInWindow > 0) {
            _idle.value = false
            silentWindows = 0
        } else if (++silentWindows >= 2) {
            _idle.value = true
        }
    }

    fun reset() {
        silentWindows = 0
        _idle.value = false
    }

    private var silentWindows = 0
}
