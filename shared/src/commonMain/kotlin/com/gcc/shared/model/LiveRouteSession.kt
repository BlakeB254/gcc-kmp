package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class LiveRouteSession(
    val id: Int,
    val routeId: Int,
    val workerId: Int,
    val workerName: String,
    val routeCode: String,
    val routeDisplayName: String,
    val status: RouteSessionStatus,
    val currentPosition: Coordinates? = null,
    val checkpointsCompleted: Int = 0,
    val checkpointsTotal: Int = 0,
    val markers: List<RouteMarker> = emptyList(),
    val messages: List<RouteMessage> = emptyList(),
    val startedAt: Instant,
    val lastUpdateAt: Instant,
)

@Serializable
data class RouteMessage(
    val id: Int,
    val routeSessionId: Int,
    val senderId: Int,
    val senderName: String,
    val senderRole: String,
    val content: String,
    val createdAt: Instant,
)
