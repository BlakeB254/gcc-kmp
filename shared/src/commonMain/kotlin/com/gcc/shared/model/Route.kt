@file:UseSerializers(LenientInstantSerializer::class)

package com.gcc.shared.model

import com.gcc.shared.serialization.LenientInstantSerializer
import kotlinx.serialization.UseSerializers

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Route(
    val id: Int,
    val code: String,
    val displayName: String,
    val serviceAreaId: Int,
    val geometry: GeoJSONLineString? = null,
    val blockBoundary: GeoJSONPolygon? = null,
    /**
     * Route-v2 inner boundaries (private-property cutoffs) — MULTIPLE islands
     * supported: unfenced vacant lots between built-up stretches remain part
     * of the scannable corridor. Between blockBoundary and these rings =
     * the required scan area. Null → clients fall back to a parallel inset.
     */
    val innerBoundary: GeoJSONMultiPolygon? = null,
    /**
     * Id of the block that backs this route (blocks.routeId → routes.id).
     * Only populated by GET /api/admin/routes (PR #60); null elsewhere.
     * Needed by the admin boundary editor, which saves via
     * PUT /api/admin/blocks/{blockId}/boundaries.
     */
    val blockId: Int? = null,
    val status: RouteStatus = RouteStatus.DRAFT,
    val distanceMeters: Double? = null,
    val estimatedDurationMinutes: Int? = null,
    val payoutAmount: Int? = null,
    val imageUrl: String? = null,
    val imageLocationLat: Double? = null,
    val imageLocationLng: Double? = null,
    val description: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant
)
