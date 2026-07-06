package com.gcc.app.platform

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Frontier picking for the environment scan's directional steering: choose
 * which uncredited cell the minimap ring and AR arrow should point at.
 *
 * Pure logic so it unit-tests without AR/GPS; SpotScreen owns the state
 * (current target) and feeds each recomputation back in.
 */
enum class GuidancePhase { COVER, DETAIL }

data class GuidancePick(val target: Pair<Double, Double>, val phase: GuidancePhase)

object EnvScanGuidance {

    /** Switch targets only when a challenger is at least this much closer —
     *  without hysteresis the arrow flaps between equidistant frontiers as
     *  GPS jitters. */
    private const val SWITCH_DISTANCE_RATIO = 0.7

    /** Corridor coverage% at which steering flips from cover-everything to
     *  detail-scan-the-grazed-areas (spec §4). */
    const val DETAIL_PHASE_COVERAGE_PERCENT = 80.0

    /** Treat a kept target as still-uncredited when a cell center matches
     *  within this distance (cells are [CoverageTracker.cellSizeMeters]). */
    private const val SAME_CELL_EPSILON_METERS = 1.0

    /**
     * Nearest-frontier pick with stickiness: keeps [current] while it remains
     * in [uncredited], unless the nearest candidate is significantly closer
     * (ratio [SWITCH_DISTANCE_RATIO]). Returns null when nothing is left to
     * scan.
     */
    fun pickFrontier(
        current: Pair<Double, Double>?,
        uncredited: List<Pair<Double, Double>>,
        userLat: Double,
        userLng: Double,
    ): Pair<Double, Double>? {
        if (uncredited.isEmpty()) return null

        var nearest: Pair<Double, Double>? = null
        var nearestDist = Double.MAX_VALUE
        var currentStillOpen: Pair<Double, Double>? = null
        for (center in uncredited) {
            val dist = approxDistanceMeters(userLat, userLng, center.first, center.second)
            if (dist < nearestDist) {
                nearestDist = dist
                nearest = center
            }
            if (current != null &&
                approxDistanceMeters(current.first, current.second, center.first, center.second) <=
                SAME_CELL_EPSILON_METERS
            ) {
                currentStillOpen = center
            }
        }

        if (currentStillOpen != null) {
            val currentDist =
                approxDistanceMeters(userLat, userLng, currentStillOpen.first, currentStillOpen.second)
            if (nearestDist >= currentDist * SWITCH_DISTANCE_RATIO) return currentStillOpen
        }
        return nearest
    }

    /**
     * Two-phase pick: while coverage is low and uncredited frontier remains,
     * steer like a coverage scan (phase COVER). Once the area is mostly
     * grazed — or nothing uncredited remains — steer at the nearest
     * grazed-but-not-close cluster like a 3D-scan app's "get closer" pass
     * (phase DETAIL). Same stickiness as [pickFrontier] in both phases.
     */
    fun pickTarget(
        current: Pair<Double, Double>?,
        uncredited: List<Pair<Double, Double>>,
        grazedNotClose: List<Pair<Double, Double>>,
        userLat: Double,
        userLng: Double,
        coveragePercent: Double,
    ): GuidancePick? {
        val coverDone = coveragePercent >= DETAIL_PHASE_COVERAGE_PERCENT || uncredited.isEmpty()
        if (!coverDone) {
            pickFrontier(current, uncredited, userLat, userLng)?.let {
                return GuidancePick(it, GuidancePhase.COVER)
            }
        }
        return pickFrontier(current, grazedNotClose, userLat, userLng)
            ?.let { GuidancePick(it, GuidancePhase.DETAIL) }
    }

    /** Equirectangular distance — plenty at scan-session scale (≤100m). */
    private fun approxDistanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val northMeters = (lat2 - lat1) * 111_320.0
        val eastMeters = (lng2 - lng1) * 111_320.0 * cos(lat1 * PI / 180.0)
        return sqrt(northMeters * northMeters + eastMeters * eastMeters)
    }
}
