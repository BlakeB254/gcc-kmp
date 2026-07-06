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
    val quality: String = "grazed", // "grazed" | "close"
)

@Serializable
data class ScanCoverageSubmitRequest(
    val events: List<ScanCoverageEventRequest>,
)

@Serializable
data class RectifyRequest(
    val lat: Double,
    val lng: Double,
)

/** Flat JSON response of POST /api/scans/{id}/rectify (no ApiResponse envelope). */
@Serializable
data class RectifyResponse(
    val success: Boolean,
    val observationId: Int? = null,
    val pointsAwarded: Int = 0,
    val wasRectification: Boolean = false,
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
    val quality: String = "grazed", // best tier on record
)

@Serializable
data class MapCoverageResponse(
    val cells: List<MapCoverageCell>,
    val success: Boolean,
)

/** Undo a device's coverage events in a time window (INDOOR-flip retraction). */
@Serializable
data class RetractCoverageRequest(
    val deviceId: String,
    val fromCapturedAt: String,
    val toCapturedAt: String,
)

@Serializable
data class RetractCoverageResponse(
    val deletedEvents: Int = 0,
    val cellsRecomputed: Int = 0,
    val success: Boolean,
    val error: String? = null,
)
