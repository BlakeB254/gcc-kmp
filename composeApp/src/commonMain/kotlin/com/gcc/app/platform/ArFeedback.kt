package com.gcc.app.platform

/**
 * A live trash detection from the AR camera feed, positioned in normalized
 * view coordinates (0..1, origin top-left) so Compose can draw a reticle
 * over the camera without knowing the pixel size.
 */
data class LiveDetection(
    val cx: Float,
    val cy: Float,
    /** Normalized reticle radius (fraction of the view's smaller dimension). */
    val r: Float,
    val label: String,
    /** Estimated distance to the detection, when an AR hit-test resolved one. */
    val distanceMeters: Float? = null,
    /** Consecutive close-range ticks so far this streak (see [MarkerPromotionTracker]). */
    val streakProgress: Int = 0,
    /** Ticks required to promote -- for rendering streakProgress as a fraction. */
    val streakTicks: Int = 2,
    /** True for exactly the one tick a world marker was just placed. */
    val justPromoted: Boolean = false,
    /** 1-5 live severity estimate (see [LiveSeverityEstimator]) -- coarser than
     *  OnDeviceAnalyzer's post-capture analysis, but drives the reticle's color
     *  so a worker sees an urgency signal before ever tapping capture. */
    val severity: Int = LiveSeverityEstimator.DEFAULT_SEVERITY,
    /** Classifier confidence behind [label], when the caller has it on hand. */
    val confidence: Float? = null,
)

/**
 * Approximates a 1-5 severity for a live detection from its trash-type label
 * and reticle size, since the precise analysis ([com.gcc.app.platform.OnDeviceAnalyzer])
 * only runs on the still photo after capture -- a live scan needs *some*
 * urgency signal on the reticle well before that. Size nudges the type's
 * baseline by at most one tier: a large fragment of a low-severity item
 * isn't a mattress, but a tiny fragment of a high-severity item is less
 * urgent than a fresh one.
 */
object LiveSeverityEstimator {
    const val DEFAULT_SEVERITY = 2

    private val BASE_SEVERITY = mapOf(
        "Mattress" to 5,
        "Tire" to 4,
        "Trash" to 3,
        "Debris" to 3,
        "Litter" to 2,
        "Carton" to 2,
        "Bag" to 2,
        "Plastic" to 2,
        "Wrapper" to 1,
        "Bottle" to 1,
        "Can" to 1,
        "Cup" to 1,
    )

    private const val LARGE_RADIUS = 0.30f
    private const val SMALL_RADIUS = 0.10f

    fun estimate(label: String, normalizedRadius: Float): Int {
        val base = BASE_SEVERITY[label] ?: DEFAULT_SEVERITY
        val sizeAdjust = when {
            normalizedRadius >= LARGE_RADIUS -> 1
            normalizedRadius <= SMALL_RADIUS -> -1
            else -> 0
        }
        return (base + sizeAdjust).coerceIn(1, 5)
    }
}

/** Kind of persistent world-anchored AR marker. */
enum class WorldMarkerKind {
    /** Placed automatically when live detection confirms trash up close. */
    CONFIRMED_TRASH,

    /** Seeded from the digital twin's expected-dirty world observations. */
    EXPECTED_TRASH,
}
