package com.gcc.app.ui.screens.admin

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

/**
 * The ONE pixel↔lat/lng conversion pair for the boundary editor.
 *
 * Equirectangular projection around the controlled map center — identical math
 * to the Canvas draw path and to MapStyles.metersToPixels (Mercator
 * meters-per-pixel at the center latitude), so a hit test, a drag and the
 * rendered geometry can never disagree. Because the projection is invertible
 * for ANY pixel (not just the center), the overlay can own all touch while
 * GccMap stays gesture-disabled and purely state-driven.
 */
internal class BoundaryProjection(
    val centerLat: Double,
    val centerLng: Double,
    val zoom: Double,
    val widthPx: Float,
    val heightPx: Float,
) {
    val lngScale: Double = cos(centerLat * PI / 180.0)

    /** Same formula as MapStyles.metersToPixels: 156543.03392·cos(lat)/2^z. */
    val metersPerPixel: Double = 156543.03392 * lngScale / 2.0.pow(zoom)

    /** Geo → screen pixel. */
    fun project(lat: Double, lng: Double): Offset {
        val eastMeters = (lng - centerLng) * 111_320.0 * lngScale
        val northMeters = (lat - centerLat) * 111_320.0
        return Offset(
            widthPx / 2f + (eastMeters / metersPerPixel).toFloat(),
            heightPx / 2f - (northMeters / metersPerPixel).toFloat(),
        )
    }

    /** Screen pixel → geo (lat, lng). Exact inverse of [project]. */
    fun unproject(pos: Offset): Pair<Double, Double> {
        val eastMeters = (pos.x - widthPx / 2f) * metersPerPixel
        val northMeters = (heightPx / 2f - pos.y) * metersPerPixel
        return (centerLat + northMeters / 111_320.0) to (centerLng + eastMeters / (111_320.0 * lngScale))
    }

    /**
     * The map center that would place geo point ([lat], [lng]) at screen
     * position [pos] for THIS projection's zoom/size — the pinch-zoom solve
     * that keeps the geo point under the pinch centroid fixed.
     */
    fun centerFor(lat: Double, lng: Double, pos: Offset): Pair<Double, Double> {
        val newCenterLat = lat + (pos.y - heightPx / 2f) * metersPerPixel / 111_320.0
        val newCenterLng = lng - (pos.x - widthPx / 2f) * metersPerPixel / (111_320.0 * lngScale)
        return newCenterLat to newCenterLng
    }
}

/** Ray-cast point-in-polygon in screen space. [ring] is unclosed. */
internal fun screenPointInPolygon(p: Offset, ring: List<Offset>): Boolean {
    if (ring.size < 3) return false
    var inside = false
    var j = ring.size - 1
    for (i in ring.indices) {
        val a = ring[i]
        val b = ring[j]
        if ((a.y > p.y) != (b.y > p.y)) {
            val xInt = (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
            if (p.x < xInt) inside = !inside
        }
        j = i
    }
    return inside
}

/** Distance from [p] to segment [a]–[b] in pixels. */
internal fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val len2 = ab.x * ab.x + ab.y * ab.y
    if (len2 <= 0f) return (p - a).getDistance()
    val t = (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2).coerceIn(0f, 1f)
    return (p - Offset(a.x + ab.x * t, a.y + ab.y * t)).getDistance()
}

/** Min distance from [p] to any edge of the (unclosed) [ring], in pixels. */
internal fun distanceToPolygonEdge(p: Offset, ring: List<Offset>): Float {
    if (ring.isEmpty()) return Float.MAX_VALUE
    if (ring.size == 1) return (p - ring[0]).getDistance()
    return ring.indices.minOf { i -> distanceToSegment(p, ring[i], ring[(i + 1) % ring.size]) }
}
