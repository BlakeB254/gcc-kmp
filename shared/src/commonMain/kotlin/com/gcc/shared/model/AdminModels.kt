@file:UseSerializers(LenientInstantSerializer::class)

package com.gcc.shared.model

import com.gcc.shared.serialization.LenientInstantSerializer
import kotlinx.serialization.UseSerializers

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
data class UpdateRouteStatusRequest(val status: RouteStatus)

@Serializable
data class AdminRouteRequest(
    val code: String,
    val displayName: String,
    val serviceAreaId: Int,
    val geometry: GeoJSONLineString? = null,
    val distanceMeters: Double? = null,
    val estimatedDurationMinutes: Int? = null,
    val payoutAmount: Int? = null,
    val imageUrl: String? = null,
    val imageLocationLat: Double? = null,
    val imageLocationLng: Double? = null,
    val status: String? = null,
    val publishAt: String? = null,
    val unpublishAt: String? = null,
)

/**
 * Body for PUT /api/admin/blocks/{blockId}/boundaries.
 * v1 only edits the inner exclusion islands — locationBoundary is
 * intentionally absent so the outer boundary is never touched.
 * innerBoundary = null clears all islands (explicit null is encoded).
 */
@Serializable
data class UpdateBlockBoundariesRequest(
    val innerBoundary: GeoJSONMultiPolygon? = null,
    /** Edited outer ring — the server trims overlapping neighbors on save. */
    val locationBoundary: GeoJSONPolygon? = null,
)

/** Response of POST /api/admin/routes/{routeId}/regenerate-inner-boundary. */
@Serializable
data class RegenerateInnerBoundaryResponse(
    val success: Boolean = false,
    val innerBoundary: GeoJSONMultiPolygon? = null,
    val error: String? = null,
)

/** Response of PUT /api/admin/blocks/{blockId}/boundaries (no data envelope). */
@Serializable
data class UpdateBlockBoundariesResponse(
    val success: Boolean = false,
    val blockId: Int = 0,
    val thinnedNeighbors: List<Int> = emptyList(),
)

@Serializable
data class AdminRouteAssignmentRequest(
    val workerId: Int,
    val isTest: Boolean = false,
)

@Serializable
data class AdminRouteAssignment(
    val jobId: Int,
    val routeId: Int,
    val workerId: Int? = null,
    val workerName: String = "Worker",
    val workerEmail: String = "",
    val status: JobStatus = JobStatus.AVAILABLE,
    val isTest: Boolean = false,
    val payoutAmount: Int = 0,
    val claimedAt: Instant? = null,
    val startedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
data class AddressChangeRequest(
    val workerId: Int,
    val newAddress: WorkerAddress,
    val reason: String? = null,
)

/**
 * One row of the admin Operations drill-in list (GET /api/admin/jobs).
 *
 * Route fields are nullable because a job references a block, and the block's
 * route is reached by join — a block with no route yields no route here.
 */
@Serializable
data class AdminJobSummary(
    val jobId: Int,
    val status: JobStatus = JobStatus.AVAILABLE,
    val workerId: Int? = null,
    val workerName: String? = null,
    val blockId: Int,
    val blockName: String? = null,
    val routeId: Int? = null,
    val routeCode: String? = null,
    val routeName: String? = null,
    val payoutAmount: Double? = null,
    val createdAt: Instant,
    val claimedAt: Instant? = null,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
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
    /** Jobs currently being worked (status = 'in_progress'). */
    val activeJobs: Int,
    /** Jobs published but not yet claimed (status = 'available'). */
    val availableJobs: Int = 0,
    /** Jobs claimed by a worker but not yet started (status = 'claimed'). */
    val claimedJobs: Int = 0,
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
