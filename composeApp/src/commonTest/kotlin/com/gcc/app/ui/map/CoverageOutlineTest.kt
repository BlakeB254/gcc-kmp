package com.gcc.app.ui.map

import kotlin.math.PI
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverageOutlineTest {

    private val anchorLat = 41.8600
    private val anchorLng = -87.7200
    private val cellSizeMeters = 3.0

    /** Same fixed-step grid convention CoverageTracker itself uses. */
    private fun cellCenter(row: Int, col: Int): Pair<Double, Double> {
        val latPerMeter = 1.0 / 111_320.0
        val lngPerMeter = 1.0 / (111_320.0 * cos(anchorLat * PI / 180.0))
        val lat = anchorLat + row * cellSizeMeters * latPerMeter
        val lng = anchorLng + col * cellSizeMeters * lngPerMeter
        return lat to lng
    }

    private fun isClosed(ring: List<Pair<Double, Double>>) =
        ring.size > 1 && ring.first() == ring.last()

    @Test
    fun emptyCellSetProducesNoRings() {
        assertEquals(emptyList(), traceCoverageOutlines(emptySet(), ::cellCenter, cellSizeMeters))
    }

    @Test
    fun adjacentFreshnessCellsMergeIntoOneRingNoInteriorEdge() {
        // Two edge-adjacent cells on a 10m lattice → a single merged ring (the
        // "paint, not graph paper" invariant the minimap freshness layers now
        // rely on — Plan B Task 5). One ring means no interior seam.
        val lngPerMeter = 1.0 / (111_320.0 * cos(anchorLat * PI / 180.0))
        val a = anchorLat to anchorLng
        val b = anchorLat to (anchorLng + 10.0 * lngPerMeter) // ~10m east = next cell
        val rings = quantizeAndTraceOutlines(
            listOf(a, b),
            cellSizeMeters = 10.0,
            anchorLat = anchorLat,
            anchorLng = anchorLng,
        )
        assertEquals(1, rings.size)
        assertTrue(isClosed(rings.first()))
    }

    @Test
    fun singleCellProducesOneClosedRing() {
        val rings = traceCoverageOutlines(setOf(0 to 0), ::cellCenter, cellSizeMeters)
        assertEquals(1, rings.size)
        assertTrue(isClosed(rings.first()))
        // 4 unit edges → 4 unique corners, each split into 2 by one Chaikin
        // pass, plus the closing repeat of the first point.
        assertEquals(4 * 2 + 1, rings.first().size)
    }

    @Test
    fun lShapeProducesOneRing() {
        val cells = setOf(0 to 0, 0 to 1, 1 to 0)
        val rings = traceCoverageOutlines(cells, ::cellCenter, cellSizeMeters)
        assertEquals(1, rings.size)
        assertTrue(isClosed(rings.first()))
    }

    @Test
    fun twoDisjointIslandsProduceTwoRings() {
        val cells = setOf(0 to 0, 10 to 10)
        val rings = traceCoverageOutlines(cells, ::cellCenter, cellSizeMeters)
        assertEquals(2, rings.size)
        rings.forEach { assertTrue(isClosed(it)) }
    }

    @Test
    fun everyRingIsClosed() {
        val cells = setOf(0 to 0, 0 to 1, 1 to 1, 1 to 0, 2 to 1, 5 to 5)
        val rings = traceCoverageOutlines(cells, ::cellCenter, cellSizeMeters)
        assertTrue(rings.isNotEmpty())
        rings.forEach { ring -> assertTrue(isClosed(ring), "ring not closed: $ring") }
    }

    @Test
    fun holeInASolidRegionProducesASecondRing() {
        // 3x3 block minus the center cell — the missing center is fully
        // surrounded, so it traces as its own (hole) ring in addition to
        // the outer boundary.
        val cells = (0..2).flatMap { r -> (0..2).map { c -> r to c } }.toSet() - (1 to 1)
        val rings = traceCoverageOutlines(cells, ::cellCenter, cellSizeMeters)
        assertEquals(2, rings.size)
        rings.forEach { assertTrue(isClosed(it)) }
    }

    @Test
    fun chaikinSmoothLeavesTinyRingsUnchanged() {
        val ring = listOf(0.0 to 0.0, 1.0 to 0.0, 0.0 to 0.0)
        assertEquals(ring, chaikinSmooth(ring))
    }

    @Test
    fun chaikinSmoothPreservesClosureAndDoublesPointCount() {
        val ring = listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0, 0.0 to 0.0)
        val smoothed = chaikinSmooth(ring)
        assertTrue(isClosed(smoothed))
        assertEquals((ring.size - 1) * 2 + 1, smoothed.size)
    }
}
