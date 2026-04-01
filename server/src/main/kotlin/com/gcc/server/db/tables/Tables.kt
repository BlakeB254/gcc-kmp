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

    // Email verification
    val emailVerified = bool("email_verified").default(false)
    val emailVerificationToken = varchar("email_verification_token", 255).nullable()
    val emailVerificationExpires = timestamp("email_verification_expires").nullable()
    val emailVerifiedAt = timestamp("email_verified_at").nullable()

    // Address (flat columns)
    val addressStreet = varchar("address_street", 255)
    val addressCity = varchar("address_city", 255).default("")
    val addressState = varchar("address_state", 50).default("")
    val addressZip = varchar("address_zip", 20)
    val addressNeighborhood = varchar("address_neighborhood", 255).nullable()
    val addressIsNorthLawndale = bool("address_is_north_lawndale").nullable()

    // Pending address change (flat columns)
    val pendingAddressChangeStreet = varchar("pending_address_change_street", 255).nullable()
    val pendingAddressChangeCity = varchar("pending_address_change_city", 255).nullable()
    val pendingAddressChangeState = varchar("pending_address_change_state", 50).nullable()
    val pendingAddressChangeZip = varchar("pending_address_change_zip", 20).nullable()
    val pendingAddressChangeProofOfAddressId = integer("pending_address_change_proof_of_address_id").nullable()
    val pendingAddressChangeRequestedAt = timestamp("pending_address_change_requested_at").nullable()
    val pendingAddressChangeStatus = varchar("pending_address_change_status", 50).default("none")
    val pendingAddressChangeReviewNotes = varchar("pending_address_change_review_notes", 1000).nullable()
    val lastAddressChangeAt = timestamp("last_address_change_at").nullable()

    // Verification documents (flat columns)
    val verificationGovernmentIdId = integer("verification_government_id_id").nullable()
    val verificationGovernmentIdStatus = varchar("verification_government_id_status", 50).default("pending")
    val verificationGovernmentIdNotes = varchar("verification_government_id_notes", 1000).nullable()
    val verificationProofOfAddressId = integer("verification_proof_of_address_id").nullable()
    val verificationProofOfAddressStatus = varchar("verification_proof_of_address_status", 50).default("pending")
    val verificationProofOfAddressNotes = varchar("verification_proof_of_address_notes", 1000).nullable()
    val verificationStatus = varchar("verification_status", 50).default("pending")
    val verificationReviewNotes = varchar("verification_review_notes", 1000).nullable()
    val verificationResubmissionCount = varchar("verification_resubmission_count", 50).default("0")
    val verificationLastResubmittedAt = timestamp("verification_last_resubmitted_at").nullable()
    val verificationReviewedAt = timestamp("verification_reviewed_at").nullable()
    val verificationReviewedById = integer("verification_reviewed_by_id").nullable()

    // Stripe (flat columns)
    val stripeAccountId = varchar("stripe_account_id", 255).nullable()
    val stripeOnboardingStatus = varchar("stripe_onboarding_status", 50).default("not_started")
    val stripeInstantPayoutsEnabled = bool("stripe_instant_payouts_enabled").default(false)
    val stripeDefaultPayoutMethod = varchar("stripe_default_payout_method", 50).default("standard")

    // Stats (flat columns — numeric stored as varchar)
    val statsJobsCompleted = varchar("stats_jobs_completed", 50).default("0")
    val statsTotalEarnings = varchar("stats_total_earnings", 50).default("0")
    val statsAverageRating = varchar("stats_average_rating", 50).nullable()
    val statsLastJobAt = timestamp("stats_last_job_at").nullable()

    // Movement rankings
    val signupTimestamp = timestamp("signup_timestamp").nullable()
    val signupRankingsGlobal = varchar("signup_rankings_global", 50).nullable()
    val signupRankingsCountry = varchar("signup_rankings_country", 50).nullable()
    val signupRankingsState = varchar("signup_rankings_state", 50).nullable()
    val signupRankingsCity = varchar("signup_rankings_city", 50).nullable()
    val signupRankingsNeighborhood = varchar("signup_rankings_neighborhood", 50).nullable()
    val earlyAdopterTier = varchar("early_adopter_tier", 50).default("standard")
    val referralCode = varchar("referral_code", 50).uniqueIndex().nullable()
    val referredBy = varchar("referred_by", 255).nullable()
    val convertedFromWaitlistId = integer("converted_from_waitlist_id").nullable()

    // Notifications (flat columns)
    val notificationsPushEnabled = bool("notifications_push_enabled").default(false)
    val notificationsPushSubscription = jsonbCol<kotlinx.serialization.json.JsonElement>("notifications_push_subscription").nullable()
    val notificationsSmsEnabled = bool("notifications_sms_enabled").default(true)
    val notificationsEmailEnabled = bool("notifications_email_enabled").default(true)

    // Auth (Payload style)
    val salt = varchar("salt", 255).nullable()
    val hash = varchar("hash", 1024).nullable()
    val resetPasswordToken = varchar("reset_password_token", 255).nullable()
    val resetPasswordExpiration = timestamp("reset_password_expiration").nullable()
    val loginAttempts = varchar("login_attempts", 50).default("0")
    val lockUntil = timestamp("lock_until").nullable()

    // Timestamps
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

    // Email verification
    val emailVerified = bool("email_verified").default(false)
    val emailVerificationToken = varchar("email_verification_token", 255).nullable()
    val emailVerificationExpires = timestamp("email_verification_expires").nullable()
    val emailVerifiedAt = timestamp("email_verified_at").nullable()

    // Type
    val type = varchar("type", 50).default("individual")

    // Business info (flat columns)
    val businessName = varchar("business_name", 255).nullable()
    val businessWebsite = varchar("business_website", 2048).nullable()
    val businessLogoId = integer("business_logo_id").nullable()

    // Address (flat columns)
    val addressStreet = varchar("address_street", 255).nullable()
    val addressCity = varchar("address_city", 255).nullable()
    val addressState = varchar("address_state", 50).nullable()
    val addressZip = varchar("address_zip", 20).nullable()

    // Preferences (flat columns)
    val preferencesFrequency = varchar("preferences_frequency", 50).default("one_time")
    val preferencesIsAnonymous = bool("preferences_is_anonymous").default(false)
    val preferencesWantsUpdates = bool("preferences_wants_updates").default(true)

    // Fund allocation preferences
    val fundAllocation = varchar("fund_allocation", 50).default("general_fund")
    val preferredCommunity = varchar("preferred_community", 255).nullable()
    val waitForCommunityLaunch = bool("wait_for_community_launch").default(false)
    val letGccChooseBlocks = bool("let_gcc_choose_blocks").default(true)

    // Stripe (flat columns)
    val stripeCustomerId = varchar("stripe_customer_id", 255).nullable()
    val stripeSubscriptionId = varchar("stripe_subscription_id", 255).nullable()
    val stripeDefaultPaymentMethod = varchar("stripe_default_payment_method", 255).nullable()

    // Stats (flat columns — numeric stored as varchar)
    val statsTotalContributed = varchar("stats_total_contributed", 50).default("0")
    val statsBlocksFunded = varchar("stats_blocks_funded", 50).default("0")
    val statsLastContributionAt = timestamp("stats_last_contribution_at").nullable()

    // Active sponsorship
    val activeSponsorship = bool("active_sponsorship").default(false)

    // Movement rankings
    val signupTimestamp = timestamp("signup_timestamp").nullable()
    val signupRankingsGlobal = varchar("signup_rankings_global", 50).nullable()
    val earlyAdopterTier = varchar("early_adopter_tier", 50).default("standard")
    val referralCode = varchar("referral_code", 50).uniqueIndex().nullable()
    val referredBy = varchar("referred_by", 255).nullable()

    // Public profile settings
    val profileIsPublic = bool("profile_is_public").default(true)
    val profileTagline = varchar("profile_tagline", 500).nullable()
    val profileBio = text("profile_bio").nullable()
    val profileSocialTwitter = varchar("profile_social_twitter", 255).nullable()
    val profileSocialLinkedIn = varchar("profile_social_linkedin", 255).nullable()
    val profileSocialInstagram = varchar("profile_social_instagram", 255).nullable()
    val profileSocialWebsite = varchar("profile_social_website", 2048).nullable()

    // Auth (Payload style)
    val salt = varchar("salt", 255).nullable()
    val hash = varchar("hash", 1024).nullable()
    val resetPasswordToken = varchar("reset_password_token", 255).nullable()
    val resetPasswordExpiration = timestamp("reset_password_expiration").nullable()
    val loginAttempts = varchar("login_attempts", 50).default("0")
    val lockUntil = timestamp("lock_until").nullable()

    // Timestamps
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
    val city = varchar("city", 100)
    val state = varchar("state", 50)

    // GeoJSON Polygon boundary
    val boundary = jsonbCol<GeoJSONPolygon>("boundary")

    // Default map center — stored as [lng, lat] JSON array in production
    val mapCenter = jsonbCol<List<Double>>("map_center")

    // Default zoom level (numeric stored as varchar)
    val defaultZoom = varchar("default_zoom", 50).default("14")

    // Status
    val status = varchar("status", 50).default("planning")

    // Launch date
    val launchDate = timestamp("launch_date").nullable()

    // Timestamps
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Blocks
// ===========================================================================
object Blocks : Table("blocks") {
    val id = integer("id").autoIncrement()
    val code = varchar("code", 20).uniqueIndex().nullable()
    val name = varchar("name", 255)
    val description = text("description").nullable()
    val status = varchar("status", 50).default("available")

