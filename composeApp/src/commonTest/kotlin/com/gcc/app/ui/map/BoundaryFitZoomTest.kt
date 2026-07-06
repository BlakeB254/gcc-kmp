package com.gcc.app.ui.map

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the per-axis fit-zoom math shared by the boundary editor camera and
 * the block-proposal card preview. The formula is the inverse of
 * [metersToPixels]: at `boundaryFitZoom(...) + zoomBias`, the padded span of
 * the binding axis renders exactly to the viewport dimension.
 */
class BoundaryFitZoomTest {

    // A ~200m × ~150m Chicago block viewed in a 1179×450px preview box on a
    // 3× device (zoomBias = log2(3·2)).
    private val latSpan = 0.0018
    private val lngSpan = 0.0018
    private val centerLat = 41.86
    private val bias = mapOverlayZoomBias(3f)

    @Test
    fun bindingAxisSpanRendersToViewportDimension() {
        val zoom = boundaryFitZoom(latSpan, lngSpan, centerLat, widthPx = 1179f, heightPx = 450f, zoomBias = bias)
        // Height binds here (450px vs 1179px for similar meter spans): the
        // padded north-south span at zoom+bias must fill the box height.
        val paddedNorthMeters = latSpan * 111_320.0 * 1.25
        val rendered = metersToPixels(paddedNorthMeters, centerLat, zoom + bias)
        assertTrue(abs(rendered - 450f) < 0.5f, "padded span rendered to ${rendered}px, expected ~450px")
    }

    @Test
    fun tighterAxisWins() {
        val wide = boundaryFitZoom(latSpan, lngSpan * 4, centerLat, 1179f, 450f, bias)
        val square = boundaryFitZoom(latSpan, lngSpan, centerLat, 1179f, 450f, bias)
        assertTrue(wide < square, "quadrupling the east-west span must zoom out once width binds")
    }

    @Test
    fun doublingBindingSpanZoomsOutExactlyOneLevel() {
        val z1 = boundaryFitZoom(latSpan, lngSpan, centerLat, 1179f, 450f, bias)
        val z2 = boundaryFitZoom(latSpan * 2, lngSpan, centerLat, 1179f, 450f, bias)
        assertEquals(1.0, z1 - z2, 1e-9)
    }

    @Test
    fun tinySpansClampToThirtyMeterFloor() {
        val a = boundaryFitZoom(0.00001, 0.00001, centerLat, 1179f, 450f, bias)
        val b = boundaryFitZoom(0.0002, 0.0002, centerLat, 1179f, 450f, bias)
        assertEquals(a, b, 1e-9, "spans under the 30m floor must frame identically")
    }
}
