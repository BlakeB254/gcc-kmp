@file:UseSerializers(LenientInstantSerializer::class)

package com.gcc.shared.model

import com.gcc.shared.serialization.LenientInstantSerializer
import kotlinx.serialization.UseSerializers

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Sponsor(
    val id: Int,
    val name: String,
    val email: String,
    val phone: String? = null,
    val emailVerified: Boolean = false,
    val type: SponsorType = SponsorType.INDIVIDUAL,
    val business: SponsorBusiness? = null,
    val preferences: SponsorPreferences = SponsorPreferences(),
    val stats: SponsorStats = SponsorStats(),
    val stripe: SponsorStripe = SponsorStripe(),
    val createdAt: Instant,
    val updatedAt: Instant
)

@Serializable
data class SponsorBusiness(
    val name: String? = null,
    val website: String? = null,
    val logoUrl: String? = null,
    val description: String? = null
)

@Serializable
data class SponsorPreferences(
    val frequency: SponsorFrequency = SponsorFrequency.ONE_TIME,
    val neighborhoodPreference: String? = null,
    val wantsUpdates: Boolean = true,
    val isAnonymous: Boolean = false
)

@Serializable
data class SponsorStats(
    val totalDonated: Int = 0,
    val blocksSponsored: Int = 0,
    val lastDonationAt: Instant? = null
)

@Serializable
data class SponsorStripe(
    val customerId: String? = null,
    val subscriptionId: String? = null,
    val defaultPaymentMethod: String? = null
)
