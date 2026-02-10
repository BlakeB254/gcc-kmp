package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class FeedItemType {
    @SerialName("spot_report") SPOT_REPORT,
    @SerialName("bounty_created") BOUNTY_CREATED,
    @SerialName("bounty_completed") BOUNTY_COMPLETED,
    @SerialName("job_completed") JOB_COMPLETED,
    @SerialName("volunteer_cleanup") VOLUNTEER_CLEANUP,
    @SerialName("badge_unlocked") BADGE_UNLOCKED,
}

@Serializable
data class FeedItem(
    val id: String,
    val type: FeedItemType,
    val actorName: String,
    val actorTier: EarlyAdopterTier,
    val title: String,
    val description: String,
    val mediaUrls: List<String> = emptyList(),
    val completionMediaUrls: List<String> = emptyList(),
    val amount: Int? = null,
    val points: Int? = null,
    val communityArea: String? = null,
    val locationLat: Double? = null,
    val locationLng: Double? = null,
    val timestamp: Instant,
    val metadata: Map<String, String> = emptyMap(),
)
