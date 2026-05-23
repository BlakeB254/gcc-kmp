package com.gcc.server.db.tables

import com.gcc.shared.model.*
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

// Community-data tables:
//   CommunityReports     — user-submitted sightings + volunteer cleanups
//   CommunityReportThanks — "thank you" interactions on those reports
//   MaterialDonations    — physical-supply donation pledges
// All three mirror Drizzle schema entries.
// Split out of Tables.kt as part of the M-9 per-domain split.
// ===========================================================================
// Community Reports
// ===========================================================================
object CommunityReports : Table("community_reports") {
    val id = integer("id").autoIncrement()

    // Reporter (polymorphic, nullable = anonymous)
    val reporterType = varchar("reporter_type", 50).nullable()
    val reporterId = integer("reporter_id").nullable()
    val reporterName = varchar("reporter_name", 255).nullable()

    // Report type and details
    val type = varchar("type", 50)
    val title = varchar("title", 255).nullable()
    val description = text("description").nullable()

    // Location
    val coordinates = jsonbCol<List<Double>>("coordinates")
    val locationAddress = varchar("location_address", 500).nullable()
    val locationNeighborhood = varchar("location_neighborhood", 255).nullable()
    val serviceAreaId = integer("service_area_id").nullable()
    val nearBlockId = integer("near_block_id").nullable()

    // Media evidence
    val mediaIds = jsonbCol<kotlinx.serialization.json.JsonElement>("media_ids").nullable()
    val beforePhotos = jsonbCol<kotlinx.serialization.json.JsonElement>("before_photos").nullable()
    val afterPhotos = jsonbCol<kotlinx.serialization.json.JsonElement>("after_photos").nullable()

    // Cleanup details
    val cleanupDurationMinutes = integer("cleanup_duration_minutes").nullable()
    val cleanupBagsCollected = integer("cleanup_bags_collected").nullable()
    val cleanupNotes = text("cleanup_notes").nullable()

    // Status and moderation
    val status = varchar("status", 50).default("pending")
    val reviewedAt = timestamp("reviewed_at").nullable()
    val reviewedById = integer("reviewed_by_id").nullable()
    val reviewNotes = text("review_notes").nullable()

    // Map visibility
    val showOnMap = bool("show_on_map").default(true)
    val mapExpiresAt = timestamp("map_expires_at").nullable()

    // Resolution
    val resolvedByJobId = integer("resolved_by_job_id").nullable()

    // Engagement
    val viewCount = integer("view_count").default(0)
    val thankCount = integer("thank_count").default(0)

    // Timestamps
    val capturedAt = timestamp("captured_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Community Report Thanks
// ===========================================================================
object CommunityReportThanks : Table("community_report_thanks") {
    val id = integer("id").autoIncrement()
    val reportId = integer("report_id").references(CommunityReports.id)

    // User who thanked
    val userType = varchar("user_type", 50)
    val userId = integer("user_id")

    // Timestamp
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Material Donations
// ===========================================================================
object MaterialDonations : Table("material_donations") {
    val id = integer("id").autoIncrement()

    // Donor info
    val name = varchar("name", 100)
    val email = varchar("email", 255)
    val phone = varchar("phone", 20).nullable()
    val company = varchar("company", 100).nullable()

    // Donation details
    val items = text("items")
    val itemCategories = jsonbCol<kotlinx.serialization.json.JsonElement>("item_categories").nullable()
    val quantity = varchar("quantity", 100).nullable()
    val condition = varchar("condition", 50).nullable()

    // Logistics
    val preferredMethod = varchar("preferred_method", 20).nullable()
    val address = text("address").nullable()
    val availableDates = text("available_dates").nullable()
    val notes = text("notes").nullable()

    // Admin tracking
    val status = varchar("status", 50).default("pending")
    val adminNotes = text("admin_notes").nullable()
    val scheduledDate = timestamp("scheduled_date").nullable()
    val receivedDate = timestamp("received_date").nullable()
    val estimatedValue = varchar("estimated_value", 50).nullable()

    // Timestamps
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

