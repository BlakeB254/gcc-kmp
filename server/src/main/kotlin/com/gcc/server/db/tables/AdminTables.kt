package com.gcc.server.db.tables

import com.gcc.shared.model.*
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

// Admin-facing tables: dispute resolution queue, analytics snapshot rollups,
// and the immutable audit trail of spot verifications. Split from Tables.kt
// as part of the M-9 per-domain split.
// ===========================================================================
// Disputes
// ===========================================================================
object Disputes : Table("disputes") {
    val id = integer("id").autoIncrement()
    val jobId = integer("job_id").references(Jobs.id)
    val reporterId = integer("reporter_id")
    val reporterType = varchar("reporter_type", 50)
    val respondentId = integer("respondent_id")
    val respondentType = varchar("respondent_type", 50)
    val type = varchar("type", 50)
    val status = varchar("status", 50).default("open")
    val description = text("description")
    val evidence = jsonbCol<List<String>>("evidence")
    val resolution = varchar("resolution", 50).nullable()
    val resolutionNotes = text("resolution_notes").nullable()
    val resolvedBy = integer("resolved_by").nullable()
    val resolvedAt = timestamp("resolved_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Analytics Snapshots
// ===========================================================================
object AnalyticsSnapshots : Table("analytics_snapshots") {
    val id = integer("id").autoIncrement()
    val date = varchar("date", 20)
    val jobsCompleted = integer("jobs_completed").default(0)
    val jobsCreated = integer("jobs_created").default(0)
    val revenue = integer("revenue").default(0)
    val newWorkers = integer("new_workers").default(0)
    val newSponsors = integer("new_sponsors").default(0)
    val bountiesPosted = integer("bounties_posted").default(0)
    val bountiesClaimed = integer("bounties_claimed").default(0)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Spot Verifications (audit trail of each verification event)
// ===========================================================================
object SpotVerifications : Table("spot_verifications") {
    val id = integer("id").autoIncrement()
    val reportId = integer("report_id")
    val verifierId = integer("verifier_id")
    val verifierName = varchar("verifier_name", 255).nullable()
    val result = varchar("result", 50)
    val photoUrl = varchar("photo_url", 2048).nullable()
    val verifierLat = varchar("verifier_lat", 50).nullable()
    val verifierLng = varchar("verifier_lng", 50).nullable()
    val distanceFromSpotMeters = varchar("distance_from_spot_meters", 50).nullable()
    val pointsAwarded = integer("points_awarded").default(0)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

