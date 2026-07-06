package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Frontier picking for env-scan directional steering: nearest uncredited
 * cell wins, with stickiness so GPS jitter doesn't flap the arrow between
 * near-equidistant frontiers.
 */
class EnvScanGuidanceTest {

    // ~41.88N (North Lawndale). 1e-5 deg lat ≈ 1.11m.
    private val userLat = 41.8800
    private val userLng = -87.7100

    private fun cellAt(northMeters: Double, eastMeters: Double): Pair<Double, Double> {
        val lat = userLat + northMeters / 111_320.0
        val lng = userLng + eastMeters / (111_320.0 * kotlin.math.cos(userLat * kotlin.math.PI / 180.0))
        return lat to lng
    }

    @Test
    fun emptyFrontierReturnsNull() {
        assertNull(EnvScanGuidance.pickFrontier(null, emptyList(), userLat, userLng))
    }

    @Test
    fun picksNearestCellWhenNoCurrentTarget() {
        val near = cellAt(northMeters = 5.0, eastMeters = 0.0)
        val far = cellAt(northMeters = 20.0, eastMeters = 10.0)
        val picked = EnvScanGuidance.pickFrontier(null, listOf(far, near), userLat, userLng)
        assertEquals(near, picked)
    }

    @Test
    fun keepsCurrentTargetAgainstMarginallyCloserChallenger() {
        val current = cellAt(northMeters = 10.0, eastMeters = 0.0)
        val challenger = cellAt(northMeters = 9.0, eastMeters = 0.0) // 10% closer only
        val picked = EnvScanGuidance.pickFrontier(current, listOf(challenger, current), userLat, userLng)
        assertEquals(current, picked)
    }

    @Test
    fun switchesWhenChallengerIsSignificantlyCloser() {
        val current = cellAt(northMeters = 20.0, eastMeters = 0.0)
        val challenger = cellAt(northMeters = 5.0, eastMeters = 0.0) // 4x closer
        val picked = EnvScanGuidance.pickFrontier(current, listOf(challenger, current), userLat, userLng)
        assertEquals(challenger, picked)
    }

    @Test
    fun rePicksWhenCurrentTargetGotCredited() {
        val credited = cellAt(northMeters = 3.0, eastMeters = 0.0)
        val remaining = cellAt(northMeters = 12.0, eastMeters = 6.0)
        val picked = EnvScanGuidance.pickFrontier(credited, listOf(remaining), userLat, userLng)
        assertNotNull(picked)
        assertEquals(remaining, picked)
    }
}
