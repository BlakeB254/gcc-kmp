package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class CommunityReport(
    val id: Int,
    val reporterId: Int? = null,
    val reporterName: String? = null,
    val type: CommunityReportType,
    val coordinates: Coordinates,
    val description: String? = null,
    val mediaUrls: List<String> = emptyList(),
    val status: CommunityReportStatus = CommunityReportStatus.PENDING,
    val upvotes: Int = 0,
    val verifiedBy: Int? = null,
    val resolvedAt: Instant? = null,
    val bountyId: Int? = null,
    val observerRewardPoints: Int = 0,
    val aiAnalysis: AiAnalysis? = null,
    val affectedRadiusMeters: Double = 15.0,
    val photoUrl: String? = null,
    // Verification lifecycle
    val lastVerifiedAt: Instant? = null,
    val verificationCount: Int = 0,
    val nextVerificationDue: Instant? = null,
    val needsVerification: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant
)

@Serializable
data class AiAnalysis(
    val estimatedWeightLbs: Double? = null,
    val trashTypes: List<String> = emptyList(),
    val confidence: Double? = null,
    val severity: String? = null,
)
