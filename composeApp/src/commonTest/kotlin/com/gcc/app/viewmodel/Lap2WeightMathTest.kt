package com.gcc.app.viewmodel

import com.gcc.app.platform.TrashItemCatalog
import com.gcc.shared.model.HotspotStatus
import com.gcc.shared.model.ScanHotspot
import com.gcc.shared.model.SpotTag
import com.gcc.shared.model.WorldObservation
import kotlinx.datetime.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Lap2WeightMathTest {

    private fun hotspot(
        id: Int,
        weightLbs: Double? = null,
        status: HotspotStatus = HotspotStatus.DETECTED,
        tags: List<SpotTag> = emptyList(),
    ) = ScanHotspot(
        id = id,
        scanId = 1,
        lat = 41.86,
        lng = -87.71,
        trashTypes = tags,
        estimatedWeightLbs = weightLbs,
        beforePhotoUrl = "before_$id.jpg",
        detectedAt = Instant.parse("2026-07-06T12:00:00Z"),
        status = status,
    )

    private fun observation(
        id: Int,
        itemType: String? = null,
        widthMeters: Double? = null,
    ) = WorldObservation(
        id = id,
        lat = 41.86,
        lng = -87.71,
        itemType = itemType,
        state = "dirty",
        sourceType = "route_scan",
        widthMeters = widthMeters,
    )

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-6) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }

    @Test
    fun emptyLap1MeansZeroProgress() {
        val progress = Lap2WeightMath.compute(emptyList(), emptyList(), emptySet())
        assertEquals(0.0, progress.percent)
        assertEquals(0.0, progress.totalLbs)
        assertEquals(0.0, progress.cleanedLbs)
        assertEquals(0, progress.totalCount)
        assertEquals(0, progress.cleanedCount)
    }

    @Test
    fun percentIsCleanedWeightOverIdentifiedWeight() {
        val hotspots = listOf(
            hotspot(1, weightLbs = 30.0, status = HotspotStatus.CLEANED),
            hotspot(2, weightLbs = 70.0, status = HotspotStatus.DETECTED),
        )
        val progress = Lap2WeightMath.compute(hotspots, emptyList(), emptySet())
        assertClose(100.0, progress.totalLbs)
        assertClose(30.0, progress.cleanedLbs)
        assertClose(30.0, progress.percent)
        assertEquals(2, progress.totalCount)
        assertEquals(1, progress.cleanedCount)
    }

    @Test
    fun missingHotspotWeightFallsBackToCatalogTypeDefault() {
        // TIRES → canonical "tire" → 9000 g typical.
        val expectedLbs = 9000.0 / Lap2WeightMath.GRAMS_PER_POUND
        assertClose(expectedLbs, Lap2WeightMath.hotspotWeightLbs(hotspot(1, tags = listOf(SpotTag.TIRES))))

        // No tags at all → generic litter fallback, still non-zero.
        val fallbackLbs = Lap2WeightMath.hotspotWeightLbs(hotspot(2))
        assertTrue(fallbackLbs > 0.0)
        assertClose(
            TrashItemCatalog.estimateWeightGrams(TrashItemCatalog.FALLBACK_TYPE, null) / Lap2WeightMath.GRAMS_PER_POUND,
            fallbackLbs,
        )
    }

    @Test
    fun zeroOrInvalidExplicitWeightUsesFallback() {
        val zeroWeight = Lap2WeightMath.hotspotWeightLbs(hotspot(1, weightLbs = 0.0))
        assertTrue(zeroWeight > 0.0)
    }

    @Test
    fun observationWeightUsesCatalogAndWidthScaling() {
        val typical = Lap2WeightMath.observationWeightLbs(observation(1, itemType = "plastic_bottle"))
        assertClose(
            TrashItemCatalog.estimateWeightGrams("plastic_bottle", null) / Lap2WeightMath.GRAMS_PER_POUND,
            typical,
        )

        // Wider than typical → heavier than typical.
        val scaled = Lap2WeightMath.observationWeightLbs(
            observation(2, itemType = "plastic_bottle", widthMeters = 0.46),
        )
        assertTrue(scaled > typical)

        // Null itemType → generic litter fallback.
        assertClose(
            TrashItemCatalog.estimateWeightGrams(TrashItemCatalog.FALLBACK_TYPE, null) / Lap2WeightMath.GRAMS_PER_POUND,
            Lap2WeightMath.observationWeightLbs(observation(3)),
        )
    }

    @Test
    fun rectifiedObservationsCountAsCleaned() {
        val hotspots = listOf(hotspot(1, weightLbs = 10.0, status = HotspotStatus.DETECTED))
        val observations = listOf(
            observation(100, itemType = "tire"),      // rectified ("Already clean")
            observation(101, itemType = "mattress"),  // still dirty
        )
        val tireLbs = TrashItemCatalog.estimateWeightGrams("tire", null) / Lap2WeightMath.GRAMS_PER_POUND
        val mattressLbs = TrashItemCatalog.estimateWeightGrams("mattress", null) / Lap2WeightMath.GRAMS_PER_POUND

        val progress = Lap2WeightMath.compute(hotspots, observations, setOf(100))
        assertClose(10.0 + tireLbs + mattressLbs, progress.totalLbs)
        assertClose(tireLbs, progress.cleanedLbs)
        assertEquals(3, progress.totalCount)
        assertEquals(1, progress.cleanedCount)
        assertClose(tireLbs / (10.0 + tireLbs + mattressLbs) * 100.0, progress.percent)
    }

    @Test
    fun skippedHotspotsStayInTotalButNeverCountCleaned() {
        val hotspots = listOf(
            hotspot(1, weightLbs = 40.0, status = HotspotStatus.CLEANED),
            hotspot(2, weightLbs = 60.0, status = HotspotStatus.SKIPPED),
        )
        val progress = Lap2WeightMath.compute(hotspots, emptyList(), emptySet())
        assertClose(100.0, progress.totalLbs)
        assertClose(40.0, progress.cleanedLbs)
        assertClose(40.0, progress.percent)
    }

    @Test
    fun percentIsClampedTo100() {
        // Everything cleaned → exactly 100, never above.
        val hotspots = listOf(
            hotspot(1, weightLbs = 5.0, status = HotspotStatus.CLEANED),
            hotspot(2, weightLbs = 15.0, status = HotspotStatus.CLEANED),
        )
        val progress = Lap2WeightMath.compute(hotspots, emptyList(), emptySet())
        assertClose(100.0, progress.percent)

        // Direct model clamp: cleaned > total (defensive) still reads 100.
        assertClose(100.0, Lap2WeightProgress(cleanedLbs = 12.0, totalLbs = 10.0).percent)
    }
}
