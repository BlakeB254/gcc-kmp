package com.gcc.app.platform

import com.gcc.shared.model.Coordinates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverageTrackerSeedTest {

    // NB: Coordinates is GeoJSON-ordered (lng, lat) — use named args.
    private fun tracker(): CoverageTracker = CoverageTracker().apply {
        initFromRoute(
            listOf(
                Coordinates(lat = 41.8600, lng = -87.7200),
                Coordinates(lat = 41.8605, lng = -87.7200),
            )
        )
    }

    @Test
    fun cellForLatLngRoundTripsWithCellCenter() {
        val t = tracker()
        val cell = t.cellForLatLng(41.8602, -87.7200)
        assertNotNull(cell)
        val (lat, lng) = t.cellCenterLatLng(cell.first, cell.second)
        val roundTrip = t.cellForLatLng(lat, lng)
        assertEquals(cell, roundTrip)
    }

    @Test
    fun cellForLatLngOutsideGridIsNull() {
        val t = tracker()
        assertNull(t.cellForLatLng(42.5, -87.72)) // ~70km north of the route
    }

    @Test
    fun cellForLatLngBeforeInitIsNull() {
        assertNull(CoverageTracker().cellForLatLng(41.86, -87.72))
    }

    @Test
    fun seededCellsAreExcludedFromFogButNotSessionCoverage() {
        val t = tracker()
        val cell = t.cellForLatLng(41.8602, -87.7200)!!
        assertTrue(cell in t.getUnscannedCells())

        t.seedScannedCells(setOf(cell))

        assertFalse(cell in t.getUnscannedCells())
        assertEquals(0.0, t.coveragePercent.value) // seeding is fog-only
    }

    /** Task 1: ledger presence (fresh OR stale) lifts fog — seeding is not
     *  restricted to "fresh" cells. */
    @Test
    fun seedingWithFreshAndStaleCellsBothLiftFog() {
        val t = tracker()
        val freshCell = t.cellForLatLng(41.8601, -87.7200)!!
        val staleCell = t.cellForLatLng(41.8603, -87.7200)!!
        assertTrue(freshCell in t.getUnscannedCells())
        assertTrue(staleCell in t.getUnscannedCells())

        t.seedScannedCells(setOf(freshCell, staleCell))

        assertFalse(freshCell in t.getUnscannedCells())
        assertFalse(staleCell in t.getUnscannedCells())
    }

    /** [CoverageTracker.getUnscannedCellsIgnoringSeed] must keep seeing a
     *  ledger-seeded cell as unscanned (ignoring the fog exemption) until it
     *  is actually credited THIS session — this is what lets a route-mode
     *  "needs revisit" classifier keep working after Task 1 seeds stale
     *  cells as fog-exempt. */
    @Test
    fun getUnscannedCellsIgnoringSeedKeepsSeededCellsUntilCreditedThisSession() {
        val t = tracker()
        val cell = t.cellForLatLng(41.8602, -87.7200)!!
        t.seedScannedCells(setOf(cell))

        // Fog-exempt (seeded) but still "unscanned" from the ignoring-seed view.
        assertFalse(cell in t.getUnscannedCells())
        assertTrue(cell in t.getUnscannedCellsIgnoringSeed())

        val (lat, lng) = t.cellCenterLatLng(cell.first, cell.second)
        t.updatePosition(lat, lng)

        // Credited this session — drops out of both views.
        assertFalse(cell in t.getUnscannedCells())
        assertFalse(cell in t.getUnscannedCellsIgnoringSeed())
    }

    /** Session SEEN-tier credit alone must never lift fog (existing
     *  semantics, pinned): only GRAZED+ counts as "scanned" for
     *  [CoverageTracker.getUnscannedCells]. */
    @Test
    fun sessionSeenCreditAloneDoesNotLiftFog() {
        val t = tracker()
        val cell = t.cellForLatLng(41.8602, -87.7200)!!
        val (lat, lng) = t.cellCenterLatLng(cell.first, cell.second)
        t.updateWithRaycastHits(listOf(RaycastCoverageHit(lat = lat, lng = lng, distanceMeters = 40.0)))

        assertTrue(cell in t.getUnscannedCells()) // SEEN tier only — still fogged
    }
}