    // Service area reference
    val serviceAreaId = integer("service_area_id")
        .references(ServiceAreas.id).nullable()

    // Route reference (1:1 relationship)
    val routeId = integer("route_id").nullable()

    // Location
    val locationNeighborhood = varchar("location_neighborhood", 255).nullable()
    val locationBoundary = jsonbCol<GeoJSONPolygon>("location_boundary").nullable()
    val locationCenter = jsonbCol<GeoJSONPoint>("location_center").nullable()

    // Block metadata
    val streetName = varchar("street_name", 255).nullable()
    val fromCorner = varchar("from_corner", 255).nullable()
    val toCorner = varchar("to_corner", 255).nullable()
    val side = varchar("side", 10).nullable()
    val includesAlley = bool("includes_alley").default(true)
    val checkpoints = jsonbCol<kotlinx.serialization.json.JsonElement>("checkpoints").nullable()

    // Display image URL (Vercel Blob)
    val displayImage = varchar("display_image", 2048).nullable()

    // Image location (where the display photo was taken)
    val imageLocationLat = double("image_location_lat").nullable()
    val imageLocationLng = double("image_location_lng").nullable()

    // Funding (numeric stored as varchar)
    val fundingGoal = varchar("funding_goal", 50).default("100")
    val fundingCurrent = varchar("funding_current", 50).default("0")
    val fundedById = integer("funded_by_id").nullable()
    val fundedAt = timestamp("funded_at").nullable()

