package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

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
)

@Serializable
data class AdminBlockRequest(
    val code: String,
    val name: String,
    val serviceAreaId: Int? = null,
    val fundingGoal: Int? = null,
    val sponsorId: Int? = null,
)

@Serializable
data class AdminServiceAreaRequest(
    val name: String,
    val city: String = "Chicago",
    val state: String = "IL",
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
    val totalBounties: Int,
    val activeBounties: Int,
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

// ─── Enums ──────────────────────────────────────────────────────────

@Serializable
enum class UserRole { WORKER, ADMIN, SUPER_ADMIN }

@Serializable
enum class BulkAction { VERIFY, SUSPEND, ACTIVATE, DELETE }

@Serializable
enum class AdminActivityType {
    WORKER_REGISTERED,
    WORKER_VERIFIED,
    JOB_COMPLETED,
    BOUNTY_POSTED,
    BOUNTY_CLAIMED,
    BOUNTY_COMPLETED,
    DISPUTE_OPENED,
    DISPUTE_RESOLVED,
    SPONSOR_REGISTERED,
    BLOCK_FUNDED,
    ROUTE_PUBLISHED,
}
