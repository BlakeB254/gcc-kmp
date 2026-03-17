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
    val SPOT_MARKER_COLOR = Color(0xFFF97316)    // orange

    // Spot heatmap layers (stacked circles to simulate density color ramp — matches website)
    const val LAYER_SPOT_HEATMAP = "spot-heatmap"
    const val LAYER_SPOT_HEATMAP_MED = "spot-heatmap-med"
    const val LAYER_SPOT_HEATMAP_HIGH = "spot-heatmap-high"

    // Heatmap radii — large enough for circles to overlap and blend into a cloud
    // Website uses kernel-density heatmap; we simulate with big overlapping circles.
    const val SPOT_HEATMAP_RADIUS_FAR = 25.0f     // overview zoom — big blobs merge
    const val SPOT_HEATMAP_RADIUS_MID = 40.0f     // neighborhood zoom — smooth cloud
    const val SPOT_HEATMAP_RADIUS_CLOSE = 50.0f   // fading out as dots take over
    const val SPOT_HEATMAP_OPACITY_FAR = 0.45f
    const val SPOT_HEATMAP_OPACITY_CLOSE = 0.0f   // fully transparent at close zoom

    // Heatmap color ramp — matches website design-tokens (low density → high density)
    val SPOT_HEATMAP_LOW = Color(0xFFFEE2E2)       // light pink (outer, sparse)
    val SPOT_HEATMAP_MED = Color(0xFFFCA5A5)        // medium pink (mid-range)
    val SPOT_HEATMAP_HIGH = Color(0xFFEF4444)       // red (dense)
    val SPOT_HEATMAP_PEAK = Color(0xFF991B1B)       // deep red (peak density)
    val SPOT_HEATMAP_COLOR = SPOT_HEATMAP_HIGH      // legacy alias

    // Zoom thresholds — dots hidden at overview, only appear after heatmap fades
    const val SPOT_HEATMAP_MIN_ZOOM = 8.0    // heatmap visible from this zoom
    const val SPOT_HEATMAP_MAX_ZOOM = 14.5   // heatmap fades above this zoom
    const val SPOT_DOTS_MIN_ZOOM = 14.0      // dots ONLY after heatmap mostly gone
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
