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
}
