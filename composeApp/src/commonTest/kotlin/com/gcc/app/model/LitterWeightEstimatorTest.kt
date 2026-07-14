package com.gcc.app.model

import com.gcc.shared.model.LitterWeightEstimator
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LitterWeightEstimatorTest {

    /** volumeLiters = w*h*d*1000, grams = (volumeLiters * FILL * density).roundToInt().coerceAtLeast(1) — mirrors [LitterWeightEstimator.estimateGrams]'s doc'd formula, computed here from the public constants so the assertion can't silently mirror a wrong constant. */
    private fun expectedGrams(widthM: Double, heightM: Double, depthM: Double, densityGPerL: Int): Int {
        val volumeLiters = widthM * heightM * depthM * 1000.0
        return (volumeLiters * LitterWeightEstimator.VOLUME_FILL_FRACTION * densityGPerL)
            .roundToInt()
            .coerceAtLeast(1)
    }

    @Test
    fun bottleUsesContainerDensity() {
        val expected = expectedGrams(0.1, 0.25, 0.1, LitterWeightEstimator.DENSITY_CONTAINER_G_PER_L)
        assertEquals(
            expected,
            LitterWeightEstimator.estimateGrams("bottle", 0.1f, 0.25f, 0.1f),
        )
    }

    @Test
    fun glassBottleUsesGlassDensityNotContainerDensity() {
        // "glass_bottle" carries both a "bottle" and a "glass" token — glass
        // must win, or every glass bottle would be under-weighted at the
        // hollow-container density instead of solid glass.
        val expected = expectedGrams(0.1, 0.25, 0.1, LitterWeightEstimator.DENSITY_GLASS_G_PER_L)
        assertEquals(
            expected,
            LitterWeightEstimator.estimateGrams("glass_bottle", 0.1f, 0.25f, 0.1f),
        )
    }

    @Test
    fun filmBagUsesFilmDensity() {
        val expected = expectedGrams(0.3, 0.2, 0.05, LitterWeightEstimator.DENSITY_FILM_G_PER_L)
        assertEquals(
            expected,
            LitterWeightEstimator.estimateGrams("plastic_bag", 0.3f, 0.2f, 0.05f),
        )
    }

    @Test
    fun canUsesCanDensity() {
        val expected = expectedGrams(0.12, 0.15, 0.12, LitterWeightEstimator.DENSITY_CAN_G_PER_L)
        assertEquals(
            expected,
            LitterWeightEstimator.estimateGrams("can", 0.12f, 0.15f, 0.12f),
        )
    }

    @Test
    fun cartonUsesPaperDensity() {
        val expected = expectedGrams(0.2, 0.2, 0.2, LitterWeightEstimator.DENSITY_PAPER_G_PER_L)
        assertEquals(
            expected,
            LitterWeightEstimator.estimateGrams("carton", 0.2f, 0.2f, 0.2f),
        )
    }

    @Test
    fun unknownLabelFallsBackToDefaultDensity() {
        val expected = expectedGrams(0.15, 0.15, 0.15, LitterWeightEstimator.DENSITY_DEFAULT_G_PER_L)
        assertEquals(
            expected,
            LitterWeightEstimator.estimateGrams("mystery_item", 0.15f, 0.15f, 0.15f),
        )
    }

    @Test
    fun pipelineRealisticGlassInputReachesGlassDensity() {
        // End-to-end shape of the completion path: the classifier names a
        // raw "glass" token (GroundExclusionRules.trashTypeFrom), SpotScreen
        // canonicalizes it (TrashItemCatalog.canonicalType -> "glass_bottle"),
        // and THAT string must hit the solid-glass 300 g/L bucket — not the
        // hollow-container one its "bottle" token would otherwise select.
        val canonical = com.gcc.app.platform.TrashItemCatalog.canonicalType("glass")
        val expected = expectedGrams(0.1, 0.2, 0.1, LitterWeightEstimator.DENSITY_GLASS_G_PER_L)
        assertEquals(expected, LitterWeightEstimator.estimateGrams(canonical, 0.1f, 0.2f, 0.1f))
    }

    @Test
    fun degenerateZeroDimensionsClampToOneGram() {
        assertEquals(1, LitterWeightEstimator.estimateGrams("bottle", 0f, 0f, 0f))
    }

    @Test
    fun estimateIsNeverBelowOneGramForTinyItems() {
        assertTrue(LitterWeightEstimator.estimateGrams("cigarette", 0.001f, 0.001f, 0.001f) >= 1)
    }
}
