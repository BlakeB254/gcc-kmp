package com.gcc.app.ui.map

import com.gcc.shared.model.MapCoverageCell
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoverageStateGeoJsonsTest {

    private fun cell(lat: Double, lng: Double, quality: String, id: String) = MapCoverageCell(
        locationCellId = id,
        lat = lat,
        lng = lng,
        state = "fresh",
        lastScannedAt = "2026-07-08T12:00:00Z",
        totalDistanceMeters = 10.0,
        quality = quality,
        priority = 0.0,
    )

    @Test
    fun outlineModeEmitsMergedRingsWithoutCellIdForCleanTier() {
        // Two adjacent "close" cells → merged ring(s), no per-cell cellId.
        val a = cell(41.8600, -87.7200, "close", "1:1")
        val b = cell(41.8600, -87.71988, "close", "1:2") // ~10m east
        val json = buildCoverageStateGeoJsons(
            coverageCells = listOf(a, b),
            activeSpots = emptyList(),
            centerLat = 41.8600, centerLng = -87.7200,
            fogBounds = doubleArrayOf(41.855, -87.730, 41.865, -87.710),
            cellSizeMeters = 10.0,
            useOutlines = true,
        )
        assertFalse(json.clean.contains("\"cellId\""), "outline-mode clean rings must not carry cellId")
        assertTrue(json.fog.contains("Polygon"), "fog should be a polygon with merged holes")
    }

    @Test
    fun perCellModeStillCarriesCellIdForTap() {
        val a = cell(41.8600, -87.7200, "close", "1:1")
        val json = buildCoverageStateGeoJsons(
            coverageCells = listOf(a),
            activeSpots = emptyList(),
            centerLat = 41.8600, centerLng = -87.7200,
            useOutlines = false,
        )
        assertTrue(json.clean.contains("\"cellId\""), "per-cell mode keeps cellId for tap")
    }
}
