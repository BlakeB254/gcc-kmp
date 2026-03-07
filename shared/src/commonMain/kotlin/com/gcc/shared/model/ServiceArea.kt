package com.gcc.shared.model

import com.gcc.shared.constants.GccConstants
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class ServiceArea(
    val id: Int,
    val name: String,
    val city: String = GccConstants.DEFAULT_CITY,
    val state: String = GccConstants.DEFAULT_STATE,
    val boundary: GeoJSONPolygon? = null,
    val mapCenter: Coordinates? = null,
    val mapZoom: Double = 14.0,
    val status: ServiceAreaStatus = ServiceAreaStatus.PLANNING,
    val totalBlocks: Int = 0,
    val activeWorkers: Int = 0,
    val createdAt: Instant,
    val updatedAt: Instant
)
