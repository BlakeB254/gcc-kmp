package com.gcc.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class RouteCheckpoint(
    val id: Int? = null,
    val name: String,
    val sequenceOrder: Int,
    val coordinates: Coordinates,
    val photoRequired: Boolean = false,
    val geofenceRadius: Double = 15.0,
    val type: CheckpointType = CheckpointType.PHOTO_POINT,
    val instructions: String? = null,
    val cameraMode: CameraMode? = null,
    val bountyPrompts: List<String> = emptyList(),
)
