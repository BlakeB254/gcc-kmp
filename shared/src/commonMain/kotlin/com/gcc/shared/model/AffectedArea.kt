package com.gcc.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One feature in the /api/affected-areas GeoJSON response.
 *
 * The server returns raw GeoJSON (`FeatureCollection`) rather than our
 * usual `ApiResponse<T>` envelope so the payload drops straight into any
 * GeoJSON-aware map renderer. Consumers that need the decoded properties
 * (block id, status, intensity, etc) use this strongly-typed wrapper.
 *
 * Intensity (0..1) is a composite score — see site/src/app/api/affected-areas
 * for the recipe (status tier + funding pct + sponsor count + completion
 * recency decay). It drives the heatmap alpha / color ramp.
 */
@Serializable
data class AffectedAreaFeature(
    val type: String, // "Feature"
    /**
     * Raw GeoJSON geometry element — kept as `JsonElement` so platform
     * map libraries can re-encode it without our code re-wrapping.
     * For current server output this is always a Polygon, but leaving
     * it generic lets the same model handle MultiPolygon + Point later.
     */
    val geometry: JsonElement,
    val properties: AffectedAreaProperties,
)

@Serializable
data class AffectedAreaProperties(
    val blockId: Int,
    val code: String?,
    val name: String?,
    val status: String,      // available | funded | in_progress | completed
    val intensity: Double,   // 0..1
    val scheduledCleaningAt: String? = null,
    val sponsorCount: Int,
    val fundingCurrent: Double,
    val fundingGoal: Double,
    val completedAt: String? = null,
    val serviceAreaName: String? = null,
    val centerLat: Double? = null,
    val centerLng: Double? = null,
)

@Serializable
data class AffectedAreasCollection(
    val type: String, // "FeatureCollection"
    val features: List<AffectedAreaFeature>,
    val generatedAt: String,
    val count: Int,
)

/**
 * Decoded view that most callers actually want — flat list with the
 * geometry kept as a raw element for map pass-through, and a typed
 * polygon accessor for Canvas-based renderers that want to draw the
 * outline themselves without adding a JSON dependency.
 */
@Serializable
data class AffectedBlock(
    val blockId: Int,
    val code: String?,
    val name: String?,
    val status: AffectedBlockStatus,
    val intensity: Double,
    val scheduledCleaningAt: String? = null,
    val sponsorCount: Int,
    val fundingCurrent: Double,
    val fundingGoal: Double,
    val completedAt: String? = null,
    val serviceAreaName: String? = null,
    val center: Coordinates? = null,
    /** Polygon outer ring as lng/lat pairs, in order. Empty if geometry wasn't a simple Polygon. */
    val polygon: List<Coordinates> = emptyList(),
)

@Serializable
enum class AffectedBlockStatus {
    @SerialName("available") AVAILABLE,
    @SerialName("funded") FUNDED,
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("completed") COMPLETED;

    companion object {
        fun fromString(raw: String): AffectedBlockStatus = when (raw) {
            "funded" -> FUNDED
            "in_progress" -> IN_PROGRESS
            "completed" -> COMPLETED
            else -> AVAILABLE
        }
    }
}
