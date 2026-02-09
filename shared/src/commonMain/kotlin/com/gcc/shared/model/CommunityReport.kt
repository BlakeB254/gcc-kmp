package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class CommunityReport(
    val id: Int,
    val reporterId: Int? = null,
    val type: CommunityReportType,
    val coordinates: Coordinates,
    val description: String? = null,
    val mediaUrls: List<String> = emptyList(),
    val status: CommunityReportStatus = CommunityReportStatus.PENDING,
    val upvotes: Int = 0,
    val verifiedBy: Int? = null,
    val resolvedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant
)
