@file:UseSerializers(LenientInstantSerializer::class)

package com.gcc.shared.model

import com.gcc.shared.serialization.LenientInstantSerializer
import kotlinx.serialization.UseSerializers

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class FeedbackTicket(
    val id: Int,
    val userId: Int,
    val userType: UserType,
    val category: FeedbackCategory,
    val subject: String,
    val description: String,
    val status: FeedbackStatus = FeedbackStatus.OPEN,
    val priority: FeedbackPriority = FeedbackPriority.MEDIUM,
    val assignedTo: String? = null,
    val replies: List<FeedbackReply> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant
)

@Serializable
data class FeedbackReply(
    val id: Int,
    val ticketId: Int,
    val authorId: String,
    val authorName: String,
    val isStaff: Boolean = false,
    val message: String,
    val createdAt: Instant
)
