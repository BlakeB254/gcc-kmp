package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Bounty(
    val id: Int,
    val posterType: PosterType,
    val posterId: Int,
    val walletId: Int,
    val title: String,
    val description: String? = null,
    val amount: Int,
    val status: BountyStatus = BountyStatus.OPEN,
    val locationLat: Double,
    val locationLng: Double,
    val radiusMeters: Double = 200.0,
    val city: String = "",
    val communityArea: String? = null,
    val neighborhood: String? = null,
    val triggerReportId: Int? = null,
    val observerId: Int? = null,
    val firstRefusalExpiresAt: Instant? = null,
    val claimedBy: Int? = null,
    val jobId: Int? = null,
    val mediaUrls: List<String> = emptyList(),
    val completionMediaUrls: List<String>? = null,
    val aiAnalysis: AiAnalysis? = null,
    val expiresAt: Instant? = null,
    val pledgeCount: Int = 1,
    val spotterTipAmount: Int? = null,
    val spotterTipPaidAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)
