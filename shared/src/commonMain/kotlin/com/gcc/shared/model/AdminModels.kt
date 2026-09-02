@file:UseSerializers(LenientInstantSerializer::class)

package com.gcc.shared.model

import com.gcc.shared.serialization.LenientInstantSerializer
import kotlinx.serialization.UseSerializers

import kotlinx.datetime.Instant
import kotlinx.serialization.EncodeDefault
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

/** POST /api/admin/jobs/[id]/reassign — move a job (and its wallet credit) to the worker who really did it. */
@Serializable
data class ReassignJobRequest(
    val toWorkerId: Int,
)

@Serializable
data class ReassignJobResponse(
    val ok: Boolean = false,
    val jobId: Int = 0,
    val fromWorkerId: Int = 0,
    val toWorkerId: Int = 0,
    /** Cents moved between wallets (0 when the job hadn't been credited yet). */
    val movedCents: Int = 0,
    val creditMoved: Boolean = false,
)

@Serializable
data class UpdateRouteStatusRequest(val status: RouteStatus)

/**
 * Body of POST /api/admin/routes/{id}/publish|unpublish|archive (route-admin
 * v2 explicit verbs). All fields optional — publish may carry a claimStatus
 * and/or scheduled publish/unpublish times.
 */
@Serializable
data class AdminRoutePublishRequest(
    // Omit these from the wire when unset. GccJson has encodeDefaults=true, so
    // without @EncodeDefault(NEVER) an unset field serializes as an explicit
    // `null`. The publish endpoint treats a present-but-null claimStatus as a
    // supplied value and rejects it (400), so a plain "publish this route" call
    // — which carries no claimStatus — would always fail. NEVER keeps the JSON
    // to only the fields the admin actually set.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val claimStatus: ClaimStatus? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val publishAt: Instant? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val unpublishAt: Instant? = null,
)

/** Body of POST /api/admin/routes/{id}/claim-status. */
@Serializable
data class UpdateRouteClaimStatusRequest(val claimStatus: ClaimStatus)

/**
 * Summary returned by DELETE /api/admin/routes/{id}. On HTTP 409 the counts
 * explain the refusal (activeJobs/pendingOffers > 0); on HTTP 200 they
 * report what the delete cancelled.
 */
@Serializable
data class RouteDeleteSummary(
    val activeJobs: Int = 0,
    val pendingOffers: Int = 0,
    val cancelledReleaseRoutes: Int = 0,
    val cancelledOffers: Int = 0,
    val detachedJobs: Int = 0,
)

/** Typed outcome of DELETE /api/admin/routes/{id} — 409 surfaces as data, not an exception. */
sealed interface RouteDeleteResult {
    data class Deleted(val summary: RouteDeleteSummary) : RouteDeleteResult
    data class Conflict(val summary: RouteDeleteSummary) : RouteDeleteResult
}

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
