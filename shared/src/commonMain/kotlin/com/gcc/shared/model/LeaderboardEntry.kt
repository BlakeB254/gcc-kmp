package com.gcc.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class LeaderboardEntry(
    val rank: Int,
    val workerId: Int,
    val name: String,
    val jobsCompleted: Int,
    val totalEarnings: Int,
    val averageRating: Double?,
    val earlyAdopterTier: EarlyAdopterTier = EarlyAdopterTier.STANDARD,
    val badgeCount: Int = 0,
)
