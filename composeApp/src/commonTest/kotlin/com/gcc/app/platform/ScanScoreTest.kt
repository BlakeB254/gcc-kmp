package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        // composite = effort*0.45 + closeOfCovered*0.35 + steadiness*0.20.
        // With coverage == close the quality ratio is 1.0 (0.35 fixed), so
        // composite = coverage*0.45 + 35 + steadiness*20.
        assertEquals('A', ScanScore.compute(100.0, 100.0, 1.0).grade) // 100
        assertEquals('B', ScanScore.compute(80.0, 80.0, 0.45).grade) // 36+35+9 = 80.0 exactly
        assertEquals('C', ScanScore.compute(40.0, 40.0, 0.6).grade) // 18+35+12 = 65.0 exactly
        assertEquals('D', ScanScore.compute(20.0, 20.0, 0.3).grade) // 9+35+6 = 50.0 exactly
        assertEquals('F', ScanScore.compute(20.0, 0.0, 0.3).grade) // 9+0+6 = 15.0
    }

    @Test
    fun closeQualityIsScoredAgainstCoveredGroundNotTheWholeCorridor() {
        // Recalibration (TestFlight 145): covering 30% of the synthetic disc
        // with EVERY covered cell at close tier used to be double-penalized
        // (close 30% of the disc too). The quality ratio is now 30/30 = 1.0.
        val thorough = ScanScore.compute(coveragePercent = 30.0, closePercent = 30.0, steadinessFraction = 1.0)
        val sweepOnly = ScanScore.compute(coveragePercent = 30.0, closePercent = 0.0, steadinessFraction = 1.0)
        assertTrue(thorough.grade < sweepOnly.grade) // 'C' < 'F' in char order A<B<C…
        assertEquals('C', thorough.grade) // 13.5+35+20 = 68.5
    }

    @Test
    fun absoluteAreaReplacesCorridorFractionWhenProvided() {
        // Blake's 145 session shape: 189m² of honest sweep, ~27% of the
        // synthetic disc, almost no close tier, decent tracking. Was an F;
        // with effort graded on real ground covered it's a D ("Rough scan")
        // with the walk-closer advice — still honest about the missing close
        // tier, no longer failing the walk itself.
        val result = ScanScore.compute(
            coveragePercent = 27.0,
            closePercent = 2.0,
            steadinessFraction = 0.9,
            areaCoveredSquareMeters = 189.0,
        )
        assertEquals('D', result.grade) // 0.945*45 + 0.074*35 + 0.9*20 ≈ 63.1
        assertEquals("Walk closer — most of your area was sweep-only", result.advice)
    }

    @Test
    fun tinyAreaStillGradesLow() {
        // 18m² (two cells) of sweep-only: effort 0.09 — the floor still exists.
        val result = ScanScore.compute(
            coveragePercent = 3.0,
            closePercent = 0.0,
            steadinessFraction = 1.0,
            areaCoveredSquareMeters = 18.0,
        )
        assertEquals('F', result.grade) // 4.05 + 0 + 20 = 24.1
    }

    @Test
    fun lowEffortDrivesCoverageAdvice() {
        val result = ScanScore.compute(
            coveragePercent = 20.0,
            closePercent = 18.0, // quality ratio 0.9 — not the weak component
            steadinessFraction = 0.95,
            areaCoveredSquareMeters = 40.0, // effort 0.2 — the weak component
        )
        assertEquals("Cover more ground next time", result.advice)
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
