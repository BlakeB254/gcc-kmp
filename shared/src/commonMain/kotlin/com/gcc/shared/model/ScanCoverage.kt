package com.gcc.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class ScanCoverageEventRequest(
    val deviceId: String,
    val platform: String, // "ios" | "android"
    val workerId: Int? = null,
    val lat: Double,
    val lng: Double,
    val distanceMeters: Double,
    val scanMode: String, // "route" | "spot"
    val deviceTier: String, // "basic" | "scanner" | "pro_scanner"
    val capturedAt: String,
)

@Serializable
data class ScanCoverageSubmitRequest(
    val events: List<ScanCoverageEventRequest>,
)

@Serializable
data class ScanCoverageSubmitResponse(
    val inserted: Int,
    val success: Boolean,
    val error: String? = null,
)

@Serializable
data class MapCoverageCell(
    val locationCellId: String,
    val lat: Double,
    val lng: Double,
    val state: String, // "fresh" | "stale"
    val lastScannedAt: String,
    val totalDistanceMeters: Double,
)

@Serializable
data class MapCoverageResponse(
    val cells: List<MapCoverageCell>,
    val success: Boolean,
)
