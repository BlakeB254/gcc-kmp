package com.gcc.shared.model

import com.gcc.shared.constants.GccConstants
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

// ─── Admin Response Models ──────────────────────────────────────────

@Serializable
data class DocumentMedia(
    val url: String? = null,
    val filename: String? = null,
    val mimeType: String? = null,
)

@Serializable
data class WorkerDocuments(
    val governmentId: DocumentMedia? = null,
    val proofOfAddress: DocumentMedia? = null,
)

// ─── Admin Request Models ───────────────────────────────────────────

@Serializable
data class VerifyWorkerRequest(
    val status: VerificationStatus,
    val governmentIdStatus: DocumentStatus? = null,
    val proofOfAddressStatus: DocumentStatus? = null,
    val reviewNotes: String? = null,
)

@Serializable
data class UpdateWorkerRoleRequest(
    val role: UserRole,
)

@Serializable
data class BulkActionRequest(
    val workerIds: List<Int>,
    val action: BulkAction,
    val reason: String? = null,
)

@Serializable
data class ForceJobStatusRequest(
    val status: JobStatus,
    val reason: String,
)

@Serializable
data class AdminRouteRequest(
    val code: String,
    val displayName: String,
    val serviceAreaId: Int,
    val distanceMeters: Double? = null,
    val estimatedDurationMinutes: Int? = null,
    val payoutAmount: Int? = null,
    val checkpoints: List<AdminCheckpointRequest> = emptyList(),
    val imageUrl: String? = null,
    val imageLocationLat: Double? = null,
    val imageLocationLng: Double? = null,
    val status: String? = null,
    val publishAt: String? = null,
    val unpublishAt: String? = null,
)

@Serializable
data class AdminCheckpointRequest(
    val name: String,
    val lat: Double,
    val lng: Double,
    val sequenceOrder: Int,
    val photoRequired: Boolean = false,
    val geofenceRadius: Double = 15.0,
    val type: String = "photo_point",
    val instructions: String? = null,
    val cameraMode: String? = null,
    val bountyPrompts: List<String> = emptyList(),
    val imageUrl: String? = null,
)

@Serializable
data class AdminBlockRequest(
    val code: String,
    val name: String,
    val serviceAreaId: Int? = null,
    val fundingGoal: Int? = null,
    val sponsorId: Int? = null,
    val displayImage: String? = null,
    val imageLocationLat: Double? = null,
    val imageLocationLng: Double? = null,
)

@Serializable
data class AdminServiceAreaRequest(
    val name: String,
    val city: String = GccConstants.DEFAULT_CITY,
    val state: String = GccConstants.DEFAULT_STATE,
    val boundary: GeoJSONPolygon? = null,
    val mapCenter: Coordinates? = null,
    val mapZoom: Double = 14.0,
    val status: String = "planning",
)

@Serializable
data class AddressChangeRequest(
    val workerId: Int,
    val newAddress: WorkerAddress,
    val reason: String? = null,
)

// ─── Admin Response Models ──────────────────────────────────────────

@Serializable
data class AdminStatsResponse(
    val totalWorkers: Int,
    val verifiedWorkers: Int,
    val pendingVerifications: Int,
    val totalSponsors: Int,
    val totalJobs: Int,
    val completedJobs: Int,
    val activeJobs: Int,
    val totalRevenue: Int,
    val totalRoutes: Int,
    val totalBlocks: Int,
    val totalSpots: Int = 0,
    val activeSpots: Int = 0,
    val totalBounties: Int = 0,
    val activeBounties: Int = 0,
    val totalDisputes: Int,
    val openDisputes: Int,
)

@Serializable
data class AdminDailyStats(
    val date: String,
    val jobsCompleted: Int,
    val jobsCreated: Int,
    val revenue: Int,
    val newWorkers: Int,
    val newSponsors: Int,
    val bountiesPosted: Int,
    val bountiesClaimed: Int,
)

@Serializable
data class RevenueByArea(
    val serviceAreaId: Int,
    val serviceAreaName: String,
    val totalRevenue: Int,
    val jobCount: Int,
    val workerCount: Int,
)

@Serializable
data class WorkerMetrics(
    val workerId: Int,
    val workerName: String,
    val jobsCompleted: Int,
    val totalEarnings: Int,
    val averageRating: Double?,
    val volunteersCompleted: Int,
    val badgesEarned: Int,
)

@Serializable
data class SponsorMetrics(
    val sponsorId: Int,
    val sponsorName: String,
    val totalFunded: Int,
    val blocksSponsored: Int,
    val bountiesPosted: Int,
    val walletBalance: Int,
)

@Serializable
data class AdminActivityItem(
    val id: Int,
    val type: AdminActivityType,
    val description: String,
    val actorName: String? = null,
    val timestamp: Instant,
)

// ─── Bounty Analytics Models ────────────────────────────────────────

@Serializable
data class BountyAnalyticsStats(
    val totalPosted: Int = 0,
    val totalValueCents: Long = 0,
    val avgPriceCents: Int = 0,
    val completionRate: Double = 0.0,
    val avgTimeToClaimMinutes: Int = 0,
    val totalSpotterTipsCents: Long = 0,
)

@Serializable
data class BountyTimeseriesPoint(
    val date: String = "",
    val posted: Int = 0,
    val claimed: Int = 0,
    val completed: Int = 0,
    val expired: Int = 0,
    val totalValueCents: Long = 0,
)

@Serializable
data class BountyLocationStats(
    val communityAreaId: Int = 0,
    val communityAreaName: String = "",
    val bountyCount: Int = 0,
    val totalValueCents: Long = 0,
    val avgPriceCents: Int = 0,
    val completionRate: Double = 0.0,
    val avgTimeToClaimMinutes: Int = 0,
)

@Serializable
data class BountyPosterStats(
    val userId: Int = 0,
    val userName: String = "",
    val posterType: PosterType = PosterType.WORKER,
    val bountiesPosted: Int = 0,
    val totalValueCents: Long = 0,
    val completionRate: Double = 0.0,
)

