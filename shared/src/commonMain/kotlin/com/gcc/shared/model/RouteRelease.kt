@file:UseSerializers(LenientInstantSerializer::class)

package com.gcc.shared.model

import com.gcc.shared.serialization.LenientInstantSerializer
import kotlinx.serialization.UseSerializers

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── Release-queue enums (mirror the Postgres enums in site/src/db/schema.ts) ─

@Serializable
enum class ReleaseBatchStatus {
    @SerialName("draft") DRAFT,
    @SerialName("scheduled") SCHEDULED,
    @SerialName("active") ACTIVE,
    @SerialName("completed") COMPLETED,
    @SerialName("cancelled") CANCELLED,
}

@Serializable
enum class ReleaseRouteStatus {
    @SerialName("open") OPEN,
    @SerialName("claimed") CLAIMED,
    @SerialName("cancelled") CANCELLED,
}

@Serializable
enum class RouteOfferStatus {
    @SerialName("pending") PENDING,
    @SerialName("accepted") ACCEPTED,
    @SerialName("declined") DECLINED,
    @SerialName("expired") EXPIRED,
    @SerialName("cancelled") CANCELLED,
}

// ─── DTOs for GET /api/admin/route-releases (list + detail) ──────────────────

/**
 * One release batch as returned by GET /api/admin/route-releases. Count
 * fields (routeCount/openSlots/pendingOffers) are list-view rollups the
 * server may include; they default to 0 when absent.
 */
@Serializable
data class AdminReleaseBatchDto(
    val id: Int,
    val serviceAreaId: Int,
    val name: String? = null,
    val communityName: String? = null,
    val status: ReleaseBatchStatus = ReleaseBatchStatus.DRAFT,
    val releaseAt: Instant? = null,
    val nextWaveAt: Instant? = null,
    val offerWindowMinutes: Int = 240,
    val targetSlots: Int? = null,
    val waveNumber: Int = 0,
    val routeCount: Int = 0,
    val openSlots: Int = 0,
    val pendingOffers: Int = 0,
    val createdAt: Instant? = null,
)

/** One route slot inside a batch (route_release_routes row + position). */
@Serializable
data class AdminReleaseRouteDto(
    val id: Int,
    val routeId: Int,
    val jobId: Int,
    val position: Int = 0,
    val status: ReleaseRouteStatus = ReleaseRouteStatus.OPEN,
    /** Joined display fields the server may include for list rendering. */
    val routeName: String? = null,
    val routeCode: String? = null,
)

/** One offer sent for a batch (route_job_offers row). */
@Serializable
data class AdminRouteOfferDto(
    val id: Int,
    val workerId: Int,
    val workerName: String? = null,
    val status: RouteOfferStatus = RouteOfferStatus.PENDING,
    val waveNumber: Int = 0,
    val expiresAt: Instant? = null,
    val selectedRouteId: Int? = null,
)

/** Response of GET /api/admin/route-releases/{id}. */
@Serializable
data class AdminReleaseBatchDetailDto(
    val batch: AdminReleaseBatchDto,
    val routes: List<AdminReleaseRouteDto> = emptyList(),
    val offers: List<AdminRouteOfferDto> = emptyList(),
)

// ─── Requests ────────────────────────────────────────────────────────────────

/** Body of PATCH /api/admin/route-releases/{id} — edit fields, or action=cancel. */
@Serializable
data class UpdateReleaseBatchRequest(
    val name: String? = null,
    val releaseAt: Instant? = null,
    val offerWindowMinutes: Int? = null,
    /** Set to "cancel" to cancel the batch (cancels open slots + pending offers). */
    val action: String? = null,
)

/** Body of PUT /api/admin/route-releases/{id}/order — full new route order. */
@Serializable
data class AdminRouteOrderRequest(
    val routeIds: List<Int>,
)

/** Response of PUT /api/admin/route-releases/{id}/order. The admin UI refetches
 *  the batch detail after reordering, so only a success flag is consumed. */
@Serializable
data class AdminRouteOrderResponse(
    val success: Boolean = false,
    val routes: List<AdminReleaseRouteDto> = emptyList(),
)
