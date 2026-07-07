package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScanScoreTest {

    @Test
    fun perfectSessionGradesAWithNoAdvice() {
        val result = ScanScore.compute(coveragePercent = 100.0, closePercent = 100.0, steadinessFraction = 1.0)
        assertEquals('A', result.grade)
        assertEquals("Excellent scan", result.headline)
        assertNull(result.advice)
    }

    @Test
    fun emptySessionGradesFAndAdvisesClosestFirstOnTie() {
        // All three components tie at 0 — close (listed first) wins the tie.
        val result = ScanScore.compute(coveragePercent = 0.0, closePercent = 0.0, steadinessFraction = 0.0)
        assertEquals('F', result.grade)
        assertEquals("Walk closer — most of your area was sweep-only", result.advice)
    }

    @Test
    fun gradeBandBoundaries() {
        // composite = coverage*0.45 + close*0.35 + steadiness*0.20
        assertEquals('A', ScanScore.compute(100.0, 100.0, 1.0).grade) // 100
        assertEquals('B', ScanScore.compute(80.0, 80.0, 0.8).grade) // 80.0 exactly
        assertEquals('C', ScanScore.compute(65.0, 65.0, 0.65).grade) // 65.0 exactly
        assertEquals('D', ScanScore.compute(50.0, 50.0, 0.5).grade) // 50.0 exactly
        assertEquals('F', ScanScore.compute(49.0, 49.0, 0.49).grade) // 49.0 — just under D
    }

    @Test
    fun lowCoverageDrivesCoverageAdvice() {
        val result = ScanScore.compute(coveragePercent = 20.0, closePercent = 90.0, steadinessFraction = 0.95)
        assertEquals("Cover more of the area next time", result.advice)
    }

    @Test
    fun lowClosePercentDrivesWalkCloserAdvice() {
        val result = ScanScore.compute(coveragePercent = 95.0, closePercent = 10.0, steadinessFraction = 0.95)
        assertEquals("Walk closer — most of your area was sweep-only", result.advice)
    }

    @Test
    fun lowSteadinessDrivesSteadierAdvice() {
        val result = ScanScore.compute(coveragePercent = 95.0, closePercent = 90.0, steadinessFraction = 0.1)
        assertEquals("Hold the phone steadier while scanning", result.advice)
    }

    @Test
    fun inputsAreCoercedIntoRange() {
        // Defensive: out-of-range inputs (e.g. a caller passing a raw ratio
        // instead of a percent) never throw and never invert the grade.
        val result = ScanScore.compute(coveragePercent = 150.0, closePercent = -10.0, steadinessFraction = 2.0)
        // coverage clamped to 100 (*0.45=45), close clamped to 0 (*0.35=0),
        // steadiness clamped to 1 (*0.20=20) → composite 65.0 → C.
        assertEquals('C', result.grade)
    }
}
