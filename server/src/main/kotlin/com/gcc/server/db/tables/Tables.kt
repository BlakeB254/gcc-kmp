package com.gcc.server.db.tables

import com.gcc.shared.model.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

/**
 * JSON config shared across all JSONB column definitions.
 * ignoreUnknownKeys handles forward-compatibility with gcc-site's Drizzle schema.
 */
private val dbJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

// ---------------------------------------------------------------------------
// Helper: inline jsonb column with kotlinx.serialization
// ---------------------------------------------------------------------------
private inline fun <reified T : Any> Table.jsonbCol(name: String) = jsonb<T>(
    name,
    { dbJson.encodeToString(it) },
    { dbJson.decodeFromString(it) }
)

// ===========================================================================
// Workers
// ===========================================================================
object Workers : Table("workers") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 255)
    val email = varchar("email", 255).uniqueIndex()
    val phone = varchar("phone", 50)
    val passwordHash = varchar("password_hash", 255)
    val emailVerified = bool("email_verified").default(false)
    val address = jsonbCol<WorkerAddress>("address")
    val verification = jsonbCol<WorkerVerification>("verification")
    val stripe = jsonbCol<WorkerStripe>("stripe")
    val stats = jsonbCol<WorkerStats>("stats")
    val earlyAdopterTier = varchar("early_adopter_tier", 50).default("standard")
    val role = varchar("role", 50).default("worker")
    val referralCode = varchar("referral_code", 50).nullable()
    val notifications = jsonbCol<WorkerNotifications>("notifications")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Sponsors
// ===========================================================================
object Sponsors : Table("sponsors") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 255)
    val email = varchar("email", 255).uniqueIndex()
    val phone = varchar("phone", 50).nullable()
    val passwordHash = varchar("password_hash", 255)
    val emailVerified = bool("email_verified").default(false)
    val type = varchar("type", 50).default("individual")
    val business = jsonbCol<SponsorBusiness>("business").nullable()
    val preferences = jsonbCol<SponsorPreferences>("preferences")
    val stats = jsonbCol<SponsorStats>("stats")
    val stripe = jsonbCol<SponsorStripe>("stripe")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Service Areas
// ===========================================================================
object ServiceAreas : Table("service_areas") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 255)
    val city = varchar("city", 100).default("Chicago")
    val state = varchar("state", 10).default("IL")
    val boundary = jsonbCol<GeoJSONPolygon>("boundary").nullable()
    val mapCenter = jsonbCol<Coordinates>("map_center").nullable()
    val mapZoom = double("map_zoom").default(14.0)
    val status = varchar("status", 50).default("planning")
    val totalBlocks = integer("total_blocks").default(0)
    val activeWorkers = integer("active_workers").default(0)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Blocks
