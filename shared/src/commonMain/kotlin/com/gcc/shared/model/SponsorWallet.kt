package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class SponsorWallet(
    val id: Int,
    val sponsorId: Int? = null,
    val workerId: Int? = null,
    val totalBalance: Int = 0,
    val generalFundBalance: Int = 0,
    val routeFundBalance: Int = 0,
    val bountyFundBalance: Int = 0,
    val totalDeposited: Int = 0,
    val totalSpent: Int = 0,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)
