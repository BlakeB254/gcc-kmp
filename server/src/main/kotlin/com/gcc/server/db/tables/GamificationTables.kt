package com.gcc.server.db.tables

import com.gcc.shared.model.*
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

// Gamification + points-ledger tables. PointsLedger is the immutable audit
// trail for every point earned (verification, cleanup, etc.) and is the
// source of the leaderboard. WorkerBadges and SponsorMilestones are the
// achievement junction tables. Split from Tables.kt as part of M-9.
// ===========================================================================
// Worker Badges (server-side badge tracking)
// ===========================================================================
object WorkerBadges : Table("worker_badges") {
    val id = integer("id").autoIncrement()
    val workerId = integer("worker_id").references(Workers.id)
    val badgeId = varchar("badge_id", 100)
    val category = varchar("category", 50)
    val tier = varchar("tier", 50)
    val name = varchar("name", 255)
    val description = text("description")
    val requirement = integer("requirement")
    val progress = integer("progress").default(0)
    val unlockedAt = timestamp("unlocked_at").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Sponsor Milestones
// ===========================================================================
object SponsorMilestones : Table("sponsor_milestones") {
    val id = integer("id").autoIncrement()
    val sponsorId = integer("sponsor_id").references(Sponsors.id)
    val milestoneType = varchar("milestone_type", 50)
    val tier = varchar("tier", 50)
    val name = varchar("name", 255)
    val description = text("description")
    val requirement = integer("requirement")
    val currentValue = integer("current_value").default(0)
    val achievedAt = timestamp("achieved_at").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Points Ledger (immutable log of all points transactions)
// ===========================================================================
object PointsLedger : Table("points_ledger") {
    val id = integer("id").autoIncrement()
    val workerId = integer("worker_id")
    val action = varchar("action", 50)
    val points = integer("points")
    val referenceType = varchar("reference_type", 50).nullable()
    val referenceId = integer("reference_id").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

