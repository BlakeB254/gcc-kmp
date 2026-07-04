package com.gcc.app.platform

import com.gcc.shared.model.Coordinates
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * initFromBlock: the required scan area is the outer boundary's interior
 * minus every inner-boundary island — NOT line-proximity like initFromRoute.
 */
class CoverageTrackerBlockCorridorTest {

    // ~120m x ~90m block near Garfield Park.
    private val outer = listOf(
        Coordinates(lat = 41.8800, lng = -87.7200),
        Coordinates(lat = 41.8808, lng = -87.7200),
        Coordinates(lat = 41.8808, lng = -87.7186),
        Coordinates(lat = 41.8800, lng = -87.7186),
    )

    // Island covering the block's middle (built stretch / private property).
    private val island = listOf(
        Coordinates(lat = 41.8802, lng = -87.7196),
        Coordinates(lat = 41.8806, lng = -87.7196),
        Coordinates(lat = 41.8806, lng = -87.7190),
        Coordinates(lat = 41.8802, lng = -87.7190),
    )

    @Test
    fun corridorExcludesInnerIslandCells() {
        val tracker = CoverageTracker()
        tracker.initFromBlock(outer, listOf(island))

        val centers = tracker.getCorridorCenters()
        assertTrue(centers.isNotEmpty(), "block corridor should have cells")

        // No required cell sits inside the island.
        val insideIsland = centers.count { (lat, lng) ->
            lat in 41.8802..41.8806 && lng in -87.7196..-87.7190
        }
        assertTrue(insideIsland == 0, "found $insideIsland required cells inside the exclusion island")

        // Cells outside the outer boundary are not required either.
        val outsideOuter = centers.count { (lat, lng) ->
            lat !in 41.8800..41.8808 || lng !in -87.7200..-87.7186
        }
        assertTrue(outsideOuter == 0, "found $outsideOuter required cells outside the block")
    }

    @Test
    fun walkingTheCorridorRaisesCoveragePercentButIslandWalkDoesNot() {
        val tracker = CoverageTracker()
        tracker.initFromBlock(outer, listOf(island))

        // A position at the island's center credits no corridor cells.
        tracker.updatePosition(41.8804, -87.7193)
        val islandOnly = tracker.coveragePercent.value

        // A position on the corridor (west edge, outside the island) credits some.
        tracker.updatePosition(41.8804, -87.7199)
        assertTrue(
            tracker.coveragePercent.value > islandOnly,
            "corridor walk should add coverage (island-only=$islandOnly, after=${tracker.coveragePercent.value})",
        )
    }

    @Test
    fun degenerateOuterRingFallsBackToRouteCorridor() {
        val tracker = CoverageTracker()
        tracker.initFromBlock(
            outerRing = listOf(Coordinates(lat = 41.88, lng = -87.72)), // < 3 vertices
            innerRings = emptyList(),
            fallbackWaypoints = outer,
        )
        assertTrue(tracker.getCorridorCenters().isNotEmpty(), "fallback corridor should exist")
    }
}
