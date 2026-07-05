package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class ArDetectedMarkerRequest(
    val lat: Double,
    val lng: Double,
    val label: String,
    val capturedAt: Instant,
)

@Serializable
data class ArDetectedMarkerBatchRequest(
    val markers: List<ArDetectedMarkerRequest>,
)

@Serializable
data class ArDetectedMarkerBatchResponse(
    val inserted: Int,
    val success: Boolean,
)