    // Preferred cleaning schedule
    val preferredCleaningDate = timestamp("preferred_cleaning_date").nullable()
    val preferredCleaningTimeStart = varchar("preferred_cleaning_time_start", 5).nullable()
    val preferredCleaningTimeEnd = varchar("preferred_cleaning_time_end", 5).nullable()

    // Completion
    val completedById = integer("completed_by_id").nullable()
    val completedAt = timestamp("completed_at").nullable()

    // Timestamps
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Routes
// ===========================================================================
object Routes : Table("routes") {
    val id = integer("id").autoIncrement()
    val code = varchar("code", 20).uniqueIndex()
    val displayName = varchar("display_name", 255)
    val serviceAreaId = integer("service_area_id")
        .references(ServiceAreas.id)

    // GeoJSON LineString geometry
    val geometry = jsonbCol<GeoJSONLineString>("geometry")

    // Route metrics (numeric stored as varchar)
    val lengthMeters = varchar("length_meters", 50).nullable()
    val estimatedMinutes = varchar("estimated_minutes", 50).nullable()

    // Status & versioning
    val status = varchar("status", 50).default("draft")
    val publishedAt = timestamp("published_at").nullable()
    val version = integer("version").default(1)

    // Funding (numeric stored as varchar)
    val payoutAmount = varchar("payout_amount", 50).default("10000")

    // Admin who created this route
    val createdById = integer("created_by_id").nullable()

    // Cover image URL
    val imageUrl = varchar("image_url", 2048).nullable()

    // Image location (where the display photo was taken)
    val imageLocationLat = double("image_location_lat").nullable()
    val imageLocationLng = double("image_location_lng").nullable()

    // Timestamps
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
    val name = varchar("name", 100)
    val sequenceOrder = integer("sequence_order")

