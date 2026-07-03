@file:UseSerializers(LenientInstantSerializer::class)

package com.gcc.shared.model

import com.gcc.shared.serialization.LenientInstantSerializer
import kotlinx.serialization.UseSerializers

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Transaction(
    val id: Int,
    val sponsorId: Int,
    val blockId: Int? = null,
    val packageId: Int? = null,
    val stripePaymentIntentId: String? = null,
    val stripeChargeId: String? = null,
    val amount: Int,
    val currency: String = "usd",
    val status: TransactionStatus = TransactionStatus.PENDING,
    val description: String? = null,
    val metadata: Map<String, String>? = null,
    val createdAt: Instant,
    val updatedAt: Instant
)
