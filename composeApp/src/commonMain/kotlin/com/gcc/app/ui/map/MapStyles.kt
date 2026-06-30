package com.gcc.app.ui.map

import androidx.compose.ui.graphics.Color
import com.gcc.app.ui.theme.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow

/** Style constants for the map layers */
object MapStyles {
    // Base map style URL (OpenFreeMap — full OSM vector tiles with street-level detail)
    const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

    // North Lawndale service area center (used for seed data and community area features)
    const val NORTH_LAWNDALE_CENTER_LAT = 41.8603
    const val NORTH_LAWNDALE_CENTER_LNG = -87.7228

    // Global fallback when no service area or GPS is available
    const val WORLD_CENTER_LAT = 0.0
    const val WORLD_CENTER_LNG = 0.0
    const val ZOOM_WORLD = 2.0

    // Default zoom levels
    const val ZOOM_CITY = 11.0
    const val ZOOM_SERVICE_AREA = 13.2       // fits full service area with overlay padding
    const val ZOOM_NEIGHBORHOOD = 14.0
    const val ZOOM_AREA = ZOOM_NEIGHBORHOOD  // alias — matches "Area" preset in MapControls
    const val ZOOM_ROUTE = 15.0
    const val ZOOM_ACTIVE_JOB = 17.0
    const val ZOOM_STREET = 19.0


    // Layer IDs
    const val LAYER_COMMUNITY_AREAS = "community-areas-fill"
    const val LAYER_COMMUNITY_AREAS_OUTLINE = "community-areas-outline"
    const val LAYER_ROUTE_FILL = "route-fill"
    const val LAYER_ROUTE_GLOW = "route-glow"
    const val LAYER_ROUTE_CASING = "route-casing"
    const val LAYER_ROUTE_LINE = "route-line"
    const val LAYER_ROUTE_LINE_COMPLETED = "route-line-completed"
    const val LAYER_ROUTE_LINE_REMAINING = "route-line-remaining"
    const val LAYER_CHECKPOINT_OUTER = "checkpoint-outer"
    const val LAYER_CHECKPOINT_INNER = "checkpoint-inner"
    const val LAYER_CHECKPOINT_START = "checkpoint-start-outer"
    const val LAYER_CHECKPOINT_START_INNER = "checkpoint-start-inner"
    const val LAYER_CHECKPOINT_END = "checkpoint-end-outer"
    const val LAYER_CHECKPOINT_END_INNER = "checkpoint-end-inner"
    const val LAYER_CHECKPOINTS = "checkpoints"
    const val LAYER_COMMUNITY_LABELS = "community-labels"
    const val LAYER_EXPLORER_LABELS = "explorer-labels"
    const val LAYER_GPS_DOT = "gps-dot"
    const val LAYER_STATE_OUTLINE = "state-outline"
    const val LAYER_CITY_OUTLINE = "city-outline"

    // Route line styles — 3-layer stack (glow → casing → core)
    const val ROUTE_GLOW_WIDTH = 14.0f
    const val ROUTE_GLOW_OPACITY = 0.15f
    const val ROUTE_CASING_WIDTH = 8.0f
    const val ROUTE_LINE_WIDTH = 5.0f
    const val ROUTE_LINE_WIDTH_ACTIVE = 6.0f
    const val COMPLETED_LINE_OPACITY = 1.0f
    const val REMAINING_LINE_OPACITY = 0.5f

    // Route casing color (darker green for border/depth)
    val ROUTE_CASING_COLOR = Color(0xFF14532D)
    val ROUTE_GLOW_COLOR = Color(0xFF22C55E) // same as GccGreen but used at low opacity
    val ROUTE_FILL_COLOR = Color(0x4022C55E)  // 25% opacity green — overview fill

    // Checkpoint marker sizes
    const val CHECKPOINT_OUTER_RADIUS = 8.0f
    const val CHECKPOINT_INNER_RADIUS = 5.5f
    const val CHECKPOINT_START_END_OUTER = 11.0f
    const val CHECKPOINT_START_END_INNER = 7.5f

    // Checkpoint colors
    val CHECKPOINT_START_COLOR = Color(0xFF16A34A) // vivid green
    val CHECKPOINT_END_COLOR = Color(0xFFF97316)   // orange/finish

    // Community area styles (outline only — no fill)
    val COMMUNITY_AREA_STROKE_COLOR = Color(0xFFDC2626) // red boundary
    const val COMMUNITY_AREA_STROKE_WIDTH = 1.5f

    // Geographic boundary styles
    val STATE_OUTLINE_COLOR = Color(0xFF9CA3AF)       // gray
    const val STATE_OUTLINE_WIDTH = 1.0f
    val CITY_OUTLINE_COLOR = Color(0xFF1E3A5F)        // deep blue-gray (pronounced)
    const val CITY_OUTLINE_WIDTH = 3.0f
    val CITY_OUTLINE_GLOW_COLOR = Color(0xFF1E3A5F)   // same, used at low opacity for glow
    const val LAYER_CITY_OUTLINE_GLOW = "city-outline-glow"

    // Geofence
    const val GEOFENCE_RADIUS_METERS = 15.0
    val GEOFENCE_FILL_COLOR = Color(0x3322C55E) // semi-transparent green

    // GPS dot
    const val GPS_DOT_RADIUS = 12.0f
    const val GPS_PULSE_RADIUS = 24.0f

    // Building colors (for street-level rendering)
    val BUILDING_TOP = Color(0xFFD1FAE5)
    val BUILDING_FRONT = Color(0xFF86EFAC)
    val BUILDING_SIDE = Color(0xFF4ADE80)
    val BUILDING_STROKE = Color(0xFF22C55E)

