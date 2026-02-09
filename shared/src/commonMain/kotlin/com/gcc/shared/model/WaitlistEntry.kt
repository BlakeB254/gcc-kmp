package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class WaitlistEntry(
    val id: Int,
    val name: String,
    val email: String,
    val phone: String? = null,
    val userType: UserType,
    val status: WaitlistStatus = WaitlistStatus.WAITLISTED,
    val location: WaitlistLocation? = null,
    val ranking: WaitlistRanking = WaitlistRanking(),
    val preferences: WaitlistPreferences = WaitlistPreferences(),
    val earlyAdopterTier: EarlyAdopterTier = EarlyAdopterTier.STANDARD,
    val referralCode: String? = null,
    val referredBy: String? = null,
    val invitedAt: Instant? = null,
    val convertedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant
)

@Serializable
data class WaitlistLocation(
    val zip: String? = null,
    val neighborhood: String? = null,
    val city: String = "Chicago",
    val state: String = "IL"
)

@Serializable
data class WaitlistRanking(
    val position: Int? = null,
    val score: Int = 0,
    val referralCount: Int = 0
)

@Serializable
data class WaitlistPreferences(
    val wantsEarlyAccess: Boolean = true,
    val interestedInBeta: Boolean = false,
    val preferredContact: String = "email"
)
