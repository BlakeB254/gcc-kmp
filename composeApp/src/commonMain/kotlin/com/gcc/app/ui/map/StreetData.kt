package com.gcc.app.ui.map

/**
 * Real Chicago street data for Canvas-based map rendering.
 * Coordinates are actual positions for North Lawndale and surrounding areas.
 *
 * When MapLibre is wired in (android/iOS), this data is unused —
 * real vector tiles render the streets. This is the desktop Canvas fallback.
 */

enum class StreetType { ARTERIAL, COLLECTOR, LOCAL }

data class StreetSegment(
    val name: String,
    val startLat: Double,
    val startLng: Double,
    val endLat: Double,
    val endLng: Double,
    val type: StreetType = StreetType.LOCAL,
    val isEastWest: Boolean = true,
)

/**
 * Major streets visible at ZOOM_NEIGHBORHOOD (14) and above.
 * Arterials are drawn thicker and labeled.
 */
val chicagoStreets: List<StreetSegment> = listOf(
    // ── East-West Arterials ──────────────────────────────────────────
    StreetSegment("Roosevelt Rd", 41.8665, -87.7800, 41.8665, -87.6300, StreetType.ARTERIAL, isEastWest = true),
    StreetSegment("Ogden Ave", 41.8800, -87.7450, 41.8440, -87.6900, StreetType.ARTERIAL, isEastWest = true),
    StreetSegment("Cermak Rd", 41.8510, -87.7800, 41.8510, -87.6300, StreetType.ARTERIAL, isEastWest = true),

    // ── East-West Collectors ─────────────────────────────────────────
    StreetSegment("Fillmore St", 41.8625, -87.7500, 41.8625, -87.6800, StreetType.COLLECTOR, isEastWest = true),
    StreetSegment("16th St", 41.8585, -87.7500, 41.8585, -87.6800, StreetType.COLLECTOR, isEastWest = true),
    StreetSegment("21st St", 41.8535, -87.7500, 41.8535, -87.6800, StreetType.COLLECTOR, isEastWest = true),
    StreetSegment("Flournoy St", 41.8700, -87.7450, 41.8700, -87.6900, StreetType.COLLECTOR, isEastWest = true),
    StreetSegment("Grenshaw St", 41.8640, -87.7450, 41.8640, -87.6900, StreetType.COLLECTOR, isEastWest = true),
    StreetSegment("13th St", 41.8605, -87.7450, 41.8605, -87.6900, StreetType.COLLECTOR, isEastWest = true),
    StreetSegment("19th St", 41.8558, -87.7450, 41.8558, -87.6900, StreetType.COLLECTOR, isEastWest = true),

    // ── North-South Arterials ────────────────────────────────────────
    StreetSegment("Pulaski Rd", 41.9100, -87.7260, 41.8100, -87.7260, StreetType.ARTERIAL, isEastWest = false),
    StreetSegment("Kedzie Ave", 41.9100, -87.7045, 41.8100, -87.7045, StreetType.ARTERIAL, isEastWest = false),
    StreetSegment("Cicero Ave", 41.9100, -87.7450, 41.8100, -87.7450, StreetType.ARTERIAL, isEastWest = false),
    StreetSegment("Western Ave", 41.9100, -87.6860, 41.8100, -87.6860, StreetType.ARTERIAL, isEastWest = false),

    // ── North-South Collectors ───────────────────────────────────────
    StreetSegment("Homan Ave", 41.8900, -87.7105, 41.8300, -87.7105, StreetType.COLLECTOR, isEastWest = false),
    StreetSegment("Central Park Ave", 41.8900, -87.7165, 41.8300, -87.7165, StreetType.COLLECTOR, isEastWest = false),
    StreetSegment("Independence Blvd", 41.8900, -87.7000, 41.8300, -87.7000, StreetType.COLLECTOR, isEastWest = false),
    StreetSegment("Kostner Ave", 41.8900, -87.7340, 41.8300, -87.7340, StreetType.COLLECTOR, isEastWest = false),
    StreetSegment("Springfield Ave", 41.8900, -87.7225, 41.8300, -87.7225, StreetType.COLLECTOR, isEastWest = false),
    StreetSegment("Lawndale Ave", 41.8900, -87.7195, 41.8300, -87.7195, StreetType.COLLECTOR, isEastWest = false),
    StreetSegment("California Ave", 41.9000, -87.6940, 41.8200, -87.6940, StreetType.COLLECTOR, isEastWest = false),
    StreetSegment("Trumbull Ave", 41.8900, -87.7075, 41.8300, -87.7075, StreetType.COLLECTOR, isEastWest = false),
    StreetSegment("St Louis Ave", 41.8900, -87.7135, 41.8300, -87.7135, StreetType.COLLECTOR, isEastWest = false),
    StreetSegment("Hamlin Ave", 41.8900, -87.7210, 41.8300, -87.7210, StreetType.COLLECTOR, isEastWest = false),
    StreetSegment("Ridgeway Ave", 41.8900, -87.7180, 41.8300, -87.7180, StreetType.COLLECTOR, isEastWest = false),
    StreetSegment("Kildare Ave", 41.8900, -87.7370, 41.8300, -87.7370, StreetType.COLLECTOR, isEastWest = false),
    StreetSegment("Karlov Ave", 41.8900, -87.7310, 41.8300, -87.7310, StreetType.COLLECTOR, isEastWest = false),
    StreetSegment("Keeler Ave", 41.8900, -87.7280, 41.8300, -87.7280, StreetType.COLLECTOR, isEastWest = false),
)

/**
 * Parks and green spaces visible at ZOOM_NEIGHBORHOOD.
 * Each is a simple bounding box rendered as a green-tinted rectangle.
 */
data class ParkArea(
    val name: String,
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
)

val chicagoParks: List<ParkArea> = listOf(
    ParkArea("Douglas Park", 41.8650, 41.8590, -87.6930, -87.7050),
    ParkArea("Lawndale Park", 41.8605, 41.8580, -87.7150, -87.7200),
    ParkArea("Franklin Park", 41.8680, 41.8660, -87.7050, -87.7100),
    ParkArea("Homan Square", 41.8680, 41.8660, -87.7100, -87.7135),
)
