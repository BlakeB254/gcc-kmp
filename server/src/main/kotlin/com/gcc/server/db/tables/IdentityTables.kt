package com.gcc.server.db.tables

import com.gcc.shared.model.*
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

// Identity tables: Workers, Sponsors, WaitlistEntries, Media.
// Split out of Tables.kt as part of the M-9 per-domain split.
// See CLAUDE.md for the schema-ownership policy this file follows.
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

    // Pending email change (account settings)
    val pendingEmail = varchar("pending_email", 255).nullable()
    val pendingEmailToken = varchar("pending_email_token", 255).nullable()
    val pendingEmailExpires = timestamp("pending_email_expires").nullable()
    val pendingEmailAttempts = integer("pending_email_attempts").default(0)

    // Two-factor authentication (TOTP + recovery codes)
    val totpSecret = varchar("totp_secret", 255).nullable()
    val twoFactorEnabled = bool("two_factor_enabled").default(false)
    val twoFactorRecoveryCodes = jsonbCol<kotlinx.serialization.json.JsonElement>("two_factor_recovery_codes").nullable()
    val twoFactorEnabledAt = timestamp("two_factor_enabled_at").nullable()

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

