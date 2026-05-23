package com.gcc.server.db.tables

import com.gcc.shared.model.*
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

// User engagement tables: feedback tickets and forum posts/replies/likes.
// Split out of Tables.kt as part of the M-9 per-domain split.
// ===========================================================================
// Feedback Tickets
// ===========================================================================
object FeedbackTickets : Table("feedback_tickets") {
    val id = integer("id").autoIncrement()

    // User who submitted (polymorphic)
    val userType = varchar("user_type", 50)
    val userId = integer("user_id")
    val userEmail = varchar("user_email", 255)
    val userName = varchar("user_name", 255)

    // Ticket details
    val category = varchar("category", 50)
    val subject = varchar("subject", 255)
    val description = text("description")

    // Status tracking
    val status = varchar("status", 50).default("open")
    val priority = varchar("priority", 50).default("medium")

    // Assignment
    val assignedTo = varchar("assigned_to", 255).nullable()

    // Resolution
    val resolutionNotes = text("resolution_notes").nullable()
    val resolvedAt = timestamp("resolved_at").nullable()

    // Attachments
    val attachmentIds = jsonbCol<kotlinx.serialization.json.JsonElement>("attachment_ids").nullable()

    // Timestamps
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Feedback Replies
// ===========================================================================
object FeedbackReplies : Table("feedback_replies") {
    val id = integer("id").autoIncrement()
    val ticketId = integer("ticket_id").references(FeedbackTickets.id)

    // Who replied
    val isAdminReply = bool("is_admin_reply").default(false)
    val replierName = varchar("replier_name", 255)
    val replierEmail = varchar("replier_email", 255).nullable()

    // Reply content
    val message = text("message")

    // Internal notes (admin only)
    val isInternal = bool("is_internal").default(false)

    // Timestamps
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Forum Posts
// ===========================================================================
object ForumPosts : Table("forum_posts") {
    val id = integer("id").autoIncrement()

    // Author (polymorphic)
    val authorType = varchar("author_type", 50)
    val authorId = integer("author_id")
    val authorName = varchar("author_name", 255)

    // Post content
    val category = varchar("category", 50).default("general")
    val title = varchar("title", 255)
    val content = text("content")

    // Status and moderation
    val status = varchar("status", 50).default("active")
    val isPinned = bool("is_pinned").default(false)
    val isAnnouncement = bool("is_announcement").default(false)

    // Engagement stats
    val viewCount = integer("view_count").default(0)
    val replyCount = integer("reply_count").default(0)
    val likeCount = integer("like_count").default(0)

    // Last activity
    val lastActivityAt = timestamp("last_activity_at").nullable()

    // Timestamps
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Forum Replies
// ===========================================================================
object ForumReplies : Table("forum_replies") {
    val id = integer("id").autoIncrement()
    val postId = integer("post_id").references(ForumPosts.id)

    // Author (polymorphic)
    val authorType = varchar("author_type", 50)
    val authorId = integer("author_id")
    val authorName = varchar("author_name", 255)

    // Parent reply for threading
    val parentReplyId = integer("parent_reply_id").nullable()

    // Reply content
    val content = text("content")

    // Moderation
    val isHidden = bool("is_hidden").default(false)

    // Engagement
    val likeCount = integer("like_count").default(0)

    // Timestamps
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Forum Likes
// ===========================================================================
object ForumLikes : Table("forum_likes") {
    val id = integer("id").autoIncrement()

    // User who liked
    val userType = varchar("user_type", 50)
    val userId = integer("user_id")

    // What they liked
    val postId = integer("post_id").nullable()
    val replyId = integer("reply_id").nullable()

    // Timestamp
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

