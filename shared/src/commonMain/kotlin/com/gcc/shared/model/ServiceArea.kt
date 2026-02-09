package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class ServiceArea(
    val id: Int,
    val name: String,
    val city: String = "Chicago",
    val state: String = "IL",
    val boundary: GeoJSONPolygon? = null,
    val mapCenter: Coordinates? = null,
    val mapZoom: Double = 14.0,
    val status: ServiceAreaStatus = ServiceAreaStatus.PLANNING,
    val totalBlocks: Int = 0,
    val activeWorkers: Int = 0,
    val createdAt: Instant,
    val updatedAt: Instant
)
