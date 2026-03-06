package com.gcc.app.ui.map

import androidx.compose.ui.graphics.Color
import com.gcc.app.ui.theme.*

/** Style constants for the map layers */
object MapStyles {
    // Base map style URL (OpenFreeMap — full OSM vector tiles with street-level detail)
    const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

    // Chicago center coordinates (North Lawndale area)
    const val CHICAGO_CENTER_LAT = 41.8603
    const val CHICAGO_CENTER_LNG = -87.7228

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
    val CITY_OUTLINE_COLOR = Color(0xFF4B5563)        // dark gray
    const val CITY_OUTLINE_WIDTH = 1.5f

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
}
