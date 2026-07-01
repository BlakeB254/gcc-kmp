package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class RouteScan(
    val id: Int,
    val routeId: Int,
    val workerId: Int,
    val deviceType: DeviceType,
    val phase: ScanPhase,
    val coveragePercent: Double = 0.0,
    val scoutStartedAt: Instant,
    val scoutCompletedAt: Instant? = null,
    val cleanStartedAt: Instant? = null,
    val cleanCompletedAt: Instant? = null,
    val startLat: Double,
    val startLng: Double,
    val scoutPathGeoJson: String? = null,
    val cleanPathGeoJson: String? = null,
    val totalTrashVolumeLiters: Double? = null,
    val totalTrashWeightLbs: Double? = null,
    val pointsAwarded: Int = 0,
    val status: ScanStatus = ScanStatus.IN_PROGRESS,
    val hotspots: List<ScanHotspot> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
    val performedByAdminId: Int? = null,
)

@Serializable
data class ScanHotspot(
    val id: Int,
    val scanId: Int,
    val lat: Double,
    val lng: Double,
    val trashTypes: List<SpotTag> = emptyList(),
    val severity: Int = 1,
    val estimatedVolumeLiters: Double? = null,
    val estimatedWeightLbs: Double? = null,
    val confidence: Float = 0f,
    val beforePhotoUrl: String,
    val afterPhotoUrl: String? = null,
    val arAnchorData: String? = null,
    val depthData: String? = null,
    val detectedAt: Instant,
    val cleanedAt: Instant? = null,
    val verificationScore: Float? = null,
    val status: HotspotStatus = HotspotStatus.DETECTED,
)

// Request/Response types:

@Serializable
data class CreateScanRequest(
    val routeId: Int,
    val deviceType: DeviceType,
    val startLat: Double,
    val startLng: Double,
)

@Serializable
data class AdvancePhaseRequest(
    val phase: ScanPhase,
    val coveragePercent: Double? = null,
    val scoutPathGeoJson: String? = null,
    val cleanPathGeoJson: String? = null,
)

@Serializable
data class CreateHotspotRequest(
    val lat: Double,
    val lng: Double,
    val trashTypes: List<SpotTag> = emptyList(),
    val severity: Int = 1,
    val estimatedVolumeLiters: Double? = null,
    val estimatedWeightLbs: Double? = null,
    val confidence: Float = 0f,
    val beforePhotoUrl: String,
    val arAnchorData: String? = null,
    val depthData: String? = null,
)

@Serializable
data class UpdateHotspotRequest(
    val afterPhotoUrl: String? = null,
    val verificationScore: Float? = null,
    val status: HotspotStatus? = null,
)

@Serializable
data class CompleteScanRequest(
    val cleanPathGeoJson: String? = null,
    val totalTrashVolumeLiters: Double? = null,
    val totalTrashWeightLbs: Double? = null,
)

@Serializable
data class CompleteScanResponse(
    val scan: RouteScan,
    val pointsAwarded: Int,
)
