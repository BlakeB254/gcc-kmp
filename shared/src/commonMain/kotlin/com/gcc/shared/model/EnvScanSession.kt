package com.gcc.shared.model

import kotlinx.serialization.Serializable

/**
 * One environment-scan session = one capture, from screen-open to the
 * Complete Scan press. The manifest plus its photos upload to R2
 * (raw/{locationCellId}/{deviceId}/…) where the DGX reconstruction pipeline
 * discovers them — capture, GPS track, facing direction, timing, and the
 * scanned map area travel together as a single unit of work.
 */
@Serializable
data class EnvScanPathSample(
    val lat: Double,
    val lng: Double,
    /** Compass facing at this fix, 0=north; null when unavailable. */
    val headingDegrees: Float? = null,
    /** GPS horizontal accuracy in meters; null when unknown. */
    val accuracyMeters: Double? = null,
    val atMs: Long,
)

@Serializable
data class EnvScanCell(val lat: Double, val lng: Double)

@Serializable
data class EnvScanManifest(
    val sessionId: String,
    val deviceId: String,
    val deviceModel: String,
    val osVersion: String,
    val startedAtMs: Long,
    val completedAtMs: Long,
    /** GPS + facing-direction track sampled during the scan (~1Hz). */
    val path: List<EnvScanPathSample>,
    /** Centers of the coverage cells this session credited. */
    val scannedCells: List<EnvScanCell>,
    val scannedCellSizeMeters: Double,
    /** Geo-tagged trash items observed during the session. */
    val observedItems: List<ObservedSpotItem>,
    /** R2 keys of the photos uploaded alongside this manifest. */
    val photoKeys: List<String>,
    /** The community report this session produced, when submission succeeded. */
    val communityReportId: Int? = null,
    val schemaVersion: Int = 1,
)

@Serializable
data class CaptureUploadTicket(
    val uploadUrl: String,
    val r2Key: String,
    val expiresInSeconds: Int = 300,
)

@Serializable
data class CaptureUploadUrlRequest(
    val locationCellId: String,
    val deviceId: String,
    val capturedAt: String,
    val ext: String,
)
