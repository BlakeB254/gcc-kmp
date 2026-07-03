package com.gcc.app.ui.map

import com.gcc.shared.model.Coordinates
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteInnerBoundaryTest {

    // 100m (E-W) × 40m (N-S) block at Chicago latitude.
    private val lat0 = 41.8600
    private val latM = 1.0 / 111_320.0
    private fun lngM(lat: Double) = 1.0 / (111_320.0 * kotlin.math.cos(lat * kotlin.math.PI / 180.0))
    private val rect = listOf(
        Coordinates(lat = lat0, lng = -87.7200),
        Coordinates(lat = lat0, lng = -87.7200 + 100 * lngM(lat0)),
        Coordinates(lat = lat0 + 40 * latM, lng = -87.7200 + 100 * lngM(lat0)),
        Coordinates(lat = lat0 + 40 * latM, lng = -87.7200),
        Coordinates(lat = lat0, lng = -87.7200),
    )

    @Test
    fun rectangleInsetsUniformlyOnEverySide() {
        val inner = insetRingByEdgeOffset(rect, 8.0)
        assertNotNull(inner)
        // Every inner vertex must be exactly 8m in from BOTH adjacent sides:
        // lat span 40-16=24m, lng span 100-16=84m.
        val lats = inner.map { it.lat }
        val lngs = inner.map { it.lng }
        val latSpanM = (lats.max() - lats.min()) / latM
        val lngSpanM = (lngs.max() - lngs.min()) / lngM(lat0)
        assertTrue(abs(latSpanM - 24.0) < 0.5, "lat span ${latSpanM}m, expected ~24m")
        assertTrue(abs(lngSpanM - 84.0) < 0.5, "lng span ${lngSpanM}m, expected ~84m")
        // And sit strictly inside the outer ring.
        assertTrue(lats.min() > lat0 && lats.max() < lat0 + 40 * latM)
    }

    @Test
    fun worksRegardlessOfWinding() {
        val reversed = rect.reversed()
        val inner = insetRingByEdgeOffset(reversed, 8.0)
        assertNotNull(inner)
        val latSpanM = (inner.maxOf { it.lat } - inner.minOf { it.lat }) / latM
        assertTrue(abs(latSpanM - 24.0) < 0.5)
    }

    @Test
    fun tooSmallLoopReturnsNull() {
        // 10x10m loop can't fit an 8m inset.
        val tiny = listOf(
            Coordinates(lat = lat0, lng = -87.7200),
            Coordinates(lat = lat0, lng = -87.7200 + 10 * lngM(lat0)),
            Coordinates(lat = lat0 + 10 * latM, lng = -87.7200 + 10 * lngM(lat0)),
            Coordinates(lat = lat0 + 10 * latM, lng = -87.7200),
            Coordinates(lat = lat0, lng = -87.7200),
        )
        assertNull(insetRingByEdgeOffset(tiny, 8.0))
    }
}
