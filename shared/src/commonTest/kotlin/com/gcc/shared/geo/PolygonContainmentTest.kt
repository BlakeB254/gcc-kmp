package com.gcc.shared.geo

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class PolygonContainmentTest {

    private val squareBlock = listOf(
        41.859 to -87.701,
        41.859 to -87.699,
        41.861 to -87.699,
        41.861 to -87.701,
    )

    @Test
    fun `point inside the polygon returns true`() {
        assertTrue(pointInPolygon(41.860, -87.700, squareBlock))
    }

    @Test
    fun `point outside the polygon returns false`() {
        assertFalse(pointInPolygon(41.870, -87.710, squareBlock))
    }

    @Test
    fun `point just past an edge returns false`() {
        assertFalse(pointInPolygon(41.860, -87.6989, squareBlock))
    }
}
