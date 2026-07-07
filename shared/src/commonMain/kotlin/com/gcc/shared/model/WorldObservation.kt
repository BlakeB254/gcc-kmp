package com.gcc.shared.model

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable

/**
 * One row of the unified world-observation model served by
 * `GET /api/map/observations` — the map/digital-twin layer fed by both
 * route-scan hotspots and community spot reports. Only rows that are
 * currently "current" (not superseded) and not inside a private-property
 * exclusion zone are served.
 *
 * See docs/superpowers/specs/2026-07-01-unified-scan-ecosystem-and-immersive-route-design.md §2.
 */
@Serializable
data class WorldObservation(
    val id: Int,
    val lat: Double,
    val lng: Double,
    val itemType: String? = null,
    /** 'litter' (default) | 'vegetation' | 'construction' | 'land_use'. */
    val domain: String? = null,
    val state: String, // 'dirty' | 'clean'
    val severity: Int? = null,
    val mediaUrl: String? = null,
    val sourceType: String, // 'route_scan' | 'spot_report' | 'ar_auto_detect'
    val capturedAt: String? = null,
    val scanHotspotId: Int? = null,
    val communityReportId: Int? = null,
    /**
     * Physical width in meters (footprint diameter for vegetation circles,
     * footprint width for oriented rectangles). Null when unmeasured — older
     * observations and clients predate width capture (Pillar 3 Task 1).
     */
    val widthMeters: Double? = null,
    /**
     * Compass bearing (degrees, [0, 360)) of the observed face, used to
     * orient a true-scale footprint on the map. Null when unmeasured.
     */
    val yawDegrees: Double? = null,
)

@Serializable
data class WorldObservationsResponse(
    val observations: List<WorldObservation> = emptyList(),
    val success: Boolean = false,
)

/**
 * Synthetic-pin id offset: scan-sourced observations rendered on the map as
 * lightweight CommunityReport pins use `SCAN_OBSERVATION_PIN_ID_BASE + id`
 * so they can never collide with a real community-report id.
 */
const val SCAN_OBSERVATION_PIN_ID_BASE = 1_000_000

/**
 * Convert a route-scan-sourced observation into a lightweight map pin using
 * the CommunityReport shape the map layers already render. Only intended for
 * observations with no backing community report (communityReportId == null) —
 * report-backed observations are already on the map via /api/spots.
 */
fun WorldObservation.toScanMapPin(): CommunityReport {
    val now = Clock.System.now()
    return CommunityReport(
        id = SCAN_OBSERVATION_PIN_ID_BASE + id,
        type = CommunityReportType.SIGHTING,
        // NAMED args: the ctor is GeoJSON-ordered (lng, lat) — the positional
        // call here transposed every scan-observation pin off the map.
        coordinates = Coordinates(lat = lat, lng = lng),
        description = run {
            val source = if (sourceType == "ar_auto_detect") "AR scan" else "Route scan"
            itemType?.let { "$source: $it" } ?: "$source detection"
        },
        status = CommunityReportStatus.VERIFIED,
        tags = listOfNotNull(spotTagForItemType(itemType)),
        photoUrl = mediaUrl,
        itemType = itemType,
        // Severity rides the report's existing AiAnalysis channel so pin
        // detail UI and heat weighting see it without a new wire field.
        aiAnalysis = severity?.let { AiAnalysis(severity = severityLabel(it)) },
        affectedRadiusMeters = 10.0, // world-observation grid cell size
        createdAt = now,
        updatedAt = now,
    )
}

/** Hotspot severity (1–5 stars) → the AiAnalysis low/medium/high vocabulary. */
fun severityLabel(severity: Int): String = when {
    severity >= 4 -> "high"
    severity >= 2 -> "medium"
    else -> "low"
}

/** Best-effort mapping from the observation itemType vocabulary to SpotTag. */
fun spotTagForItemType(itemType: String?): SpotTag? {
    if (itemType == null) return null
    return when (itemType.lowercase()) {
        "tire", "tires" -> SpotTag.TIRES
        "glass" -> SpotTag.GLASS
        "metal" -> SpotTag.METAL
        "couch", "furniture", "mattress" -> SpotTag.FURNITURE
        "construction", "construction_debris" -> SpotTag.CONSTRUCTION
        "leaves" -> SpotTag.LEAVES
        "organic", "tree_branch", "branches" -> SpotTag.ORGANIC
        "hazardous" -> SpotTag.HAZARDOUS
        "electronics", "ebike", "e_bike" -> SpotTag.ELECTRONICS
        "clothing" -> SpotTag.CLOTHING
        "graffiti" -> SpotTag.GRAFFITI
        else -> SpotTag.TRASH
    }
}
