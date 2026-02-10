package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class SponsorMilestone(
    val id: Int,
    val sponsorId: Int,
    val milestoneType: SponsorMilestoneType,
    val tier: BadgeTier,
    val name: String,
    val description: String,
    val requirement: Int,
    val currentValue: Int = 0,
    val achievedAt: Instant? = null,
    val createdAt: Instant,
)
