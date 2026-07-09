package com.gcc.shared.geo

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolygonContainmentTest {

    // A rectangle near North Lawndale, GeoJSON [lng, lat] order (lng negative).
    private val square = listOf(
        listOf(-87.720, 41.860),
        listOf(-87.710, 41.860),
        listOf(-87.710, 41.866),
        listOf(-87.720, 41.866),
        listOf(-87.720, 41.860), // closed ring
    )

    @Test
    fun pointInsideSquareIsInside() {
        assertTrue(pointInGeoJsonRing(lng = -87.715, lat = 41.863, ring = square))
    }

    @Test
    fun pointOutsideSquareIsOutside() {
        assertFalse(pointInGeoJsonRing(lng = -87.700, lat = 41.863, ring = square)) // east
        assertFalse(pointInGeoJsonRing(lng = -87.715, lat = 41.900, ring = square)) // north
    }

    @Test
    fun ringWithoutExplicitClosureStillWorks() {
        val open = square.dropLast(1)
        assertTrue(pointInGeoJsonRing(lng = -87.715, lat = 41.863, ring = open))
        assertFalse(pointInGeoJsonRing(lng = -87.700, lat = 41.863, ring = open))
    }

    @Test
    fun degenerateRingIsOutside() {
        assertFalse(pointInGeoJsonRing(lng = -87.715, lat = 41.863, ring = emptyList()))
        assertFalse(
            pointInGeoJsonRing(
                lng = -87.715, lat = 41.863,
                ring = listOf(listOf(-87.72, 41.86), listOf(-87.71, 41.86)),
            ),
        )
    }
}
