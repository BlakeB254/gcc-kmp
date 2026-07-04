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
)

/** Kind of persistent world-anchored AR marker. */
enum class WorldMarkerKind {
    /** Placed automatically when live detection confirms trash up close. */
    CONFIRMED_TRASH,

    /** Seeded from the digital twin's expected-dirty world observations. */
    EXPECTED_TRASH,
}
