package com.gcc.app.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class ScanSessionStatsChipTest {

    @Test
    fun zeroItemsShowsCountOnly() {
        assertEquals("0 items", scanSessionStatsLabel(itemCount = 0, totalWeightGrams = 0))
    }

    @Test
    fun singularItemWithoutWeightEstimate() {
        assertEquals("1 item", scanSessionStatsLabel(itemCount = 1, totalWeightGrams = 0))
    }

    @Test
    fun gramsBelowOneKilogram() {
        assertEquals("3 items · ≈740 g", scanSessionStatsLabel(itemCount = 3, totalWeightGrams = 740))
    }

    @Test
    fun kilogramsWithTenthRounding() {
        assertEquals("5 items · ≈1.2 kg", scanSessionStatsLabel(itemCount = 5, totalWeightGrams = 1234))
    }

    @Test
    fun exactKilogramBoundary() {
        assertEquals("2 items · ≈1.0 kg", scanSessionStatsLabel(itemCount = 2, totalWeightGrams = 1000))
    }
}