// ===========================================================================
object Blocks : Table("blocks") {
    val id = integer("id").autoIncrement()
    val code = varchar("code", 50).uniqueIndex()
    val name = varchar("name", 255)
    val serviceAreaId = integer("service_area_id")
        .references(ServiceAreas.id).nullable()
    val status = varchar("status", 50).default("available")
    val boundary = jsonbCol<GeoJSONPolygon>("boundary").nullable()
    val center = jsonbCol<GeoJSONPoint>("center").nullable()
    val fundingGoal = integer("funding_goal").nullable()
    val fundingCurrent = integer("funding_current").default(0)
    val sponsorId = integer("sponsor_id")
        .references(Sponsors.id).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Routes
// ===========================================================================
object Routes : Table("routes") {
    val id = integer("id").autoIncrement()
    val code = varchar("code", 50).uniqueIndex()
    val displayName = varchar("display_name", 255)
    val serviceAreaId = integer("service_area_id")
        .references(ServiceAreas.id)
    val geometry = jsonbCol<GeoJSONLineString>("geometry").nullable()
    val status = varchar("status", 50).default("draft")
    val distanceMeters = double("distance_meters").nullable()
    val estimatedDurationMinutes = integer("estimated_duration_minutes").nullable()
    val payoutAmount = integer("payout_amount").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Route Checkpoints
// ===========================================================================
object RouteCheckpoints : Table("route_checkpoints") {
    val id = integer("id").autoIncrement()
    val routeId = integer("route_id").references(Routes.id)
    val name = varchar("name", 255)
    val sequenceOrder = integer("sequence_order")
    val lng = double("lng")
    val lat = double("lat")
    val photoRequired = bool("photo_required").default(false)
    val geofenceRadius = double("geofence_radius").default(15.0)
    val type = varchar("type", 50).default("photo_point")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Jobs
// ===========================================================================
object Jobs : Table("jobs") {
    val id = integer("id").autoIncrement()
    val blockId = integer("block_id").references(Blocks.id).nullable()
    val routeId = integer("route_id").references(Routes.id).nullable()
    val workerId = integer("worker_id").references(Workers.id).nullable()
    val status = varchar("status", 50).default("available")
    val claimedAt = timestamp("claimed_at").nullable()
    val startedAt = timestamp("started_at").nullable()
    val completedAt = timestamp("completed_at").nullable()
    val durationMinutes = integer("duration_minutes").nullable()
    val photos = jsonbCol<List<String>>("photos").nullable()
    val gpsPath = jsonbCol<List<List<Double>>>("gps_path").nullable()
    val payoutAmount = integer("payout_amount").nullable()
    val payoutStatus = varchar("payout_status", 50).nullable()
    val rating = double("rating").nullable()
    val reviewNotes = text("review_notes").nullable()
    val fundingSource = varchar("funding_source", 50).nullable()
    val bountyId = integer("bounty_id").nullable()
    val sponsorId = integer("sponsor_id").references(Sponsors.id).nullable()
    val completionReport = jsonbCol<CompletionReport>("completion_report").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Transactions
// ===========================================================================
object Transactions : Table("transactions") {
    val id = integer("id").autoIncrement()
    val sponsorId = integer("sponsor_id").references(Sponsors.id)
    val blockId = integer("block_id").references(Blocks.id).nullable()
    val packageId = integer("package_id").nullable()
    val stripePaymentIntentId = varchar("stripe_payment_intent_id", 255).nullable()
    val stripeChargeId = varchar("stripe_charge_id", 255).nullable()
    val amount = integer("amount")
    val currency = varchar("currency", 10).default("usd")
    val status = varchar("status", 50).default("pending")
    val description = text("description").nullable()
    val metadata = jsonbCol<Map<String, String>>("metadata").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Sponsor Packages
// ===========================================================================
object SponsorPackages : Table("sponsor_packages") {
    val id = integer("id").autoIncrement()
    val sponsorId = integer("sponsor_id").references(Sponsors.id)
    val name = varchar("name", 255)
    val status = varchar("status", 50).default("draft")
    val blocks = jsonbCol<List<Int>>("blocks")
    val blockCount = integer("block_count").default(0)
    val frequency = varchar("frequency", 50).default("one_time")
    val baseAmount = integer("base_amount").default(0)
    val addons = jsonbCol<PackageAddons>("addons")
    val totalAmount = integer("total_amount").default(0)
    val isEnterprise = bool("is_enterprise").default(false)
    val enterpriseTier = varchar("enterprise_tier", 50).nullable()
    val enterpriseDetails = text("enterprise_details").nullable() // raw JSON string
    val stripeInvoiceId = varchar("stripe_invoice_id", 255).nullable()
    val stripeSubscriptionId = varchar("stripe_subscription_id", 255).nullable()
    val paidAt = timestamp("paid_at").nullable()
    val expiresAt = timestamp("expires_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Waitlist Entries
// ===========================================================================
object WaitlistEntries : Table("waitlist_entries") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 255)
    val email = varchar("email", 255).uniqueIndex()
    val phone = varchar("phone", 50).nullable()
    val userType = varchar("user_type", 50)
    val status = varchar("status", 50).default("waitlisted")
    val location = jsonbCol<WaitlistLocation>("location").nullable()
    val ranking = jsonbCol<WaitlistRanking>("ranking")
    val preferences = jsonbCol<WaitlistPreferences>("preferences")
    val earlyAdopterTier = varchar("early_adopter_tier", 50).default("standard")
    val referralCode = varchar("referral_code", 50).nullable()
    val referredBy = varchar("referred_by", 100).nullable()
    val invitedAt = timestamp("invited_at").nullable()
    val convertedAt = timestamp("converted_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Media
// ===========================================================================
object MediaTable : Table("media") {
    val id = integer("id").autoIncrement()
    val url = varchar("url", 2048)
    val filename = varchar("filename", 500)
    val mimeType = varchar("mime_type", 100)
    val alt = varchar("alt", 500).nullable()
    val category = varchar("category", 50).default("other")
    val width = integer("width").nullable()
    val height = integer("height").nullable()
    val sizeBytes = long("size_bytes").nullable()
    val uploadedBy = integer("uploaded_by").references(Workers.id).nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Feedback Tickets
// ===========================================================================
object FeedbackTickets : Table("feedback_tickets") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id")
    val userType = varchar("user_type", 50)
    val category = varchar("category", 50)
    val subject = varchar("subject", 500)
    val description = text("description")
    val status = varchar("status", 50).default("open")
    val priority = varchar("priority", 50).default("medium")
    val assignedTo = varchar("assigned_to", 255).nullable()
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
    val authorId = varchar("author_id", 255)
    val authorName = varchar("author_name", 255)
    val isStaff = bool("is_staff").default(false)
    val message = text("message")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Forum Posts (referenced in migration plan, basic structure)
// ===========================================================================
object ForumPosts : Table("forum_posts") {
    val id = integer("id").autoIncrement()
    val authorId = integer("author_id").references(Workers.id)
    val title = varchar("title", 500)
    val content = text("content")
    val category = varchar("category", 100).nullable()
    val isPinned = bool("is_pinned").default(false)
    val likesCount = integer("likes_count").default(0)
    val repliesCount = integer("replies_count").default(0)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Community Reports
// ===========================================================================
object CommunityReports : Table("community_reports") {
    val id = integer("id").autoIncrement()
    val reporterId = integer("reporter_id").references(Workers.id).nullable()
    val type = varchar("type", 50)
    val lng = double("lng")
    val lat = double("lat")
    val description = text("description").nullable()
    val mediaUrls = jsonbCol<List<String>>("media_urls")
    val status = varchar("status", 50).default("pending")
    val upvotes = integer("upvotes").default(0)
    val verifiedBy = integer("verified_by").references(Workers.id).nullable()
    val resolvedAt = timestamp("resolved_at").nullable()
    val bountyId = integer("bounty_id").nullable()
    val observerRewardPoints = integer("observer_reward_points").default(0)
    val aiAnalysis = jsonbCol<AiAnalysis>("ai_analysis").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

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
    val city = varchar("city", 100).default("Chicago")
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
// Bounty Pledges (stackable — multiple sponsors can pledge on one bounty)
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