    // GPS coordinates [lng, lat] as JSONB
    val coordinates = jsonbCol<List<Double>>("coordinates")

    // Photo configuration
    val photoRequired = bool("photo_required").default(true)
    val photoInstructions = text("photo_instructions").nullable()

    // Geofence radius (numeric stored as varchar)
    val geofenceRadius = varchar("geofence_radius", 50).default("15")

    // Type of checkpoint
    val type = varchar("type", 50)

    // Checkpoint image URL
    val imageUrl = varchar("image_url", 2048).nullable()

    // Timestamps
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Jobs
// ===========================================================================
object Jobs : Table("jobs") {
    val id = integer("id").autoIncrement()
    val blockId = integer("block_id").references(Blocks.id)
    val workerId = integer("worker_id").references(Workers.id).nullable()
    val status = varchar("status", 50).default("available")

    // Claim info
    val claimedAt = timestamp("claimed_at").nullable()
    val startedAt = timestamp("started_at").nullable()
    val completedAt = timestamp("completed_at").nullable()

    // Photos and evidence
    val beforePhotos = jsonbCol<kotlinx.serialization.json.JsonElement>("before_photos").nullable()
    val afterPhotos = jsonbCol<kotlinx.serialization.json.JsonElement>("after_photos").nullable()
    val gpsPath = jsonbCol<kotlinx.serialization.json.JsonElement>("gps_path").nullable()

    // Payout (numeric stored as varchar)
    val payoutAmount = varchar("payout_amount", 50).nullable()
    val payoutStatus = varchar("payout_status", 50).nullable()
    val payoutStripeTransferId = varchar("payout_stripe_transfer_id", 255).nullable()
    val payoutCompletedAt = timestamp("payout_completed_at").nullable()

    // Rating (numeric stored as varchar)
    val rating = varchar("rating", 50).nullable()
    val ratingNotes = text("rating_notes").nullable()

    // Timestamps
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Transactions
// ===========================================================================
object Transactions : Table("transactions") {
    val id = integer("id").autoIncrement()
    val sponsorId = integer("sponsor_id").references(Sponsors.id).nullable()
    val blockId = integer("block_id").references(Blocks.id).nullable()
    val jobId = integer("job_id").references(Jobs.id).nullable()

    // Stripe info
    val stripePaymentIntentId = varchar("stripe_payment_intent_id", 255).nullable()
    val stripeCheckoutSessionId = varchar("stripe_checkout_session_id", 255).nullable()
    val stripeSubscriptionId = varchar("stripe_subscription_id", 255).nullable()

    // Amount (numeric stored as varchar)
    val amount = varchar("amount", 50)
    val currency = varchar("currency", 10).default("usd")

    // Status
    val status = varchar("status", 50).default("pending")

    // Contribution type and allocation
    val allocationType = varchar("allocation_type", 50).default("general")
    val sponsorType = varchar("sponsor_type", 50).nullable()

    // Public display settings
    val displayName = varchar("display_name", 255).nullable()
    val isAnonymous = bool("is_anonymous").default(false)

    // Supporter message
    val message = text("message").nullable()
    val messageStatus = varchar("message_status", 50).default("visible")
    val messageRedactedText = text("message_redacted_text").nullable()

    // Preferred cleaning schedule
    val preferredCleaningDate = timestamp("preferred_cleaning_date").nullable()
    val preferredCleaningTimeStart = varchar("preferred_cleaning_time_start", 5).nullable()
    val preferredCleaningTimeEnd = varchar("preferred_cleaning_time_end", 5).nullable()

