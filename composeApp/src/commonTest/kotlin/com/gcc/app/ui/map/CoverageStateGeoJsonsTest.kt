package com.gcc.app.ui.map

import com.gcc.shared.model.MapCoverageCell
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoverageStateGeoJsonsTest {

    private fun cell(
        lat: Double,
        lng: Double,
        quality: String,
        id: String,
        state: String = "fresh",
        litterCount: Int = 0,
        litterWeightGrams: Double = 0.0,
    ) = MapCoverageCell(
        locationCellId = id,
        lat = lat,
        lng = lng,
        state = state,
        lastScannedAt = "2026-07-08T12:00:00Z",
        totalDistanceMeters = 10.0,
        quality = quality,
        priority = 0.0,
        litterCount = litterCount,
        litterWeightGrams = litterWeightGrams,
    )

    private fun build(cells: List<MapCoverageCell>, useOutlines: Boolean = true) =
        buildCoverageStateGeoJsons(
            coverageCells = cells,
            activeSpots = emptyList(),
            centerLat = 41.8600, centerLng = -87.7200,
            fogBounds = doubleArrayOf(41.855, -87.730, 41.865, -87.710),
            cellSizeMeters = 10.0,
            useOutlines = useOutlines,
        )

    private fun isEmptyCollection(json: String) = !json.contains("Polygon")

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

    // ── Problem areas: scan-found litter must colour a cell (D1) ──

    @Test
    fun scanFoundLitterMakesACellAProblemEvenWithNoCommunityReport() {
        // The headline regression: before this, `dirty` was `hasSpotNear` only,
        // so a cell full of scan-found litter rendered as clean green.
        val json = build(listOf(cell(41.8600, -87.7200, "close", "1:1", litterCount = 2, litterWeightGrams = 500.0)))
        assertTrue(json.problemLight.contains("Polygon"), "a litter cell must produce a problem blob")
        assertTrue(isEmptyCollection(json.clean), "a litter cell must NOT also render as clean")
    }

    @Test
    fun severityTiersRouteCellsToTheirOwnCollections() {
        val light = cell(41.8600, -87.7200, "close", "1:1", litterCount = 1, litterWeightGrams = 100.0)
        val severe = cell(41.8620, -87.7180, "close", "2:2", litterCount = 1, litterWeightGrams = 20_000.0)
        val json = build(listOf(light, severe))
        assertTrue(json.problemLight.contains("Polygon"), "light tier populated")
        assertTrue(json.problemSevere.contains("Polygon"), "severe tier populated")
        assertTrue(isEmptyCollection(json.problemModerate), "moderate tier stays empty when nothing tiers there")
    }

    // ── Staleness is a border, not a fill ──

    @Test
    fun aStaleCellWithLitterRendersBothAProblemFillAndAStaleBorder() {
        // Under the old code this cell rendered amber ONLY — the litter was hidden.
        val json = build(listOf(cell(41.8600, -87.7200, "close", "1:1", state = "stale", litterCount = 1, litterWeightGrams = 5_000.0)))
        assertTrue(json.problemModerate.contains("Polygon"), "stale must not hide the litter fill")
        assertTrue(json.staleBorder.contains("Polygon"), "stale cells still get a border")
    }

    @Test
    fun aStaleCleanCellKeepsItsGreenFillAndGainsABorder() {
        // Regression guard: if the clean tiers were gated on `state == "fresh"`,
        // a stale clean cell would get NO fill and read as never-scanned fog.
        val json = build(listOf(cell(41.8600, -87.7200, "close", "1:1", state = "stale")))
        assertTrue(json.clean.contains("Polygon"), "a stale scanned cell is still scanned ground")
        assertTrue(json.staleBorder.contains("Polygon"), "and it is still stale")
    }

    @Test
    fun aFreshCleanCellHasNoStaleBorder() {
        val json = build(listOf(cell(41.8600, -87.7200, "close", "1:1")))
        assertTrue(json.clean.contains("Polygon"))
        assertTrue(isEmptyCollection(json.staleBorder), "fresh cells carry no stale border")
    }
}
