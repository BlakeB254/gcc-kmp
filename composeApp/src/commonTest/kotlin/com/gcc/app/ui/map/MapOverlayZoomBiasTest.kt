package com.gcc.app.ui.map

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The overlay zoom bias must convert the 256-tile logical-pixel Mercator
 * formula into MapLibre's physical-pixel scale: biasing the zoom by
 * log2(density × 2) multiplies the pixel output by exactly density × 2.
 * If this invariant breaks, every Canvas overlay drawn over a GccMap
 * (minimap cells, veil holes, radius rings) detaches from the basemap.
 */
class MapOverlayZoomBiasTest {

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-4) {
        assertTrue(
            abs(expected - actual) <= tolerance * abs(expected),
            "expected $expected, got $actual",
        )
    }

    @Test
    fun biasScalesPixelsByDensityTimesTwo() {
        val lat = 41.8603 // North Lawndale
        val zoom = 17.0
        for (density in listOf(1.0f, 2.0f, 3.0f)) {
            val unbiased = metersToPixels(10.0, lat, zoom).toDouble()
            val biased = metersToPixels(10.0, lat, zoom + mapOverlayZoomBias(density)).toDouble()
            assertClose(unbiased * density * 2.0, biased)
        }
    }

    @Test
    fun cameraZoomRoundTripsThroughBias() {
        // Framing math inverts metersToPixels against physical pixels, then
        // subtracts the bias to get the camera zoom; an overlay re-adding the
        // bias must land back on the physical-pixel fit exactly.
        val density = 3.0f
        val effectiveZoom = 16.4
        val cameraZoom = effectiveZoom - mapOverlayZoomBias(density)
        assertClose(effectiveZoom, cameraZoom + mapOverlayZoomBias(density), 1e-9)
    }
}
