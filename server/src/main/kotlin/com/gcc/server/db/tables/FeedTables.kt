package com.gcc.server.db.tables

import com.gcc.shared.model.*
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

// Feed-related tables: comments on feed items, per-photo captions, and the
// per-worker opt-in flags for feed display. Split from Tables.kt as part of
// the M-9 per-domain split.
// ===========================================================================
// Feed Comments
// ===========================================================================
object FeedComments : Table("feed_comments") {
    val id = integer("id").autoIncrement()
    val feedItemId = varchar("feed_item_id", 255)
    val authorId = integer("author_id").references(Workers.id)
    val authorName = varchar("author_name", 255)
    val text = text("text")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Feed Captions
// ===========================================================================
object FeedCaptions : Table("feed_captions") {
    val id = integer("id").autoIncrement()
    val feedItemId = varchar("feed_item_id", 255).uniqueIndex()
    val workerId = integer("worker_id").references(Workers.id)
    val caption = text("caption")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Feed Profile Opt-ins
// ===========================================================================
object FeedProfileOptIns : Table("feed_profile_opt_ins") {
    val id = integer("id").autoIncrement()
    val feedItemId = varchar("feed_item_id", 255).uniqueIndex()
    val workerId = integer("worker_id").references(Workers.id)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
