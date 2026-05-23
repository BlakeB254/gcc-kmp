package com.gcc.server.db.tables

import com.gcc.shared.model.*
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

// Server-only tables that don't have Drizzle counterparts (or only partially do).
// Includes wallet ledgers, bounties (NOTE: bounties IS in Drizzle schema; kept here
// for the moment because of the comment-divider grouping in the original Tables.kt),
// bounty pledges, and volunteer cleanups.
// Split out of Tables.kt as part of the M-9 per-domain split.
// ===========================================================================
// Server-only tables (not in Drizzle schema — kept as-is)
// ===========================================================================

// ===========================================================================
// Sponsor Wallets
// ===========================================================================
object SponsorWallets : Table("sponsor_wallets") {
    val id = integer("id").autoIncrement()
    val sponsorId = integer("sponsor_id").references(Sponsors.id).nullable()
    val workerId = integer("worker_id").references(Workers.id).nullable()
    val totalBalance = integer("total_balance").default(0)
    val generalFundBalance = integer("general_fund_balance").default(0)
    val routeFundBalance = integer("route_fund_balance").default(0)
    val bountyFundBalance = integer("bounty_fund_balance").default(0)
    val totalDeposited = integer("total_deposited").default(0)
    val totalSpent = integer("total_spent").default(0)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Wallet Transactions (ledger)
// ===========================================================================
object WalletTransactions : Table("wallet_transactions") {
    val id = integer("id").autoIncrement()
    val walletId = integer("wallet_id").references(SponsorWallets.id)
    val type = varchar("type", 50)
    val fundType = varchar("fund_type", 50)
    val amount = integer("amount")
    val balanceAfter = integer("balance_after")
    val referenceId = integer("reference_id").nullable()
    val referenceType = varchar("reference_type", 50).nullable()
    val description = text("description").nullable()
    val stripePaymentIntentId = varchar("stripe_payment_intent_id", 255).nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Bounties
// ===========================================================================
object Bounties : Table("bounties") {
    val id = integer("id").autoIncrement()
    val posterType = varchar("poster_type", 50)
    val posterId = integer("poster_id")
    val walletId = integer("wallet_id").references(SponsorWallets.id)
    val title = varchar("title", 255)
    val description = text("description").nullable()
    val amount = integer("amount")
    val status = varchar("status", 50).default("open")
    val locationLat = double("location_lat")
    val locationLng = double("location_lng")
    val radiusMeters = double("radius_meters").default(200.0)
    val city = varchar("city", 100).default("")
    val communityArea = varchar("community_area", 255).nullable()
    val neighborhood = varchar("neighborhood", 255).nullable()
    val triggerReportId = integer("trigger_report_id").references(CommunityReports.id).nullable()
    val observerId = integer("observer_id").references(Workers.id).nullable()
    val firstRefusalExpiresAt = timestamp("first_refusal_expires_at").nullable()
    val claimedBy = integer("claimed_by").references(Workers.id).nullable()
    val jobId = integer("job_id").references(Jobs.id).nullable()
    val mediaUrls = jsonbCol<List<String>>("media_urls")
    val completionMediaUrls = jsonbCol<List<String>>("completion_media_urls").nullable()
    val aiAnalysis = jsonbCol<AiAnalysis>("ai_analysis").nullable()
    val expiresAt = timestamp("expires_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Bounty Pledges (stackable)
// ===========================================================================
object BountyPledges : Table("bounty_pledges") {
    val id = integer("id").autoIncrement()
    val bountyId = integer("bounty_id").references(Bounties.id)
    val pledgerType = varchar("pledger_type", 50)
    val pledgerId = integer("pledger_id")
    val walletId = integer("wallet_id").references(SponsorWallets.id)
    val amount = integer("amount")
    val status = varchar("status", 50).default("active")
    val renewalCount = integer("renewal_count").default(0)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Volunteer Cleanups
// ===========================================================================
object VolunteerCleanups : Table("volunteer_cleanups") {
    val id = integer("id").autoIncrement()
    val reportId = integer("report_id").references(CommunityReports.id)
    val workerId = integer("worker_id").references(Workers.id)
    val beforeMediaUrls = jsonbCol<List<String>>("before_media_urls")
    val afterMediaUrls = jsonbCol<List<String>>("after_media_urls")
    val trashHeapMediaUrl = varchar("trash_heap_media_url", 2048).nullable()
    val aiTrashAnalysis = jsonbCol<AiAnalysis>("ai_trash_analysis").nullable()
    val locationLat = double("location_lat")
    val locationLng = double("location_lng")
    val locationVerified = bool("location_verified").default(false)
    val startedAt = timestamp("started_at")
    val completedAt = timestamp("completed_at").nullable()
    val durationMinutes = integer("duration_minutes").nullable()
    val pdfUrl = varchar("pdf_url", 2048).nullable()
    val status = varchar("status", 50).default("in_progress")
    val verificationNotes = text("verification_notes").nullable()
    val pointsAwarded = integer("points_awarded").default(0)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

