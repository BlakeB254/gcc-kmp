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
    val quality: String = "grazed", // "seen" | "grazed" | "close"
    // Ground surface under the camera when this event was captured —
    // "grass" | "pavement" | "street" | "dirt" (SurfaceType.wire), null on
    // old clients or when no surface classified yet. Additive: the server
    // stores it as reconstruction/backdrop context and ignores unknown values.
    val surface: String? = null,
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

/** POST /api/observations/{id}/flag-media — privacy flag-down for a
 *  public-map litter photo (person/animal/interior/no visible trash). */
@Serializable
data class FlagObservationMediaRequest(
    val reason: String? = null,
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
    val quality: String = "grazed", // best tier on record: "seen" | "grazed" | "close"
    // Rescan priority, 0..1 — ageFactor x qualityBoost, computed server-side
    // at read time (no schema migration). Defaulted so legacy/cached
    // responses without it still decode; 0.0 renders as "no urgency".
    val priority: Double = 0.0,
    // Scan-found litter in this cell (current + dirty + domain=litter), rolled
    // up server-side at read time like [priority]. Drives the problem-area
    // blobs: [litterCount] decides IF a cell is a problem, [litterWeightGrams]
    // decides HOW BAD. Both defaulted so a cached/pre-deploy response still
    // decodes and simply renders no problem area.
    val litterCount: Int = 0,
    val litterWeightGrams: Double = 0.0,
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
