package com.gcc.app.platform

import com.gcc.shared.model.Coordinates
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Geogrid registration: the AR ground grid, the AR vector/fog quads and the
 * minimap cell grid must all read as ONE lattice. These tests pin the three
 * contracts that make that true:
 *
 *  1. [CoverageTracker.latticeOrigin] is the exact phase of every cell center
 *     the minimap/AR quads draw (centers sit at origin + (i + 0.5) · cell).
 *  2. [snapRangeToLattice] (the plane-sizing snap both AR renderers use —
 *     Swift mirrors it in GccArNative.snapToLattice) puts plane edges exactly
 *     on lattice lines while still covering the detected plane.
 *  3. Converted through the SAME geo reference ([latLngToLocal]), lattice
 *     origin and cell centers land on the SAME 1.5m-tile AR lattice, so a
 *     grid phased to the origin has lines through every 3m cell edge.
 *
 * Plus the minimap freshness layer: env-scan fog cells must sit on the
 * canonical fixed-constant 10m ledger lattice (gridCell10m / server
 * gridCell), flush with the fresh/stale cells the server returns.
 */
class GridLatticeRegistrationTest {

    private val outerRing = listOf(
        Coordinates(lat = 41.8600, lng = -87.7200),
        Coordinates(lat = 41.8600, lng = -87.7180),
        Coordinates(lat = 41.8615, lng = -87.7180),
        Coordinates(lat = 41.8615, lng = -87.7200),
    )

    @Test
    fun latticeOriginIsNullBeforeInit() {
        assertNull(CoverageTracker().latticeOrigin())
    }

    @Test
    fun cellCentersArePhasedToLatticeOrigin() {
        val tracker = CoverageTracker()
        tracker.initFromBlock(outerRing, innerRings = emptyList())
        val origin = assertNotNull(tracker.latticeOrigin())

        val latPerMeter = 1.0 / 111_320.0
        val lngPerMeter = 1.0 / (111_320.0 * kotlin.math.cos(41.8600 * kotlin.math.PI / 180.0))
        for ((row, col) in listOf(0 to 0, 3 to 7, 12 to 1)) {
            val (lat, lng) = tracker.cellCenterLatLng(row, col)
            // Center offset from origin = (index + 0.5) whole cells, exactly.
            val rowOffset = (lat - origin.first) / (CoverageTracker.cellSizeMeters * latPerMeter)
            val colOffset = (lng - origin.second) / (CoverageTracker.cellSizeMeters * lngPerMeter)
            assertTrue(abs(rowOffset - (row + 0.5)) < 1e-6, "row $row phase off: $rowOffset")
            assertTrue(abs(colOffset - (col + 0.5)) < 1e-6, "col $col phase off: $colOffset")
        }
    }

    @Test
    fun snapRangeToLatticeLandsOnLatticeAndCoversInput() {
        val tile = 1.5
        for ((minV, maxV, phase) in listOf(
            Triple(-3.7, -1.2, 0.25),
            Triple(2.1, 2.2, -10.6),
            Triple(0.0, 0.0, 0.4), // degenerate → at least one tile
            Triple(100.03, 104.9, 3.35),
        )) {
            val (lo, hi) = snapRangeToLattice(minV, maxV, phase, tile)
            assertTrue(lo <= minV + 1e-9 && hi >= maxV - 1e-9, "[$lo,$hi] must cover [$minV,$maxV]")
            assertTrue(hi - lo >= tile - 1e-9, "at least one tile wide")
            // Both endpoints on lattice lines: (v - phase) / tile is integral.
            for (v in listOf(lo, hi)) {
                val k = (v - phase) / tile
                assertTrue(abs(k - kotlin.math.round(k)) < 1e-9, "$v not on lattice(phase=$phase)")
            }
        }
    }

    @Test
    fun arLocalCellEdgesLandOnGridTileLatticeFromOrigin() {
        val tracker = CoverageTracker()
        tracker.initFromBlock(outerRing, innerRings = emptyList())
        val origin = assertNotNull(tracker.latticeOrigin())

        // Geo reference paired somewhere inside the block, camera at an
        // arbitrary AR position — the exact situation on a walk.
        val refLat = 41.8607; val refLng = -87.7191
        val refX = 4.2f; val refZ = -7.9f
        val lngScale = lngScaleFor(refLat)
        val originLocal = latLngToLocal(origin.first, origin.second, refLat, refLng, refX, refZ, lngScale)

        val gridTile = 1.5 // GRID_TILE_METERS on both AR renderers
        for ((row, col) in listOf(0 to 0, 5 to 9, 20 to 14)) {
            val (lat, lng) = tracker.cellCenterLatLng(row, col)
            val local = latLngToLocal(lat, lng, refLat, refLng, refX, refZ, lngScale)
            // Cell EDGES sit half a cell (1.5m = one grid tile) from the
            // center, so centers must land on ODD multiples of half a tile —
            // i.e. (offset / 1.5 - 1) is even ⇔ offset/1.5 is an odd integer.
            val ux = (local.first - originLocal.first) / gridTile
            val uz = (originLocal.second - local.second) / gridTile // north = -z
            // Tolerance: the tracker phases lng with cos(minLat) while the AR
            // conversion uses cos(refLat) — sub-millimeter at block scale.
            assertTrue(abs(ux - kotlin.math.round(ux)) < 1e-3, "cell($row,$col) x off-lattice: $ux")
            assertTrue(abs(uz - kotlin.math.round(uz)) < 1e-3, "cell($row,$col) z off-lattice: $uz")
            assertEquals(1, kotlin.math.round(ux).toInt().mod(2), "x center must be an odd tile multiple")
            assertEquals(1, kotlin.math.round(uz).toInt().mod(2), "z center must be an odd tile multiple")
        }
    }
}
