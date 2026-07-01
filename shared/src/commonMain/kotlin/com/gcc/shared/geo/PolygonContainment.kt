package com.gcc.shared.geo

/**
 * Ray-casting point-in-polygon test. Polygon points are (lat, lng) pairs,
 * matching site/src/lib/geo-utils.ts's pointInPolygon convention so the
 * client-side and server-side containment checks agree.
 */
fun pointInPolygon(lat: Double, lng: Double, polygon: List<Pair<Double, Double>>): Boolean {
    var inside = false
    var j = polygon.size - 1
    for (i in polygon.indices) {
        val (yi, xi) = polygon[i]
        val (yj, xj) = polygon[j]
        if ((yi > lat) != (yj > lat) && lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
            inside = !inside
        }
        j = i
    }
    return inside
}
