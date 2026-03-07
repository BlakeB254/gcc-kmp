package com.gcc.app.ui.map

/**
 * Ray-casting point-in-polygon check.
 * Polygon is a list of (lat, lng) pairs forming a closed ring.
 */
fun isPointInPolygon(lat: Double, lng: Double, polygon: List<Pair<Double, Double>>): Boolean {
    var inside = false
    var j = polygon.size - 1
    for (i in polygon.indices) {
        val yi = polygon[i].first
        val xi = polygon[i].second
        val yj = polygon[j].first
        val xj = polygon[j].second
        if (((yi > lat) != (yj > lat)) &&
            (lng < (xj - xi) * (lat - yi) / (yj - yi) + xi)
        ) {
            inside = !inside
        }
        j = i
    }
    return inside
}

/**
 * Find which community area (if any) contains the given GPS coordinates.
 */
fun findCommunityAreaForLocation(lat: Double, lng: Double): CommunityArea? {
    return chicagoCommunityAreas.find { area ->
        // Quick bounding box check first
        lat in area.bounds.south..area.bounds.north &&
        lng in area.bounds.west..area.bounds.east &&
        // Then precise polygon check
        area.polygon.isNotEmpty() && isPointInPolygon(lat, lng, area.polygon)
    }
}
