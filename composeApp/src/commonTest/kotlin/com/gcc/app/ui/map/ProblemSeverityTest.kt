package com.gcc.app.ui.map

import kotlin.test.Test
import kotlin.test.assertEquals

class ProblemSeverityTest {

    @Test
    fun weightBoundariesTierExactly() {
        assertEquals(ProblemSeverity.LIGHT, problemSeverityTier(1, 1.0))
        assertEquals(ProblemSeverity.LIGHT, problemSeverityTier(1, 1_999.0))
        assertEquals(ProblemSeverity.MODERATE, problemSeverityTier(1, 2_000.0))
        assertEquals(ProblemSeverity.MODERATE, problemSeverityTier(1, 14_999.0))
        assertEquals(ProblemSeverity.SEVERE, problemSeverityTier(1, 15_000.0))
    }

    @Test
    fun weightWinsOverCountWhenPresent() {
        // 20 tiny items, barely any mass -> still LIGHT.
        assertEquals(ProblemSeverity.LIGHT, problemSeverityTier(litterCount = 20, litterWeightGrams = 100.0))
        // One heavy item -> SEVERE regardless of the low count.
        assertEquals(ProblemSeverity.SEVERE, problemSeverityTier(litterCount = 1, litterWeightGrams = 40_000.0))
    }

    @Test
    fun unweighedCellsFallBackToCountRatherThanReadingAsLight() {
        // The regression this guards: weight is only estimated on a diagnosed
        // close-range capture, so a pile of un-weighed trash reports 0 grams.
        assertEquals(ProblemSeverity.LIGHT, problemSeverityTier(litterCount = 2, litterWeightGrams = 0.0))
        assertEquals(ProblemSeverity.MODERATE, problemSeverityTier(litterCount = 3, litterWeightGrams = 0.0))
        assertEquals(ProblemSeverity.MODERATE, problemSeverityTier(litterCount = 5, litterWeightGrams = 0.0))
        assertEquals(ProblemSeverity.SEVERE, problemSeverityTier(litterCount = 6, litterWeightGrams = 0.0))
        assertEquals(ProblemSeverity.SEVERE, problemSeverityTier(litterCount = 99, litterWeightGrams = 0.0))
    }

    @Test
    fun reportOnlyCellWithNoScanDataTiersLight() {
        // hasSpotNear() made it a problem; no scan has characterised it yet.
        assertEquals(ProblemSeverity.LIGHT, problemSeverityTier(litterCount = 0, litterWeightGrams = 0.0))
    }

    @Test
    fun negativeOrNonsenseWeightNeverEscalates() {
        assertEquals(ProblemSeverity.LIGHT, problemSeverityTier(litterCount = 1, litterWeightGrams = -5.0))
    }
}
