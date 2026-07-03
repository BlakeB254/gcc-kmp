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
}
