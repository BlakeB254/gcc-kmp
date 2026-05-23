package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class RouteReleaseStatus {
    @SerialName("draft") DRAFT,
    @SerialName("scheduled") SCHEDULED,
    @SerialName("active") ACTIVE,
    @SerialName("completed") COMPLETED,
    @SerialName("cancelled") CANCELLED,
}

@Serializable
enum class RouteJobOfferStatus {
    @SerialName("pending") PENDING,
    @SerialName("accepted") ACCEPTED,
    @SerialName("declined") DECLINED,
    @SerialName("expired") EXPIRED,
    @SerialName("cancelled") CANCELLED,
}

@Serializable
data class AdminRouteReleaseRequest(
    val name: String,
    val serviceAreaId: Int,
    val routeIds: List<Int>,
    val releaseAt: Instant,
    val offerWindowMinutes: Int = 240,
    val eligibleZipCodes: List<String> = emptyList(),
    val communityName: String? = null,
    val timezone: String = "America/Chicago",
    val daytimeWindowStart: String = "08:00",
    val daytimeWindowEnd: String = "20:00",
    val cleanupStartsAt: Instant? = null,
    val cleanupEndsAt: Instant? = null,
)

@Serializable
data class RouteReleaseBatch(
    val id: Int,
    val serviceAreaId: Int,
    val name: String,
    val communityName: String,
    val eligibleZipCodes: List<String>,
    val status: RouteReleaseStatus,
    val releaseAt: Instant,
    val nextWaveAt: Instant? = null,
    val offerWindowMinutes: Int,
    val timezone: String,
    val daytimeWindowStart: String,
    val daytimeWindowEnd: String,
    val cleanupStartsAt: Instant? = null,
    val cleanupEndsAt: Instant? = null,
    val targetSlots: Int,
    val waveNumber: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
data class RouteOffer(
    val id: Int,
    val batchId: Int,
    val workerId: Int,
    val waveNumber: Int,
    val status: RouteJobOfferStatus,
    val offeredAt: Instant,
    val expiresAt: Instant,
    val respondedAt: Instant? = null,
    val selectedRouteId: Int? = null,
    val selectedJobId: Int? = null,
    val declineReason: String? = null,
    val batch: RouteReleaseBatch? = null,
    val availableRoutes: List<Route> = emptyList(),
)

@Serializable
data class RouteOfferAcceptRequest(
    val routeId: Int,
)

@Serializable
data class RouteOfferDeclineRequest(
    val reason: String? = null,
)

@Serializable
data class RouteReleaseProcessResult(
    val batchId: Int,
    val status: RouteReleaseStatus,
    val waveNumber: Int,
    val expiredOffers: Int,
    val openSlots: Int,
    val activeOffers: Int,
    val offersSent: Int,
    val nextWaveAt: Instant? = null,
)

@Serializable
data class RouteNotificationProcessResult(
    val scheduled: Int = 0,
    val sent: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
)

@Serializable
data class RouteReleaseRunResponse(
    val releases: List<RouteReleaseProcessResult> = emptyList(),
    val notificationsScheduled: RouteNotificationProcessResult = RouteNotificationProcessResult(),
    val notificationsDispatched: RouteNotificationProcessResult = RouteNotificationProcessResult(),
)
