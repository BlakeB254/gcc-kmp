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

    // ── closeCellGaps / traceSessionSilhouette ───────────────────────
    // The AR raycast fan samples 15 discrete rays; past ~12m adjacent rays
    // land further apart than one 3m cell, so fan-credited cells arrive as
    // sparse single-cell columns ("comb"). Traced raw, every column is its
    // own ring — the live minimap drew each as a separately-outlined capsule
    // (field report: "streaks that never connect"). The silhouette tracer
    // closes those sampling seams before tracing.

    private fun combColumns(cols: List<Int>, rows: IntRange): List<Pair<Double, Double>> =
        cols.flatMap { c -> rows.map { r -> cellCenter(r, c) } }

    @Test
    fun rawTraceOfSparseFanCombProducesOneRingPerColumn() {
        // Documents the pre-fix behavior the silhouette tracer exists to avoid.
        val rings = quantizeAndTraceOutlines(
            combColumns(listOf(0, 2, 4), 0..9), cellSizeMeters, anchorLat, anchorLng,
        )
        assertEquals(3, rings.size)
    }

    @Test
    fun silhouetteBridgesOneCellSamplingGaps() {
        val rings = traceSessionSilhouette(
            combColumns(listOf(0, 2, 4), 0..9), cellSizeMeters, anchorLat, anchorLng,
        )
        assertEquals(1, rings.size)
        assertTrue(isClosed(rings.first()))
    }

    @Test
    fun silhouetteBridgesTwoCellSamplingGaps() {
        val rings = traceSessionSilhouette(
            combColumns(listOf(0, 3), 0..9), cellSizeMeters, anchorLat, anchorLng,
        )
        assertEquals(1, rings.size)
    }

    @Test
    fun silhouetteKeepsGenuinelySeparateAreasApart() {
        // A 3+-cell gap is real unscanned ground, not a sampling seam.
        val rings = traceSessionSilhouette(
            combColumns(listOf(0, 8), 0..9), cellSizeMeters, anchorLat, anchorLng,
        )
        assertEquals(2, rings.size)
    }

    @Test
    fun closeCellGapsIsASupersetOfItsInput() {
        val cells = setOf(0 to 0, 0 to 2, 5 to 5)
        val closed = closeCellGaps(cells)
        assertTrue(closed.containsAll(cells))
    }

    @Test
    fun closeCellGapsLeavesAnIsolatedCellAlone() {
        assertEquals(setOf(4 to 4), closeCellGaps(setOf(4 to 4)))
    }

    @Test
    fun closeCellGapsOnEmptySetIsEmpty() {
        assertEquals(emptySet(), closeCellGaps(emptySet()))
    }

    @Test
    fun silhouetteOnEmptyInputIsEmpty() {
        assertEquals(emptyList(), traceSessionSilhouette(emptyList(), cellSizeMeters, anchorLat, anchorLng))
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
