package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class WalletTransaction(
    val id: Int,
    val walletId: Int,
    val type: WalletTransactionType,
    val fundType: FundType,
    val amount: Int,
    val balanceAfter: Int,
    val referenceId: Int? = null,
    val referenceType: WalletReferenceType? = null,
    val description: String? = null,
    val stripePaymentIntentId: String? = null,
    val createdAt: Instant? = null,
)
