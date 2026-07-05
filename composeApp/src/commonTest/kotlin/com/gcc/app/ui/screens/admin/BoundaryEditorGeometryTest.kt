package com.gcc.app.ui.screens.admin

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundaryEditorGeometryTest {

    // Chicago-ish camera, phone-ish canvas.
    private val proj = BoundaryProjection(
        centerLat = 41.8600,
        centerLng = -87.7200,
        zoom = 17.0,
        widthPx = 1170f,
        heightPx = 2100f,
    )

    @Test
    fun projectPutsCenterAtCanvasCenter() {
        val p = proj.project(41.8600, -87.7200)
        assertEquals(585f, p.x, 0.001f)
        assertEquals(1050f, p.y, 0.001f)
    }

    @Test
    fun unprojectIsExactInverseOfProject() {
        // Any pixel, not just the center — this is what lets the overlay own
        // all touch instead of aiming a crosshair.
        val samples = listOf(
            Offset(0f, 0f),
            Offset(1170f, 2100f),
            Offset(123.4f, 987.6f),
            Offset(585f, 1050f),
        )
        for (px in samples) {
            val (lat, lng) = proj.unproject(px)
            val back = proj.project(lat, lng)
            assertTrue(abs(back.x - px.x) < 0.01f, "x roundtrip for $px: ${back.x}")
            assertTrue(abs(back.y - px.y) < 0.01f, "y roundtrip for $px: ${back.y}")
        }
    }

    @Test
    fun projectMatchesEquirectangularMeters() {
        // 100 m east and 50 m north of center must land mpp-scaled from center.
        val latM = 1.0 / 111_320.0
        val lngM = 1.0 / (111_320.0 * proj.lngScale)
        val p = proj.project(41.8600 + 50 * latM, -87.7200 + 100 * lngM)
        val expectedDx = (100.0 / proj.metersPerPixel).toFloat()
        val expectedDy = (50.0 / proj.metersPerPixel).toFloat()
        assertEquals(585f + expectedDx, p.x, 0.01f)
        assertEquals(1050f - expectedDy, p.y, 0.01f)
    }

    @Test
    fun centerForKeepsPinchCentroidGeoFixed() {
        // The geo point under the pinch centroid must stay under it after a
        // zoom step. centerFor solves the new center; verify by re-projecting.
        val centroid = Offset(300f, 1600f)
        val (gLat, gLng) = proj.unproject(centroid)
        val newZoom = 17.6
        val solver = BoundaryProjection(proj.centerLat, proj.centerLng, newZoom, proj.widthPx, proj.heightPx)
        val (ncLat, ncLng) = solver.centerFor(gLat, gLng, centroid)
        val after = BoundaryProjection(ncLat, ncLng, newZoom, proj.widthPx, proj.heightPx).project(gLat, gLng)
        // Tiny drift allowed: lngScale is re-evaluated at the solved center.
        assertTrue(abs(after.x - centroid.x) < 0.5f, "x drift: ${after.x - centroid.x}")
        assertTrue(abs(after.y - centroid.y) < 0.5f, "y drift: ${after.y - centroid.y}")
    }

    // ── Screen-space hit tests ──────────────────────────────────────────

    private val square = listOf(
        Offset(100f, 100f),
        Offset(200f, 100f),
        Offset(200f, 200f),
        Offset(100f, 200f),
    )

    @Test
    fun pointInPolygonInsideAndOutside() {
        assertTrue(screenPointInPolygon(Offset(150f, 150f), square))
        assertFalse(screenPointInPolygon(Offset(50f, 150f), square))
        assertFalse(screenPointInPolygon(Offset(150f, 250f), square))
        assertFalse(screenPointInPolygon(Offset(150f, 150f), square.take(2)))
    }

    @Test
    fun distanceToPolygonEdgeMeasuresNearestEdge() {
        // 10 px left of the left edge.
        assertEquals(10f, distanceToPolygonEdge(Offset(90f, 150f), square), 0.001f)
        // Inside: 25 px from the top edge is the nearest.
        assertEquals(25f, distanceToPolygonEdge(Offset(150f, 125f), square), 0.001f)
        // Beyond a corner: diagonal distance.
        assertEquals(5f, distanceToSegment(Offset(97f, 96f), square[0], square[1]), 0.001f)
    }
}
