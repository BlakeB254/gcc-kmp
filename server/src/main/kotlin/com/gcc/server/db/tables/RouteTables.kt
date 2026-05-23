package com.gcc.server.db.tables

import com.gcc.shared.model.*
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

// Route-subsystem tables: service areas, blocks, routes, checkpoints,
// jobs, and the route-release queue introduced in PR #4.
// Split out of Tables.kt as part of the M-9 per-domain split.
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
// Route release queue
// ===========================================================================
object RouteReleaseBatches : Table("route_release_batches") {
    val id = integer("id").autoIncrement()
    val serviceAreaId = integer("service_area_id").references(ServiceAreas.id)
    val name = varchar("name", 255)
    val communityName = varchar("community_name", 255)
    val eligibleZipCodes = jsonbCol<List<String>>("eligible_zip_codes")
    val status = varchar("status", 50).default("draft")
    val releaseAt = timestamp("release_at")
    val nextWaveAt = timestamp("next_wave_at").nullable()
    val offerWindowMinutes = integer("offer_window_minutes").default(240)
    val timezone = varchar("timezone", 64).default("America/Chicago")
    val daytimeWindowStart = varchar("daytime_window_start", 5).default("08:00")
    val daytimeWindowEnd = varchar("daytime_window_end", 5).default("20:00")
    val cleanupStartsAt = timestamp("cleanup_starts_at").nullable()
    val cleanupEndsAt = timestamp("cleanup_ends_at").nullable()
    val targetSlots = integer("target_slots").default(0)
    val waveNumber = integer("wave_number").default(0)
    val createdById = integer("created_by_id").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object RouteReleaseRoutes : Table("route_release_routes") {
    val id = integer("id").autoIncrement()
    val batchId = integer("batch_id").references(RouteReleaseBatches.id)
    val routeId = integer("route_id").references(Routes.id)
    val jobId = integer("job_id").references(Jobs.id)
    val status = varchar("status", 50).default("open")
    val acceptedByWorkerId = integer("accepted_by_worker_id").references(Workers.id).nullable()
    val acceptedOfferId = integer("accepted_offer_id").nullable()
    val acceptedAt = timestamp("accepted_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object RouteJobOffers : Table("route_job_offers") {
    val id = integer("id").autoIncrement()
    val batchId = integer("batch_id").references(RouteReleaseBatches.id)
    val workerId = integer("worker_id").references(Workers.id)
    val waveNumber = integer("wave_number")
    val status = varchar("status", 50).default("pending")
    val offerToken = varchar("offer_token", 96)
    val offeredAt = timestamp("offered_at")
    val expiresAt = timestamp("expires_at")
    val respondedAt = timestamp("responded_at").nullable()
    val selectedRouteId = integer("selected_route_id").references(Routes.id).nullable()
    val selectedJobId = integer("selected_job_id").references(Jobs.id).nullable()
    val declineReason = text("decline_reason").nullable()
    val notificationStatus = varchar("notification_status", 32).default("pending")
    val notificationLastError = text("notification_last_error").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object RouteCommunityQueue : Table("route_community_queue") {
    val id = integer("id").autoIncrement()
    val serviceAreaId = integer("service_area_id").references(ServiceAreas.id)
    val workerId = integer("worker_id").references(Workers.id)
    val queueRank = varchar("queue_rank", 50).nullable()
    val lastOpportunityAt = timestamp("last_opportunity_at").nullable()
    val lastOfferedBatchId = integer("last_offered_batch_id").nullable()
    val lastOfferStatus = varchar("last_offer_status", 50).nullable()
    val opportunitiesCount = integer("opportunities_count").default(0)
    val carryoverReleaseAfter = timestamp("carryover_release_after").nullable()
    val carryoverAttempts = integer("carryover_attempts").default(0)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object RouteOfferNotifications : Table("route_offer_notifications") {
    val id = integer("id").autoIncrement()
    val offerId = integer("offer_id").references(RouteJobOffers.id)
    val workerId = integer("worker_id").references(Workers.id)
    val channel = varchar("channel", 20)
    val kind = varchar("kind", 32)
    val status = varchar("status", 32).default("pending")
    val scheduledAt = timestamp("scheduled_at")
    val sentAt = timestamp("sent_at").nullable()
    val attempts = integer("attempts").default(0)
    val lastError = text("last_error").nullable()
    val payload = jsonbCol<kotlinx.serialization.json.JsonElement>("payload").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}


// RouteScans and ScanHotspots were originally further down in Tables.kt;
// moved here in the final M-9 pass so all route-subsystem tables live in
// one place.
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

