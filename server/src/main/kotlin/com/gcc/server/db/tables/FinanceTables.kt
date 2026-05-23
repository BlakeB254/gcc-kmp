package com.gcc.server.db.tables

import com.gcc.shared.model.*
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

// Finance tables that are mirrored from the Drizzle schema:
//   Transactions  — donations + sponsorships
//   SponsorPackages — calculator-based custom sponsorships
//
// Tables under the "Server-only" divider (SponsorWallets, etc.) live in
// Tables.kt and don't appear in schema.ts — different concern, different file.
// Split out of Tables.kt as part of the M-9 per-domain split.
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

