package com.gcc.app.ui.map

import com.gcc.shared.model.MapCoverageCell
import com.gcc.shared.model.WorldObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CellHealthChoroplethTest {

    private fun cell(
        lat: Double = 41.86,
        lng: Double = -87.72,
        state: String = "fresh",
        quality: String = "close",
    ) = MapCoverageCell(
        locationCellId = "cell-$lat-$lng",
        lat = lat,
        lng = lng,
        state = state,
        lastScannedAt = "2026-07-01T00:00:00Z",
        totalDistanceMeters = 10.0,
        quality = quality,
    )

    private fun dirtyObservation(
        lat: Double = 41.86,
        lng: Double = -87.72,
        id: Int = 1,
        estimatedWeightGrams: Int? = null,
    ) = WorldObservation(
        id = id,
        lat = lat,
        lng = lng,
        state = "dirty",
        sourceType = "route_scan",
        estimatedWeightGrams = estimatedWeightGrams,
    )

    @Test
    fun emptyInputsProduceNoTiles() {
        assertEquals(emptyList(), aggregateCellHealth(emptyList(), emptyList(), tileMeters = 100.0))
    }

    @Test
    fun nonPositiveTileSizeProducesNoTiles() {
        val tiles = aggregateCellHealth(listOf(cell()), emptyList(), tileMeters = 0.0)
        assertEquals(emptyList(), tiles)
    }

    @Test
    fun singleCleanCellProducesAFullyGreenTile() {
        val tiles = aggregateCellHealth(listOf(cell(quality = "close", state = "fresh")), emptyList(), tileMeters = 100.0)
        assertEquals(1, tiles.size)
        assertEquals(1.0, tiles.single().health, 0.0001)
        assertEquals(HealthState.CLEAN, tiles.single().dominant)
    }

    @Test
    fun cellWithHeavyLitterProducesAFullyRedTile() {
        // Scanned close (would be a perfect clean tile on its own) but a
        // heavy litter observation lands in the same bin — litter must win.
        val tiles = aggregateCellHealth(
            listOf(cell(quality = "close", state = "fresh")),
            listOf(dirtyObservation(estimatedWeightGrams = 6000)),
            tileMeters = 100.0,
        )
        assertEquals(1, tiles.size)
        val tile = tiles.single()
        assertEquals(0.0, tile.health, 0.0001)
        assertEquals(HealthState.DIRTY, tile.dominant)
    }

    @Test
    fun mixedCoverageAndModerateLitterProduceAWeightedMiddleTile() {
        // One close + one stale cell -> coverageComponent = (1.0 + 0.5) / 2 = 0.75.
        // 1000g litter / 4000g saturation -> litterPenalty = 0.25.
        // health = 0.75 * (1 - 0.25) = 0.5625 -- strictly between the fully
        // clean (1.0) and fully dirty (0.0) extremes above; neither litter
        // nor coverage alone determines the outcome.
        val tiles = aggregateCellHealth(
            listOf(
                cell(lat = 41.860, lng = -87.720, state = "fresh", quality = "close"),
                cell(lat = 41.8601, lng = -87.7201, state = "stale", quality = "close"),
            ),
            listOf(dirtyObservation(lat = 41.8600, lng = -87.7200, estimatedWeightGrams = 1000)),
            tileMeters = 200.0,
        )
        assertEquals(1, tiles.size)
        val tile = tiles.single()
        assertEquals(0.5625, tile.health, 0.0001)
        assertTrue(tile.health > 0.0 && tile.health < 1.0)
        assertEquals(HealthState.STALE, tile.dominant)
    }

    @Test
    fun binsWithNoDataAtAllAreExcludedNotFabricated() {
        val tiles = aggregateCellHealth(listOf(cell(lat = 41.86, lng = -87.72)), emptyList(), tileMeters = 100.0)
        // Only the one occupied bin comes back -- no neighboring "empty" tiles.
        assertEquals(1, tiles.size)
    }

    @Test
    fun cleanStateObservationsDoNotCreateOrAffectTiles() {
        val cleanObs = WorldObservation(
            id = 2,
            lat = 41.90,
            lng = -87.80,
            state = "clean",
            sourceType = "route_scan",
        )
        val tiles = aggregateCellHealth(emptyList(), listOf(cleanObs), tileMeters = 100.0)
        assertEquals(emptyList(), tiles)
    }

    @Test
    fun unscannedBinWithLightLitterIsNeutralNotRed() {
        // No coverage cell at all, litter well under saturation -> neutral
        // coverage baseline (0.5) times a small litter penalty, not zero.
        val tiles = aggregateCellHealth(
            emptyList(),
            listOf(dirtyObservation(estimatedWeightGrams = 200)),
            tileMeters = 100.0,
        )
        assertEquals(1, tiles.size)
        val tile = tiles.single()
        assertEquals(HealthState.UNSCANNED, tile.dominant)
        assertTrue(tile.health in 0.4..0.5)
    }

    @Test
    fun allStaleCoverageWithNoLitterIsAmberNotGreenOrRed() {
        val tiles = aggregateCellHealth(
            listOf(cell(state = "stale", quality = "close")),
            emptyList(),
            tileMeters = 100.0,
        )
        assertEquals(1, tiles.size)
        val tile = tiles.single()
        assertEquals(HealthState.STALE, tile.dominant)
        assertEquals(0.5, tile.health, 0.0001)
    }

    @Test
    fun twoCellsInTheSameBinMergeIntoOneTile() {
        val tiles = aggregateCellHealth(
            listOf(
                cell(lat = 41.8600, lng = -87.7200),
                cell(lat = 41.8601, lng = -87.7201),
            ),
            emptyList(),
            tileMeters = 400.0,
        )
        assertEquals(1, tiles.size)
    }

    @Test
    fun choroplethTileMetersForZoomIsMonotonicallyNonIncreasing() {
        val zooms = listOf(2.0, 5.0, 7.0, 9.0, 10.0, 12.0, 13.0, 14.0, 14.2)
        val sizes = zooms.map { choroplethTileMetersForZoom(it) }
        for (i in 1 until sizes.size) {
            assertTrue(sizes[i] <= sizes[i - 1], "expected non-increasing tile size as zoom grows: $sizes")
        }
        assertTrue(sizes.all { it > 0.0 })
    }
}
