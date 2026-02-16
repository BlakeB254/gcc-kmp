package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class RouteMarker(
    val id: Int,
    val routeSessionId: Int,
    val workerId: Int,
    val type: RouteMarkerType,
    val coordinates: Coordinates,
    val note: String? = null,
    val mediaUrls: List<String> = emptyList(),
    val bountyPromptId: Int? = null,
    val createdAt: Instant,
)
