package com.gcc.app.platform

/**
 * Promotes a live detection to a persistent world marker once it's been
 * held within [distanceThresholdMeters] for [streakTicks] consecutive ticks.
 * Shared by iOS's ArFrameFeedback and Android's equivalent pipeline (Task 6)
 * so both platforms promote at the identical threshold.
 */
class MarkerPromotionTracker(
    private val distanceThresholdMeters: Double = 2.0,
    private val streakTicks: Int = 2,
) {
    private var streak = 0

    /** Call once per tick with the current hit-test distance (or null if no hit). Returns true exactly on the tick that reaches the streak threshold. */
    fun onTick(hitDistanceMeters: Double?): Boolean {
        if (hitDistanceMeters != null && hitDistanceMeters <= distanceThresholdMeters) {
            streak += 1
        } else {
            streak = 0
            return false
        }
        return streak >= streakTicks
    }
}
