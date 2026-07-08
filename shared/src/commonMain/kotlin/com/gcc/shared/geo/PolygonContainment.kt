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

/**
 * Ray-casting point-in-polygon over a GeoJSON linear ring. Ring vertices are
 * [lng, lat] pairs (GeoJSON order — Coordinates is (lng,lat) repo-wide), so
 * this is the correct check for building footprints returned by
 * `GET /api/map/buildings`. Distinct from [pointInPolygon] above, which takes
 * (lat, lng) Pairs — DO NOT cross the two conventions.
 *
 * A ring may be explicitly closed (last vertex == first) or not; the wrap in
 * the loop handles both. Rings with fewer than 3 vertices are never inside.
 */
fun pointInGeoJsonRing(lng: Double, lat: Double, ring: List<List<Double>>): Boolean {
    if (ring.size < 3) return false
    var inside = false
    var j = ring.size - 1
    for (i in ring.indices) {
        val xi = ring[i][0]; val yi = ring[i][1]
        val xj = ring[j][0]; val yj = ring[j][1]
        if ((yi > lat) != (yj > lat) && lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
            inside = !inside
        }
        j = i
    }
    return inside
}
