package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteTapConfirmTest {

    private fun candidate(label: String?) = TapCandidateState(
        phase = TapPhase.AWAITING_CONFIRMATION,
        lat = 41.86, lng = -87.72, label = label,
    )

    @Test
    fun filingCanonicalizesTheLabelAndCarriesTypicalWeight() {
        val filing = routeTapFiling(candidate("bottle"))!!
        assertEquals(TrashItemCatalog.canonicalType("bottle"), filing.itemType)
        assertEquals(
            TrashItemCatalog.estimateWeightGrams(filing.itemType, null),
            filing.estimatedWeightGrams,
        )
        assertTrue(filing.estimatedWeightLbs > 0.0)
        assertEquals(1, filing.severity)
    }

    @Test
    fun nullLabelFilesAsUnknownLitter() {
        val filing = routeTapFiling(candidate(null))!!
        assertEquals(TrashItemCatalog.canonicalType(UNKNOWN_LITTER_LABEL), filing.itemType)
    }

    @Test
    fun poundsAreGramsOver453point59237RoundedToTwoDecimals() {
        val filing = routeTapFiling(candidate("bottle"))!!
        val expected = kotlin.math.round(filing.estimatedWeightGrams / 453.59237 * 100) / 100
        assertEquals(expected, filing.estimatedWeightLbs)
    }

    @Test
    fun missingCoordinatesYieldNull() {
        assertNull(routeTapFiling(TapCandidateState(phase = TapPhase.AWAITING_CONFIRMATION, label = "bottle")))
    }
}
