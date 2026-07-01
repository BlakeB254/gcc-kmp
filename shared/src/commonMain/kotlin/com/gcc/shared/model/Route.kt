package com.gcc.shared.model

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
    val status: RouteStatus = RouteStatus.DRAFT,
    val distanceMeters: Double? = null,
    val estimatedDurationMinutes: Int? = null,
    val payoutAmount: Int? = null,
    val checkpoints: List<RouteCheckpoint> = emptyList(),
    val imageUrl: String? = null,
    val imageLocationLat: Double? = null,
    val imageLocationLng: Double? = null,
    val description: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant
)
