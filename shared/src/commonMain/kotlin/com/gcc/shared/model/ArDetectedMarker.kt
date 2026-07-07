package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class ArDetectedMarkerRequest(
    val lat: Double,
    val lng: Double,
    val label: String,
    val capturedAt: Instant,
    /** Public URL of a best-effort photo captured at marker promotion time
     *  (uploaded before enqueueing — never null bytes persisted here). Null
     *  when no photo was captured or the upload failed. */
    val mediaUrl: String? = null,
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
