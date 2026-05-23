package com.gcc.app.ui.map

import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.ServiceArea

/**
 * Resolved map center with coordinates and zoom level.
 */
data class MapCenter(
    val lat: Double,
    val lng: Double,
    val zoom: Double,
)

/**
 * Resolves the best map center using a cascading priority:
 *
 * 1. Worker's assigned service area (if it has a mapCenter)
 * 2. User's current GPS location
 * 3. World-level fallback (0, 0 at zoom 2)
 *
 * @param serviceArea   The worker's assigned service area from the API (may be null)
 * @param currentLocation  The user's current GPS coordinates (may be null if permission denied)
 * @param defaultZoom   Zoom level used for GPS-based centering (defaults to neighborhood)
 */
fun resolveMapCenter(
    serviceArea: ServiceArea?,
    currentLocation: Coordinates?,
    defaultZoom: Double = MapStyles.ZOOM_NEIGHBORHOOD,
): MapCenter {
    // Priority 1: Verified service area with a map center
    serviceArea?.mapCenter?.let { center ->
        return MapCenter(
            lat = center.lat,
            lng = center.lng,
            zoom = serviceArea.mapZoom.takeIf { it > 0 } ?: MapStyles.ZOOM_SERVICE_AREA,
        )
    }

    // The current production service-area endpoint can return "North Lawndale"
    // without mapCenter/mapZoom. Keep service-area screens anchored to the
    // community instead of falling back to simulator/device GPS.
    if (serviceArea?.name?.equals("North Lawndale", ignoreCase = true) == true) {
        return MapCenter(
            lat = MapStyles.NORTH_LAWNDALE_CENTER_LAT,
            lng = MapStyles.NORTH_LAWNDALE_CENTER_LNG,
            zoom = MapStyles.ZOOM_SERVICE_AREA,
        )
    }

    // Priority 2: GPS location
    currentLocation?.let { loc ->
        return MapCenter(
            lat = loc.lat,
            lng = loc.lng,
            zoom = defaultZoom,
        )
    }

    // Priority 3: World fallback
    return MapCenter(
        lat = MapStyles.WORLD_CENTER_LAT,
        lng = MapStyles.WORLD_CENTER_LNG,
        zoom = MapStyles.ZOOM_WORLD,
    )
}