    // Timestamps
    val completedAt = timestamp("completed_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Sponsor Packages
// ===========================================================================
object SponsorPackages : Table("sponsor_packages") {
    val id = integer("id").autoIncrement()
    val sponsorId = integer("sponsor_id").references(Sponsors.id).nullable()
    val name = varchar("name", 255).nullable()

    // Base configuration
    val blockCount = integer("block_count")
    val baseAmount = varchar("base_amount", 50)
    val isRecurring = bool("is_recurring").default(false)
    val recurringFrequency = varchar("recurring_frequency", 50).nullable()

    // Add-ons stored as JSONB
    val workerBrandingAddons = jsonbCol<kotlinx.serialization.json.JsonElement>("worker_branding_addons").nullable()
    val equipmentBrandingAddons = jsonbCol<kotlinx.serialization.json.JsonElement>("equipment_branding_addons").nullable()
    val streetAdvertisingAddons = jsonbCol<kotlinx.serialization.json.JsonElement>("street_advertising_addons").nullable()
    val digitalPresenceAddons = jsonbCol<kotlinx.serialization.json.JsonElement>("digital_presence_addons").nullable()
    val enhancedCleaningAddons = jsonbCol<kotlinx.serialization.json.JsonElement>("enhanced_cleaning_addons").nullable()
    val seasonalServicesAddons = jsonbCol<kotlinx.serialization.json.JsonElement>("seasonal_services_addons").nullable()
    val greenServicesAddons = jsonbCol<kotlinx.serialization.json.JsonElement>("green_services_addons").nullable()
    val businessEngagementAddons = jsonbCol<kotlinx.serialization.json.JsonElement>("business_engagement_addons").nullable()

    // Events
    val eventPackages = jsonbCol<kotlinx.serialization.json.JsonElement>("event_packages").nullable()

    // Media packages
    val photoPackage = varchar("photo_package", 255).nullable()
    val videoPackage = varchar("video_package", 255).nullable()

    // Calculated amounts (numeric stored as varchar)
    val workerBrandingAmount = varchar("worker_branding_amount", 50).default("0")
    val equipmentBrandingAmount = varchar("equipment_branding_amount", 50).default("0")
    val streetAdvertisingAmount = varchar("street_advertising_amount", 50).default("0")
    val digitalPresenceAmount = varchar("digital_presence_amount", 50).default("0")
    val enhancedCleaningAmount = varchar("enhanced_cleaning_amount", 50).default("0")
    val seasonalServicesAmount = varchar("seasonal_services_amount", 50).default("0")
    val greenServicesAmount = varchar("green_services_amount", 50).default("0")
    val businessEngagementAmount = varchar("business_engagement_amount", 50).default("0")
    val eventsAmount = varchar("events_amount", 50).default("0")
    val mediaAmount = varchar("media_amount", 50).default("0")

    // Totals (numeric stored as varchar)
    val addonsAmount = varchar("addons_amount", 50).default("0")
    val volumeDiscountAmount = varchar("volume_discount_amount", 50).default("0")
    val recurringDiscountAmount = varchar("recurring_discount_amount", 50).default("0")
    val totalAmount = varchar("total_amount", 50)
    val workerPayoutAmount = varchar("worker_payout_amount", 50)
    val gccFeeAmount = varchar("gcc_fee_amount", 50).default("0")

    // Enterprise flags
    val isEnterprise = bool("is_enterprise").default(false)
    val enterpriseTier = varchar("enterprise_tier", 50).nullable()
    val requiresCustomQuote = bool("requires_custom_quote").default(false)
    val recognitionLevel = varchar("recognition_level", 50).nullable()

    // Status
    val status = varchar("status", 50).default("draft")
    val stripePaymentIntentId = varchar("stripe_payment_intent_id", 255).nullable()
    val stripeCheckoutSessionId = varchar("stripe_checkout_session_id", 255).nullable()
    val stripeSubscriptionId = varchar("stripe_subscription_id", 255).nullable()
    val paidAt = timestamp("paid_at").nullable()

    // Notes and metadata
    val notes = text("notes").nullable()
    val metadata = jsonbCol<kotlinx.serialization.json.JsonElement>("metadata").nullable()

    // Timestamps
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Waitlist Entries
// ===========================================================================
object WaitlistEntries : Table("waitlist_entries") {
    val id = integer("id").autoIncrement()
    val email = varchar("email", 255).uniqueIndex()
    val name = varchar("name", 255).nullable()
    val phone = varchar("phone", 50).nullable()

    // Location (flat columns)
    val locationCountry = varchar("location_country", 255).default("United States")
    val locationCountryCode = varchar("location_country_code", 10).default("US")
    val locationState = varchar("location_state", 255)
    val locationStateCode = varchar("location_state_code", 10)
    val locationCity = varchar("location_city", 255)
    val locationNeighborhood = varchar("location_neighborhood", 255).nullable()

    // Rankings (numeric stored as varchar)
    val signupTimestamp = timestamp("signup_timestamp")
    val rankingsGlobal = varchar("rankings_global", 50)
    val rankingsCountry = varchar("rankings_country", 50)
    val rankingsState = varchar("rankings_state", 50)
    val rankingsCity = varchar("rankings_city", 50)
    val rankingsNeighborhood = varchar("rankings_neighborhood", 50).nullable()

    // Tier and referral
    val earlyAdopterTier = varchar("early_adopter_tier", 50).default("standard")
    val referralCode = varchar("referral_code", 50).uniqueIndex()
    val referredById = integer("referred_by_id").nullable()
    val referralCount = varchar("referral_count", 50).default("0")

    // Status
    val status = varchar("status", 50).default("waitlisted")
    val convertedToWorkerId = integer("converted_to_worker_id").nullable()
    val invitedAt = timestamp("invited_at").nullable()

    // Source tracking (Kotlin property renamed to avoid hiding ColumnSet.source)
    val signupSource = varchar("source", 255).nullable()
    val mobileAppPlatform = varchar("mobile_app_platform", 50).nullable()

    // Preferences (flat columns)
    val preferencesWantsToBeWorker = bool("preferences_wants_to_be_worker").default(true)
    val preferencesWantsToBeSponsor = bool("preferences_wants_to_be_sponsor").default(false)
    val preferencesWantsToVolunteer = bool("preferences_wants_to_volunteer").default(false)
    val preferencesEmailUpdates = bool("preferences_email_updates").default(true)

    // Timestamps
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ===========================================================================
// Media
// ===========================================================================
object MediaTable : Table("media") {
    val id = integer("id").autoIncrement()
    val alt = varchar("alt", 500).nullable()
    val category = varchar("category", 50).nullable()
    val url = varchar("url", 2048).nullable()
    val thumbnailUrl = varchar("thumbnail_u_r_l", 2048).nullable()
    val filename = varchar("filename", 500).uniqueIndex().nullable()
    val mimeType = varchar("mime_type", 100).nullable()
    val filesize = varchar("filesize", 50).nullable()
    val width = varchar("width", 50).nullable()
    val height = varchar("height", 50).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

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

// ===========================================================================
// Route Scans (Scout & Clean feature)
// ===========================================================================
object RouteScans : Table("route_scans") {
    val id = integer("id").autoIncrement()
    val routeId = integer("route_id").references(Routes.id)
    val workerId = integer("worker_id").references(Workers.id)
    val deviceType = varchar("device_type", 10).default("gps")
    val phase = varchar("phase", 30).default("scouting")
    val coveragePercent = double("coverage_percent").default(0.0)
    val scoutStartedAt = timestamp("scout_started_at")
    val scoutCompletedAt = timestamp("scout_completed_at").nullable()
    val cleanStartedAt = timestamp("clean_started_at").nullable()
    val cleanCompletedAt = timestamp("clean_completed_at").nullable()
    val startLat = double("start_lat")
    val startLng = double("start_lng")
    val scoutPathGeoJson = text("scout_path_geojson").nullable()
    val cleanPathGeoJson = text("clean_path_geojson").nullable()
    val totalTrashVolumeLiters = double("total_trash_volume_liters").nullable()
    val totalTrashWeightLbs = double("total_trash_weight_lbs").nullable()
    val pointsAwarded = integer("points_awarded").default(0)
    val status = varchar("status", 30).default("in_progress")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object ScanHotspots : Table("scan_hotspots") {
    val id = integer("id").autoIncrement()
    val scanId = integer("scan_id").references(RouteScans.id)
    val lat = double("lat")
    val lng = double("lng")
    val trashTypes = varchar("trash_types", 500).default("[]")
    val severity = integer("severity").default(1)
    val estimatedVolumeLiters = double("estimated_volume_liters").nullable()
    val estimatedWeightLbs = double("estimated_weight_lbs").nullable()
    val confidence = float("confidence").default(0f)
    val beforePhotoUrl = varchar("before_photo_url", 500)
    val afterPhotoUrl = varchar("after_photo_url", 500).nullable()
    val arAnchorData = text("ar_anchor_data").nullable()
    val depthData = text("depth_data").nullable()
    val detectedAt = timestamp("detected_at")
    val cleanedAt = timestamp("cleaned_at").nullable()
    val verificationScore = float("verification_score").nullable()
    val status = varchar("status", 20).default("detected")

    override val primaryKey = PrimaryKey(id)
}

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
