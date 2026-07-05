package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionPointsEstimatorTest {

    private val cell = 3 to 4

    @Test
    fun newAreaCellEarnsTenPerMeter() {
        val e = SessionPointsEstimator()
        assertEquals(30, e.onSegment(cell, priorState = null, meters = 3.0))
        assertEquals(30, e.total)
    }

    @Test
    fun staleCellEarnsFivePerMeter() {
        val e = SessionPointsEstimator()
        assertEquals(15, e.onSegment(cell, priorState = "stale", meters = 3.0))
    }

    @Test
    fun freshCellEarnsNothing() {
        val e = SessionPointsEstimator()
        assertEquals(0, e.onSegment(cell, priorState = "fresh", meters = 3.0))
    }

    @Test
    fun eachCellCreditsOnlyOncePerSession() {
        val e = SessionPointsEstimator()
        assertEquals(30, e.onSegment(cell, null, 3.0))
        assertEquals(0, e.onSegment(cell, null, 3.0))
        assertEquals(30, e.total)
    }

    @Test
    fun gpsJumpSegmentsAreCapped() {
        val e = SessionPointsEstimator()
        assertEquals(500, e.onSegment(cell, null, 400.0)) // capped at 50m × 10
    }

    @Test
    fun nullCellOrNonPositiveMetersEarnNothing() {
        val e = SessionPointsEstimator()
        assertEquals(0, e.onSegment(null, null, 3.0))
        assertEquals(0, e.onSegment(cell, null, 0.0))
    }

    /**
     * gridCell10m keys are canonical in the world model (server
     * world_observations.locationCellId + R2 raw/{cellId}/ paths), so the
     * client must reproduce site/src/lib/world-observations.ts `gridCell`
     * EXACTLY: floor(lat / (10/111_000)) : floor(lng / (10/82_300)).
     */
    @Test
    fun gridCell10mMatchesServerFormula() {
        // North Lawndale service-area center.
        val lat = 41.8603
        val lng = -87.7228
        val expectedLat = kotlin.math.floor(lat / (10.0 / 111_000.0)).toInt()
        val expectedLng = kotlin.math.floor(lng / (10.0 / 82_300.0)).toInt()
        assertEquals(expectedLat to expectedLng, SessionPointsEstimator.gridCell10m(lat, lng))
        // Frozen values so a constant drift on either side fails loudly.
        assertEquals(464649 to -721959, SessionPointsEstimator.gridCell10m(lat, lng))
    }
}
