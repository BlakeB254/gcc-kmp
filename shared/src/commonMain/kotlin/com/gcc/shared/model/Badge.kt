package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Badge(
    val id: String,
    val category: BadgeCategory,
    val name: String,
    val description: String,
    val iconUrl: String? = null,
    val tier: BadgeTier,
    val requirement: Int,
    val progress: Int = 0,
    val isUnlocked: Boolean = false,
    val unlockedAt: Instant? = null
)

@Serializable
enum class BadgeCategory {
    @SerialName("jobs") JOBS,
    @SerialName("earnings") EARNINGS,
    @SerialName("streak") STREAK,
    @SerialName("community") COMMUNITY,
    @SerialName("early_adopter") EARLY_ADOPTER,
    @SerialName("observe") OBSERVE,
    @SerialName("volunteer") VOLUNTEER,
    @SerialName("first_responder") FIRST_RESPONDER,
    @SerialName("sponsor") SPONSOR
}

@Serializable
enum class BadgeTier {
    @SerialName("bronze") BRONZE,
    @SerialName("silver") SILVER,
    @SerialName("gold") GOLD,
    @SerialName("platinum") PLATINUM,
    @SerialName("diamond") DIAMOND
}