    // Grid color for street level
    val GRID_COLOR = Color(0x1A6B7280)

    // Context marker pin colors
    val PIN_RED = Color(0xFFEF4444)
    val PIN_SHADOW = Color(0x33000000)

    // Bounty pulse orange
    val BOUNTY_PULSE_COLOR = Color(0xFFF97316)

    // Bounty marker layers
    const val LAYER_BOUNTY_DOTS = "bounty-dots"
    const val LAYER_BOUNTY_LABELS = "bounty-labels"
    const val LAYER_BOUNTY_RADIUS = "bounty-radius"
    val BOUNTY_DOT_COLOR = Color(0xFFEF4444)   // red — matches PIN_RED
    val BOUNTY_RADIUS_FILL = Color(0x1AEF4444)  // 10% opacity red

    // Spot report marker layers
    const val LAYER_SPOT_MARKERS = "spot-markers"
    const val LAYER_SPOT_MARKER_HALOS = "spot-marker-halos"
    const val LAYER_BOUNTIED_SPOT_HALOS = "bountied-spot-halos"
    const val LAYER_SPOT_CLEAN_ZONE_BASE = "spot-clean-zone-base"
    const val LAYER_SPOT_THERMAL_FIELD = "spot-thermal-field"
    const val LAYER_SPOT_HEAT_FAR_OUTER = "spot-heat-far-outer"
    const val LAYER_SPOT_HEAT_FAR_MID = "spot-heat-far-mid"
    const val LAYER_SPOT_HEAT_FAR_CORE = "spot-heat-far-core"
    const val LAYER_SPOT_HEAT_ZONE_OUTER = "spot-heat-zone-outer"
    const val LAYER_SPOT_HEAT_ZONE_MID = "spot-heat-zone-mid"
    const val LAYER_SPOT_HEAT_ZONE_CORE = "spot-heat-zone-core"
    const val LAYER_SPOT_OVERVIEW_HEAT_OUTER = "spot-overview-heat-outer"
    const val LAYER_SPOT_OVERVIEW_HEAT_MID = "spot-overview-heat-mid"
    const val LAYER_SPOT_OVERVIEW_HEAT_CORE = "spot-overview-heat-core"
    val SPOT_MARKER_COLOR = Color(0xFFF97316)    // orange (unchanged — dots)
    // Doppler ramp — GCC colors (green → yellow → orange → red → deep-red core, no white blowout)
    val SPOT_HEAT_EDGE = Color(0xFF2EEA52)       // green — Light
    val SPOT_HEAT_LOW = Color(0xFF7CFC2F)        // lime-green — Light
    val SPOT_HEAT_MEDIUM = Color(0xFFFDE047)     // yellow — Moderate
    val SPOT_HEAT_HIGH = Color(0xFFF97316)       // orange — Heavy
    val SPOT_HEAT_EXTREME = Color(0xFFEF4444)    // red — Heavy
    val SPOT_HEAT_CRITICAL = Color(0xFFDC2626)   // red — Heavy
    val SPOT_HEAT_NEAR_PEAK = Color(0xFFC81E1E)  // deep red — Extreme
    val SPOT_HEAT_PEAK = Color(0xFFB91C1C)       // deep-red core — Extreme (no white)
    val CLEAN_BASE_COLOR = Color(0xFF128F3A)     // retained but no longer used for heat base
    val SPOT_SCRIM_COLOR = Color(0xFFF5F6F8)     // neutral scrim beneath heatmap

    // Spot heatmap layer: clean green baseline, then yellow/orange/red density.
    // The clean baseline only renders when spot reports exist, so an empty map stays neutral.
    const val LAYER_SPOT_HEATMAP = "spot-heatmap"
    const val SPOT_HEATMAP_RADIUS_FAR = 18.0f
    const val SPOT_HEATMAP_RADIUS_MID = 56.0f
    const val SPOT_HEATMAP_RADIUS_CLOSE = 0.0f
    const val SPOT_HEATMAP_OPACITY_FAR = 0.82f
    const val SPOT_HEATMAP_OPACITY_CLOSE = 0.0f  // fully transparent at close zoom

    // Heatmap fallback color for non-native renderers.
    val SPOT_HEATMAP_COLOR = SPOT_HEAT_EXTREME

    // Zoom thresholds — heatmap below the switch, spot dots at/above the switch.
    // Keep the handoff tight so the app never renders dots and heat at the same zoom.
    const val SPOT_HEATMAP_MIN_ZOOM = 2.0
    const val SPOT_HEATMAP_MAX_ZOOM = 12.99
    const val SPOT_HEATMAP_FADE_OUT_ZOOM = 12.99
    const val SPOT_DOTS_MIN_ZOOM = 13.0
}


/** Convert meters to pixels at a given latitude and zoom level (Mercator projection). */
fun metersToPixels(meters: Double, lat: Double, zoom: Double): Float {
    val metersPerPixel = 156543.03392 * cos(lat * kotlin.math.PI / 180.0) / 2.0.pow(zoom)
    return (meters / metersPerPixel).toFloat()
}

/** Format a coordinate to 4 decimal places, handling negative values correctly. */
fun formatCoord(value: Double): String {
    val sign = if (value < 0) "-" else ""
    val absVal = abs(value)
    val intPart = absVal.toInt()
    val fracPart = ((absVal - intPart) * 10000).toInt()
    return "$sign$intPart.${fracPart.toString().padStart(4, '0')}"
}
