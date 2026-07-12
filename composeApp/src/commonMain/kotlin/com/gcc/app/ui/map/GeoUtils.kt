package com.gcc.app.ui.map

import com.gcc.shared.geo.pointInPolygon

/**
 * Find which community area (if any) contains the given GPS coordinates.
 * Containment delegates to the shared (lat, lng) [pointInPolygon] so client
 * and server agree on the algorithm.
 */
fun findCommunityAreaForLocation(lat: Double, lng: Double): CommunityArea? {
    return chicagoCommunityAreas.find { area ->
        // Quick bounding box check first
        lat in area.bounds.south..area.bounds.north &&
        lng in area.bounds.west..area.bounds.east &&
        // Then precise polygon check
        area.polygon.isNotEmpty() && pointInPolygon(lat, lng, area.polygon)
    }
}
