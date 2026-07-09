package com.gcc.app.ui.map

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildingDataTest {

    @AfterTest
    fun tearDown() = MappedBuildingRegistry.clear()

    @Test
    fun fallsBackToRectanglesWhenNoFootprintsRegistered() {
        MappedBuildingRegistry.clear()
        // Center of the first Roosevelt Rd rectangle (BuildingData L38-42).
        assertTrue(isInsideMappedBuilding(lat = 41.86615, lng = -87.7164))
        // Open street, far from any rectangle.
        assertFalse(isInsideMappedBuilding(lat = 41.8580, lng = -87.7000))
    }

    @Test
    fun registeredFootprintTakesOverFromRectangles() {
        // A footprint far from any NL rectangle — proves registry, not rects.
        val ring = listOf(
            listOf(-87.6100, 41.8900), listOf(-87.6090, 41.8900),
            listOf(-87.6090, 41.8910), listOf(-87.6100, 41.8910),
            listOf(-87.6100, 41.8900),
        )
        MappedBuildingRegistry.setRingsForTile("b:4189:-8761", listOf(ring))
        assertTrue(MappedBuildingRegistry.hasFootprints())
        assertTrue(isInsideMappedBuilding(lat = 41.8905, lng = -87.6095))
        assertFalse(isInsideMappedBuilding(lat = 41.8905, lng = -87.6000))
    }

    @Test
    fun whenFootprintsRegisteredRectangleFallbackIsNotConsulted() {
        val elsewhere = listOf(
            listOf(-87.6100, 41.8900), listOf(-87.6090, 41.8900),
            listOf(-87.6090, 41.8910), listOf(-87.6100, 41.8910),
            listOf(-87.6100, 41.8900),
        )
        MappedBuildingRegistry.setRingsForTile("b:4189:-8761", listOf(elsewhere))
        // The old NL rectangle center is NO LONGER "inside" — registry wins.
        assertFalse(isInsideMappedBuilding(lat = 41.86615, lng = -87.7164))
    }
}
