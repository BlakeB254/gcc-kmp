@file:UseSerializers(LenientInstantSerializer::class)

package com.gcc.shared.model

import com.gcc.shared.serialization.LenientInstantSerializer
import kotlinx.serialization.UseSerializers

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
    @SerialName("leaderboard_change") LEADERBOARD_CHANGE,
    @SerialName("article") ARTICLE,
}

@Serializable
data class FeedItem(
    val id: String,
    val type: FeedItemType,
    val actorName: String,
    val actorTier: EarlyAdopterTier = EarlyAdopterTier.STANDARD,
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
    // Social features
    val caption: String? = null,
    val commentCount: Int = 0,
    val actorId: Int? = null,
    val actorProfileVisible: Boolean = false,
)

@Serializable
data class FeedComment(
    val id: String,
    val feedItemId: String,
    val authorName: String,
    val authorTier: EarlyAdopterTier = EarlyAdopterTier.STANDARD,
    val text: String,
    val timestamp: Instant,
)
