package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Block(
    val id: Int,
    val code: String,
    val name: String,
    val serviceAreaId: Int? = null,
    val status: BlockStatus = BlockStatus.AVAILABLE,
    val boundary: GeoJSONPolygon? = null,
    val center: GeoJSONPoint? = null,
    val displayImage: String? = null,
    val imageLocationLat: Double? = null,
    val imageLocationLng: Double? = null,
    val fundingGoal: Int? = null,
    val fundingCurrent: Int = 0,
    val sponsorId: Int? = null,
    val checkpoints: List<RouteCheckpoint> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant
)
