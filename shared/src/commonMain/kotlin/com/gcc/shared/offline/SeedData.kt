package com.gcc.shared.offline

import com.gcc.shared.model.*
import kotlinx.datetime.Instant

object SeedData {

    // ── JWT Token ──────────────────────────────────────────────────────
    // Header: {"alg":"HS256","typ":"JWT"}  -> base64url: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9
    // Payload: {"workerId":1,"exp":4102444800,"iat":1705312000} -> base64url: eyJ3b3JrZXJJZCI6MSwiZXhwIjo0MTAyNDQ0ODAwLCJpYXQiOjE3MDUzMTIwMDB9
    // Signature: dummy
    val offlineToken: String =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" +
        ".eyJ3b3JrZXJJZCI6MSwiZXhwIjo0MTAyNDQ0ODAwLCJpYXQiOjE3MDUzMTIwMDB9" +
        ".ZGVtby1zaWduYXR1cmU"

    // ── Worker ─────────────────────────────────────────────────────────
    val worker: Worker = Worker(
        id = 1,
        name = "Marcus Johnson",
        email = "marcus.johnson@example.com",
        phone = "+17735551234",
        emailVerified = true,
        address = WorkerAddress(
            street = "3829 W Grenshaw St",
            city = "Chicago",
            state = "IL",
            zip = "60624",
            neighborhood = "North Lawndale",
            isNorthLawndale = true,
            latitude = 41.8604,
            longitude = -87.7225,
        ),
        verification = WorkerVerification(
            status = VerificationStatus.APPROVED,
            governmentIdStatus = DocumentStatus.APPROVED,
            proofOfAddressStatus = DocumentStatus.APPROVED,
        ),
        stripe = WorkerStripe(
            accountId = "acct_demo_marcus",
            onboardingStatus = StripeOnboardingStatus.COMPLETED,
            instantPayoutsEnabled = true,
            defaultPayoutMethod = "instant",
        ),
        stats = WorkerStats(
            jobsCompleted = 47,
            totalEarnings = 235000,
            averageRating = 4.9,
            lastJobAt = Instant.parse("2024-06-10T14:30:00Z"),
        ),
        earlyAdopterTier = EarlyAdopterTier.FOUNDER,
        role = "worker",
        referralCode = "MARCUS-GCC",
        notifications = WorkerNotifications(
            pushEnabled = true,
            smsEnabled = true,
            emailEnabled = true,
        ),
        createdAt = Instant.parse("2024-01-15T10:00:00Z"),
        updatedAt = Instant.parse("2024-06-10T14:30:00Z"),
    )

    // ── Auth Response ──────────────────────────────────────────────────
    val authResponse: AuthResponse = AuthResponse(
        token = offlineToken,
        worker = worker,
        exp = 4102444800L,
    )

    // ── Service Area ───────────────────────────────────────────────────
    val serviceArea: ServiceArea = ServiceArea(
        id = 1,
        name = "North Lawndale",
        city = "Chicago",
        state = "IL",
        mapCenter = Coordinates(lng = -87.7225, lat = 41.8604),
        mapZoom = 14.0,
        status = ServiceAreaStatus.ACTIVE,
        totalBlocks = 12,
        activeWorkers = 8,
        createdAt = Instant.parse("2024-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2024-06-01T00:00:00Z"),
    )

    // ── Routes (8) ─────────────────────────────────────────────────────
    val routes: List<Route> = listOf(
        Route(
            id = 1,
            code = "NL-PULASKI-01",
            displayName = "Pulaski Rd Block",
            description = "S Pulaski Rd to S Karlov Ave, W Roosevelt Rd south to W Cermak Rd — both sides",
            serviceAreaId = 1,
            geometry = GeoJSONLineString(coordinates = listOf(
                listOf(-87.7260, 41.8665), listOf(-87.7260, 41.8625), listOf(-87.7260, 41.8585),
                listOf(-87.7260, 41.8545), listOf(-87.7260, 41.8510),
                listOf(-87.7200, 41.8510), listOf(-87.7200, 41.8545),
                listOf(-87.7200, 41.8585), listOf(-87.7200, 41.8625), listOf(-87.7200, 41.8665),
                listOf(-87.7260, 41.8665),
            )),
            status = RouteStatus.DRAFT,
            distanceMeters = 1800.0,
            estimatedDurationMinutes = 60,
            payoutAmount = 10000,
            checkpoints = listOf(
                RouteCheckpoint(id = 1, name = "Pulaski & Roosevelt", sequenceOrder = 1, coordinates = Coordinates(lng = -87.7260, lat = 41.8665), photoRequired = true, type = CheckpointType.ROUTE_START),
                RouteCheckpoint(id = 2, name = "Pulaski & 16th St", sequenceOrder = 2, coordinates = Coordinates(lng = -87.7260, lat = 41.8585), photoRequired = true, type = CheckpointType.PHOTO_POINT),
                RouteCheckpoint(id = 3, name = "Pulaski & Ogden", sequenceOrder = 3, coordinates = Coordinates(lng = -87.7260, lat = 41.8545), photoRequired = true, type = CheckpointType.PHOTO_POINT),
                RouteCheckpoint(id = 4, name = "Pulaski & Cermak", sequenceOrder = 4, coordinates = Coordinates(lng = -87.7260, lat = 41.8510), photoRequired = true, type = CheckpointType.ROUTE_END),
            ),
            imageUrl = "demo://route-pulaski-road.jpg",
            createdAt = Instant.parse("2024-02-01T08:00:00Z"),
            updatedAt = Instant.parse("2024-05-15T12:00:00Z"),
        ),
        Route(
            id = 2,
            code = "NL-HOMAN-01",
            displayName = "Homan Ave Block",
            description = "S Homan Ave to S St Louis Ave, W Roosevelt Rd south to W Ogden Ave — both sides",
            serviceAreaId = 1,
            geometry = GeoJSONLineString(coordinates = listOf(
                listOf(-87.7105, 41.8665), listOf(-87.7105, 41.8625), listOf(-87.7105, 41.8585),
                listOf(-87.7105, 41.8545),
                listOf(-87.7050, 41.8545), listOf(-87.7050, 41.8585),
                listOf(-87.7050, 41.8625), listOf(-87.7050, 41.8665),
                listOf(-87.7105, 41.8665),
            )),
            status = RouteStatus.DRAFT,
            distanceMeters = 1400.0,
            estimatedDurationMinutes = 45,
            payoutAmount = 10000,
            checkpoints = listOf(
                RouteCheckpoint(id = 5, name = "Homan & Roosevelt", sequenceOrder = 1, coordinates = Coordinates(lng = -87.7105, lat = 41.8665), photoRequired = true, type = CheckpointType.ROUTE_START),
                RouteCheckpoint(id = 6, name = "Homan & Fillmore", sequenceOrder = 2, coordinates = Coordinates(lng = -87.7105, lat = 41.8625), photoRequired = true, type = CheckpointType.PHOTO_POINT),
                RouteCheckpoint(id = 7, name = "Homan & Ogden", sequenceOrder = 3, coordinates = Coordinates(lng = -87.7105, lat = 41.8545), photoRequired = true, type = CheckpointType.ROUTE_END),
            ),
            imageUrl = "demo://route-homan-corridor.jpg",
            createdAt = Instant.parse("2024-02-05T08:00:00Z"),
            updatedAt = Instant.parse("2024-05-15T12:00:00Z"),
        ),
        Route(
            id = 3,
            code = "NL-OGDEN-01",
            displayName = "Ogden Ave Diagonal",
            description = "W Ogden Ave from S Pulaski Rd northeast to S Kedzie Ave — both sides of the diagonal",
            serviceAreaId = 1,
            geometry = GeoJSONLineString(coordinates = listOf(
                listOf(-87.7260, 41.8545), listOf(-87.7210, 41.8555), listOf(-87.7165, 41.8570),
                listOf(-87.7105, 41.8590), listOf(-87.7045, 41.8610),
                listOf(-87.7045, 41.8640), listOf(-87.7105, 41.8620),
                listOf(-87.7165, 41.8600), listOf(-87.7210, 41.8585), listOf(-87.7260, 41.8575),
                listOf(-87.7260, 41.8545),
            )),
            status = RouteStatus.DRAFT,
            distanceMeters = 2400.0,
            estimatedDurationMinutes = 90,
            payoutAmount = 10000,
            checkpoints = listOf(
                RouteCheckpoint(id = 8, name = "Ogden & Pulaski", sequenceOrder = 1, coordinates = Coordinates(lng = -87.7260, lat = 41.8545), photoRequired = true, type = CheckpointType.ROUTE_START),
                RouteCheckpoint(id = 9, name = "Ogden & Central Park", sequenceOrder = 2, coordinates = Coordinates(lng = -87.7165, lat = 41.8570), photoRequired = true, type = CheckpointType.PHOTO_POINT),
                RouteCheckpoint(id = 10, name = "Ogden & Homan", sequenceOrder = 3, coordinates = Coordinates(lng = -87.7105, lat = 41.8590), photoRequired = true, type = CheckpointType.PHOTO_POINT),
                RouteCheckpoint(id = 11, name = "Ogden & Kedzie", sequenceOrder = 4, coordinates = Coordinates(lng = -87.7045, lat = 41.8610), photoRequired = true, type = CheckpointType.ROUTE_END),
            ),
            imageUrl = "demo://route-ogden-loop.jpg",
            createdAt = Instant.parse("2024-02-10T08:00:00Z"),
            updatedAt = Instant.parse("2024-05-15T12:00:00Z"),
        ),
        Route(
            id = 4,
            code = "NL-CPARK-01",
            displayName = "Central Park Ave Block",
            description = "S Central Park Ave to S Lawndale Ave, W Roosevelt Rd south to W Ogden Ave — both sides",
            serviceAreaId = 1,
            geometry = GeoJSONLineString(coordinates = listOf(
                listOf(-87.7165, 41.8665), listOf(-87.7165, 41.8625), listOf(-87.7165, 41.8585),
                listOf(-87.7165, 41.8545),
                listOf(-87.7120, 41.8545), listOf(-87.7120, 41.8585),
                listOf(-87.7120, 41.8625), listOf(-87.7120, 41.8665),
                listOf(-87.7165, 41.8665),
            )),
            status = RouteStatus.DRAFT,
            distanceMeters = 1200.0,
            estimatedDurationMinutes = 40,
            payoutAmount = 10000,
            checkpoints = listOf(
                RouteCheckpoint(id = 12, name = "Central Park & Roosevelt", sequenceOrder = 1, coordinates = Coordinates(lng = -87.7165, lat = 41.8665), photoRequired = true, type = CheckpointType.ROUTE_START),
                RouteCheckpoint(id = 13, name = "Central Park & 16th", sequenceOrder = 2, coordinates = Coordinates(lng = -87.7165, lat = 41.8585), photoRequired = true, type = CheckpointType.PHOTO_POINT),
                RouteCheckpoint(id = 14, name = "Central Park & Ogden", sequenceOrder = 3, coordinates = Coordinates(lng = -87.7165, lat = 41.8545), photoRequired = true, type = CheckpointType.ROUTE_END),
            ),
            imageUrl = "demo://route-central-park.jpg",
            createdAt = Instant.parse("2024-02-15T08:00:00Z"),
            updatedAt = Instant.parse("2024-05-15T12:00:00Z"),
        ),
        Route(
            id = 5,
            code = "NL-INDEP-01",
            displayName = "Independence Blvd Block",
            description = "S Independence Blvd to S Springfield Ave, W Roosevelt Rd south to W Ogden Ave — both sides",
            serviceAreaId = 1,
            geometry = GeoJSONLineString(coordinates = listOf(
                listOf(-87.7000, 41.8665), listOf(-87.7000, 41.8625), listOf(-87.7000, 41.8585),
                listOf(-87.7000, 41.8545),
                listOf(-87.6950, 41.8545), listOf(-87.6950, 41.8585),
                listOf(-87.6950, 41.8625), listOf(-87.6950, 41.8665),
                listOf(-87.7000, 41.8665),
            )),
            status = RouteStatus.DRAFT,
            distanceMeters = 1600.0,
            estimatedDurationMinutes = 55,
            payoutAmount = 10000,
            checkpoints = listOf(
                RouteCheckpoint(id = 15, name = "Independence & Roosevelt", sequenceOrder = 1, coordinates = Coordinates(lng = -87.7000, lat = 41.8665), photoRequired = true, type = CheckpointType.ROUTE_START),
                RouteCheckpoint(id = 16, name = "Independence & Fillmore", sequenceOrder = 2, coordinates = Coordinates(lng = -87.7000, lat = 41.8625), photoRequired = true, type = CheckpointType.ALLEY_ENTRANCE),
                RouteCheckpoint(id = 17, name = "Independence & 16th", sequenceOrder = 3, coordinates = Coordinates(lng = -87.7000, lat = 41.8585), photoRequired = true, type = CheckpointType.PHOTO_POINT),
                RouteCheckpoint(id = 18, name = "Independence & Ogden", sequenceOrder = 4, coordinates = Coordinates(lng = -87.7000, lat = 41.8545), photoRequired = true, type = CheckpointType.ROUTE_END),
            ),
            imageUrl = "demo://route-independence-blvd.jpg",
            createdAt = Instant.parse("2024-02-20T08:00:00Z"),
            updatedAt = Instant.parse("2024-05-15T12:00:00Z"),
        ),
        Route(
            id = 6,
            code = "NL-16TH-E-01",
            displayName = "W 16th St Corridor",
            description = "W 16th St from S Pulaski Rd east to S Homan Ave — north and south sidewalks",
            serviceAreaId = 1,
            geometry = GeoJSONLineString(coordinates = listOf(
                listOf(-87.7260, 41.8585), listOf(-87.7210, 41.8585), listOf(-87.7165, 41.8585),
                listOf(-87.7105, 41.8585),
                listOf(-87.7105, 41.8555), listOf(-87.7165, 41.8555),
                listOf(-87.7210, 41.8555), listOf(-87.7260, 41.8555),
                listOf(-87.7260, 41.8585),
            )),
            status = RouteStatus.DRAFT,
            distanceMeters = 1100.0,
            estimatedDurationMinutes = 35,
            payoutAmount = 10000,
            checkpoints = listOf(
                RouteCheckpoint(id = 19, name = "16th & Pulaski", sequenceOrder = 1, coordinates = Coordinates(lng = -87.7260, lat = 41.8585), photoRequired = true, type = CheckpointType.ROUTE_START),
                RouteCheckpoint(id = 20, name = "16th & Central Park", sequenceOrder = 2, coordinates = Coordinates(lng = -87.7165, lat = 41.8585), photoRequired = true, type = CheckpointType.PHOTO_POINT),
                RouteCheckpoint(id = 21, name = "16th & Homan", sequenceOrder = 3, coordinates = Coordinates(lng = -87.7105, lat = 41.8585), photoRequired = true, type = CheckpointType.ROUTE_END),
            ),
            imageUrl = "demo://route-16th-street.jpg",
            createdAt = Instant.parse("2024-03-01T08:00:00Z"),
            updatedAt = Instant.parse("2024-05-15T12:00:00Z"),
        ),
        Route(
            id = 7,
            code = "NL-ROOSE-01",
            displayName = "Roosevelt Rd Corridor",
            description = "W Roosevelt Rd from S Kedzie Ave west to S Pulaski Rd — north and south sidewalks",
            serviceAreaId = 1,
            geometry = GeoJSONLineString(coordinates = listOf(
                listOf(-87.7045, 41.8665), listOf(-87.7105, 41.8665), listOf(-87.7165, 41.8665),
                listOf(-87.7260, 41.8665),
                listOf(-87.7260, 41.8695), listOf(-87.7165, 41.8695),
                listOf(-87.7105, 41.8695), listOf(-87.7045, 41.8695),
                listOf(-87.7045, 41.8665),
            )),
            status = RouteStatus.DRAFT,
            distanceMeters = 2000.0,
            estimatedDurationMinutes = 70,
            payoutAmount = 10000,
            checkpoints = listOf(
                RouteCheckpoint(id = 22, name = "Roosevelt & Kedzie", sequenceOrder = 1, coordinates = Coordinates(lng = -87.7045, lat = 41.8665), photoRequired = true, type = CheckpointType.ROUTE_START),
                RouteCheckpoint(id = 23, name = "Roosevelt & Homan", sequenceOrder = 2, coordinates = Coordinates(lng = -87.7105, lat = 41.8665), photoRequired = true, type = CheckpointType.PHOTO_POINT),
                RouteCheckpoint(id = 24, name = "Roosevelt & Central Park", sequenceOrder = 3, coordinates = Coordinates(lng = -87.7165, lat = 41.8665), photoRequired = true, type = CheckpointType.PHOTO_POINT),
                RouteCheckpoint(id = 25, name = "Roosevelt & Pulaski", sequenceOrder = 4, coordinates = Coordinates(lng = -87.7260, lat = 41.8665), photoRequired = true, type = CheckpointType.ROUTE_END),
            ),
            imageUrl = "demo://route-roosevelt-road.jpg",
            createdAt = Instant.parse("2024-03-05T08:00:00Z"),
            updatedAt = Instant.parse("2024-05-15T12:00:00Z"),
        ),
        Route(
            id = 8,
            code = "NL-KOST-01",
            displayName = "Kostner Ave Block",
            description = "S Kostner Ave to S Kildare Ave, W Roosevelt Rd south to W 16th St — both sides",
            serviceAreaId = 1,
            geometry = GeoJSONLineString(coordinates = listOf(
                listOf(-87.7340, 41.8665), listOf(-87.7340, 41.8625), listOf(-87.7340, 41.8585),
                listOf(-87.7290, 41.8585), listOf(-87.7290, 41.8625), listOf(-87.7290, 41.8665),
                listOf(-87.7340, 41.8665),
            )),
            status = RouteStatus.DRAFT,
            distanceMeters = 800.0,
            estimatedDurationMinutes = 30,
            payoutAmount = 10000,
            checkpoints = listOf(
                RouteCheckpoint(id = 26, name = "Kostner & Roosevelt", sequenceOrder = 1, coordinates = Coordinates(lng = -87.7340, lat = 41.8665), photoRequired = true, type = CheckpointType.ROUTE_START),
                RouteCheckpoint(id = 27, name = "Kostner & Fillmore", sequenceOrder = 2, coordinates = Coordinates(lng = -87.7340, lat = 41.8625), photoRequired = true, type = CheckpointType.PHOTO_POINT),
                RouteCheckpoint(id = 28, name = "Kostner & 16th", sequenceOrder = 3, coordinates = Coordinates(lng = -87.7340, lat = 41.8585), photoRequired = true, type = CheckpointType.ROUTE_END),
            ),
            imageUrl = "demo://route-kostner-circuit.jpg",
            createdAt = Instant.parse("2024-03-10T08:00:00Z"),
            updatedAt = Instant.parse("2024-05-15T12:00:00Z"),
        ),
    )

    // ── Leaderboard (10 entries) ───────────────────────────────────────
    val leaderboard: List<LeaderboardEntry> = listOf(
        LeaderboardEntry(rank = 1, workerId = 5, name = "Deshawn Williams", jobsCompleted = 62, totalEarnings = 310000, averageRating = 4.8, earlyAdopterTier = EarlyAdopterTier.PIONEER, badgeCount = 9),
        LeaderboardEntry(rank = 2, workerId = 3, name = "Keisha Thompson", jobsCompleted = 55, totalEarnings = 275000, averageRating = 5.0, earlyAdopterTier = EarlyAdopterTier.FOUNDER, badgeCount = 8),
        LeaderboardEntry(rank = 3, workerId = 1, name = "Marcus Johnson", jobsCompleted = 47, totalEarnings = 235000, averageRating = 4.9, earlyAdopterTier = EarlyAdopterTier.FOUNDER, badgeCount = 7),
        LeaderboardEntry(rank = 4, workerId = 7, name = "Andre Davis", jobsCompleted = 41, totalEarnings = 205000, averageRating = 4.7, earlyAdopterTier = EarlyAdopterTier.PIONEER, badgeCount = 6),
        LeaderboardEntry(rank = 5, workerId = 2, name = "Tanya Robinson", jobsCompleted = 38, totalEarnings = 190000, averageRating = 4.6, earlyAdopterTier = EarlyAdopterTier.EARLY, badgeCount = 5),
        LeaderboardEntry(rank = 6, workerId = 9, name = "Jerome Washington", jobsCompleted = 33, totalEarnings = 165000, averageRating = 4.5, earlyAdopterTier = EarlyAdopterTier.EARLY, badgeCount = 5),
        LeaderboardEntry(rank = 7, workerId = 4, name = "Aaliyah Brown", jobsCompleted = 29, totalEarnings = 145000, averageRating = 4.8, earlyAdopterTier = EarlyAdopterTier.STANDARD, badgeCount = 4),
        LeaderboardEntry(rank = 8, workerId = 8, name = "Carlos Martinez", jobsCompleted = 24, totalEarnings = 120000, averageRating = 4.4, earlyAdopterTier = EarlyAdopterTier.STANDARD, badgeCount = 3),
        LeaderboardEntry(rank = 9, workerId = 6, name = "Destiny Harris", jobsCompleted = 19, totalEarnings = 95000, averageRating = 4.7, earlyAdopterTier = EarlyAdopterTier.STANDARD, badgeCount = 3),
        LeaderboardEntry(rank = 10, workerId = 10, name = "Malik Jefferson", jobsCompleted = 15, totalEarnings = 75000, averageRating = 4.3, earlyAdopterTier = EarlyAdopterTier.STANDARD, badgeCount = 2),
    )

    // ── Feed Items (12) ────────────────────────────────────────────────
    val feedItems: List<FeedItem> = listOf(
        FeedItem(
            id = "feed-01", type = FeedItemType.SPOT_REPORT, actorName = "Marcus Johnson", actorTier = EarlyAdopterTier.FOUNDER,
            title = "Illegal dumping spotted", description = "Large pile of tires found behind 3900 W Ogden Ave. Approximately 15 tires stacked against the fence, blocking the alley entrance.",
            mediaUrls = listOf("demo://spot-tires-ogden-1.jpg", "demo://spot-tires-ogden-2.jpg"),
            locationLat = 41.8545, locationLng = -87.7225,
            communityArea = "North Lawndale", timestamp = Instant.parse("2024-06-10T12:00:00Z"),
        ),
        FeedItem(
            id = "feed-02", type = FeedItemType.SPOT_REPORT, actorName = "Tanya Robinson", actorTier = EarlyAdopterTier.EARLY,
            title = "Mattress dumped in alley", description = "Someone left a queen-size mattress and box spring in the alley behind Homan Ave. Partially blocking garbage truck access.",
            mediaUrls = listOf("demo://spot-mattress-homan-1.jpg"),
            locationLat = 41.8630, locationLng = -87.7105,
            communityArea = "North Lawndale", timestamp = Instant.parse("2024-06-09T18:30:00Z"),
        ),
        FeedItem(
            id = "feed-03", type = FeedItemType.BOUNTY_CREATED, actorName = "Community Sponsor", actorTier = EarlyAdopterTier.STANDARD,
            title = "New bounty: Alley Cleanup Behind Homan", description = "Sponsor posted a $40 bounty for alley cleanup. Trash bags and broken furniture need removal from the alley between Homan and Central Park.",
            amount = 4000, locationLat = 41.8630, locationLng = -87.7105,
            communityArea = "North Lawndale", timestamp = Instant.parse("2024-06-09T15:00:00Z"),
        ),
        FeedItem(
            id = "feed-04", type = FeedItemType.BOUNTY_CREATED, actorName = "Green Block Fund", actorTier = EarlyAdopterTier.STANDARD,
            title = "New bounty: Tire Dump Near Ogden", description = "Organization posted a $35 bounty for tire removal at the Ogden & Central Park intersection. Estimated 15+ tires.",
            amount = 3500, locationLat = 41.8570, locationLng = -87.7165,
            communityArea = "North Lawndale", timestamp = Instant.parse("2024-06-09T10:00:00Z"),
        ),
        FeedItem(
            id = "feed-05", type = FeedItemType.BOUNTY_COMPLETED, actorName = "Keisha Thompson", actorTier = EarlyAdopterTier.FOUNDER,
            title = "Bounty completed: Mattress on Central Park", description = "Keisha cleaned up the mattress dump on Central Park Ave. Before and after photos verified by community.",
            amount = 5000,
            mediaUrls = listOf("demo://bounty-mattress-before.jpg"),
            completionMediaUrls = listOf("demo://bounty-mattress-after-1.jpg", "demo://bounty-mattress-after-2.jpg"),
            locationLat = 41.8585, locationLng = -87.7165,
            communityArea = "North Lawndale", timestamp = Instant.parse("2024-06-08T16:45:00Z"),
        ),
        FeedItem(
            id = "feed-06", type = FeedItemType.BOUNTY_COMPLETED, actorName = "Andre Davis", actorTier = EarlyAdopterTier.PIONEER,
            title = "Bounty completed: Trash on Independence", description = "Andre cleared the trash pile on Independence Blvd near Fillmore. 6 bags removed and sidewalk swept.",
            amount = 2000,
            completionMediaUrls = listOf("demo://bounty-trash-independence-after.jpg"),
            locationLat = 41.8625, locationLng = -87.7000,
            communityArea = "North Lawndale", timestamp = Instant.parse("2024-06-07T14:20:00Z"),
        ),
        FeedItem(
            id = "feed-07", type = FeedItemType.JOB_COMPLETED, actorName = "Deshawn Williams", actorTier = EarlyAdopterTier.PIONEER,
            title = "Route completed: Pulaski Road Sweep", description = "Deshawn completed the Pulaski Road route in 52 minutes. 4 checkpoints verified, 5-star quality rating.",
            amount = 10000,
            completionMediaUrls = listOf("demo://job-pulaski-checkpoint-1.jpg", "demo://job-pulaski-checkpoint-4.jpg"),
            locationLat = 41.8585, locationLng = -87.7260,
            communityArea = "North Lawndale", timestamp = Instant.parse("2024-06-07T11:00:00Z"),
        ),
        FeedItem(
            id = "feed-08", type = FeedItemType.JOB_COMPLETED, actorName = "Marcus Johnson", actorTier = EarlyAdopterTier.FOUNDER,
            title = "Route completed: Homan Ave Corridor", description = "Marcus completed the Homan Ave route with a 5-star rating. All 3 checkpoints photographed and submitted.",
            amount = 10000,
            completionMediaUrls = listOf("demo://job-homan-checkpoint-1.jpg", "demo://job-homan-checkpoint-3.jpg"),
            locationLat = 41.8625, locationLng = -87.7105,
            communityArea = "North Lawndale", timestamp = Instant.parse("2024-06-06T15:30:00Z"),
        ),
        FeedItem(
            id = "feed-09", type = FeedItemType.VOLUNTEER_CLEANUP, actorName = "Aaliyah Brown", actorTier = EarlyAdopterTier.STANDARD,
            title = "Volunteer cleanup completed", description = "Aaliyah cleaned up an alley near 16th & Central Park. Removed broken glass, swept sidewalk, and bagged loose trash.",
            points = 50,
            completionMediaUrls = listOf("demo://volunteer-16th-centralpark.jpg"),
            locationLat = 41.8585, locationLng = -87.7165,
            communityArea = "North Lawndale", timestamp = Instant.parse("2024-06-06T10:15:00Z"),
        ),
        FeedItem(
            id = "feed-10", type = FeedItemType.VOLUNTEER_CLEANUP, actorName = "Jerome Washington", actorTier = EarlyAdopterTier.EARLY,
            title = "Volunteer cleanup completed", description = "Jerome removed debris from the lot on Roosevelt & Homan. Construction scraps and broken pallets cleared from the sidewalk.",
            points = 75,
            completionMediaUrls = listOf("demo://volunteer-roosevelt-homan.jpg"),
            locationLat = 41.8665, locationLng = -87.7105,
            communityArea = "North Lawndale", timestamp = Instant.parse("2024-06-05T09:00:00Z"),
        ),
        FeedItem(
            id = "feed-11", type = FeedItemType.BADGE_UNLOCKED, actorName = "Keisha Thompson", actorTier = EarlyAdopterTier.FOUNDER,
            title = "Badge unlocked: Block Captain", description = "Keisha earned the Gold Block Captain badge for completing 50 jobs",
            communityArea = "North Lawndale", timestamp = Instant.parse("2024-06-04T17:00:00Z"),
            metadata = mapOf("badgeId" to "block-captain", "tier" to "gold"),
        ),
        FeedItem(
            id = "feed-12", type = FeedItemType.BADGE_UNLOCKED, actorName = "Marcus Johnson", actorTier = EarlyAdopterTier.FOUNDER,
            title = "Badge unlocked: Eagle Eye", description = "Marcus earned the Silver Eagle Eye badge for 10 verified spot reports",
            communityArea = "North Lawndale", timestamp = Instant.parse("2024-06-03T13:45:00Z"),
            metadata = mapOf("badgeId" to "eagle-eye", "tier" to "silver"),
        ),
    )

    // ── Bounties (5) ───────────────────────────────────────────────────
    val bounties: List<Bounty> = listOf(
        Bounty(
            id = 1, posterType = PosterType.SPONSOR, posterId = 1, walletId = 1,
            title = "Illegal Dumping on Pulaski", description = "Large pile of construction debris dumped on sidewalk near 3800 S Pulaski",
            amount = 2500, status = BountyStatus.OPEN,
            locationLat = 41.8620, locationLng = -87.7260, radiusMeters = 200.0,
            city = "Chicago", communityArea = "North Lawndale", neighborhood = "North Lawndale",
            mediaUrls = listOf("demo://dumping-pulaski-1.jpg", "demo://dumping-pulaski-2.jpg"),
            expiresAt = Instant.parse("2024-06-12T12:00:00Z"), pledgeCount = 2,
            createdAt = Instant.parse("2024-06-10T12:00:00Z"), updatedAt = Instant.parse("2024-06-10T12:00:00Z"),
        ),
        Bounty(
            id = 2, posterType = PosterType.SPONSOR, posterId = 1, walletId = 1,
            title = "Alley Cleanup Behind Homan", description = "Accumulated trash and broken furniture in alley behind 3400 W Homan Ave",
            amount = 4000, status = BountyStatus.CLAIMED,
            locationLat = 41.8630, locationLng = -87.7105, radiusMeters = 150.0,
            city = "Chicago", communityArea = "North Lawndale", neighborhood = "North Lawndale",
            mediaUrls = listOf("demo://alley-homan-1.jpg", "demo://alley-homan-2.jpg", "demo://alley-homan-3.jpg"),
            claimedBy = 1, expiresAt = null, pledgeCount = 3,
            createdAt = Instant.parse("2024-06-09T15:00:00Z"), updatedAt = Instant.parse("2024-06-10T08:00:00Z"),
        ),
        Bounty(
            id = 3, posterType = PosterType.SPONSOR, posterId = 1, walletId = 1,
            title = "Tire Dump Near Ogden", description = "Approximately 15 tires dumped near Ogden & Central Park intersection",
            amount = 3500, status = BountyStatus.IN_PROGRESS,
            locationLat = 41.8570, locationLng = -87.7165, radiusMeters = 250.0,
            city = "Chicago", communityArea = "North Lawndale", neighborhood = "North Lawndale",
            mediaUrls = listOf("demo://tires-ogden-1.jpg", "demo://tires-ogden-2.jpg"),
            claimedBy = 5, jobId = 2, expiresAt = null, pledgeCount = 2,
            createdAt = Instant.parse("2024-06-08T10:00:00Z"), updatedAt = Instant.parse("2024-06-09T14:00:00Z"),
        ),
        Bounty(
            id = 4, posterType = PosterType.SPONSOR, posterId = 1, walletId = 1,
            title = "Mattress on Central Park", description = "Two mattresses left on the sidewalk near Central Park & 16th",
            amount = 5000, status = BountyStatus.COMPLETED,
            locationLat = 41.8585, locationLng = -87.7165, radiusMeters = 100.0,
            city = "Chicago", communityArea = "North Lawndale", neighborhood = "North Lawndale",
            mediaUrls = listOf("demo://mattress-centralpark-1.jpg"),
            claimedBy = 3, jobId = 3, pledgeCount = 4,
            completionMediaUrls = listOf("https://media.gcc.example/bounty-4-after-1.jpg"),
            createdAt = Instant.parse("2024-06-05T09:00:00Z"), updatedAt = Instant.parse("2024-06-08T16:45:00Z"),
        ),
        Bounty(
            id = 5, posterType = PosterType.SPONSOR, posterId = 1, walletId = 1,
            title = "Trash Pile on Independence", description = "Household trash bags piled on Independence Blvd near Fillmore",
            amount = 2000, status = BountyStatus.EXPIRED,
            locationLat = 41.8625, locationLng = -87.7000, radiusMeters = 200.0,
            city = "Chicago", communityArea = "North Lawndale", neighborhood = "North Lawndale",
            mediaUrls = listOf("demo://trash-independence-1.jpg", "demo://trash-independence-2.jpg"),
            expiresAt = Instant.parse("2024-06-06T09:00:00Z"), pledgeCount = 1,
            createdAt = Instant.parse("2024-06-04T09:00:00Z"), updatedAt = Instant.parse("2024-06-06T09:00:00Z"),
        ),
    )

    // ── Active Job ─────────────────────────────────────────────────────
    val activeJob: Job = Job(
        id = 1,
        routeId = 1,
        workerId = 1,
        status = JobStatus.IN_PROGRESS,
        timing = JobTiming(
            claimedAt = Instant.parse("2024-06-10T13:00:00Z"),
            startedAt = Instant.parse("2024-06-10T13:05:00Z"),
        ),
        payoutAmount = 10000,
        fundingSource = "route_fund",
        createdAt = Instant.parse("2024-06-10T12:55:00Z"),
        updatedAt = Instant.parse("2024-06-10T13:05:00Z"),
    )

    // ── Wallet ─────────────────────────────────────────────────────────
    val wallet: SponsorWallet = SponsorWallet(
        id = 1,
        workerId = 1,
        totalBalance = 15000,
        generalFundBalance = 8000,
        routeFundBalance = 4500,
        bountyFundBalance = 2500,
        totalDeposited = 25000,
        totalSpent = 10000,
        createdAt = Instant.parse("2024-01-15T10:00:00Z"),
        updatedAt = Instant.parse("2024-06-10T12:00:00Z"),
    )

    // ── Wallet Transactions (8) ────────────────────────────────────────
    val transactions: List<WalletTransaction> = listOf(
        WalletTransaction(
            id = 1, walletId = 1, type = WalletTransactionType.DEPOSIT, fundType = FundType.GENERAL,
            amount = 10000, balanceAfter = 10000,
            referenceType = WalletReferenceType.STRIPE_DEPOSIT, description = "Initial deposit",
            stripePaymentIntentId = "pi_demo_001",
            createdAt = Instant.parse("2024-01-15T10:30:00Z"),
        ),
        WalletTransaction(
            id = 2, walletId = 1, type = WalletTransactionType.DEPOSIT, fundType = FundType.GENERAL,
            amount = 15000, balanceAfter = 25000,
            referenceType = WalletReferenceType.STRIPE_DEPOSIT, description = "Second deposit",
            stripePaymentIntentId = "pi_demo_002",
            createdAt = Instant.parse("2024-03-01T09:00:00Z"),
        ),
        WalletTransaction(
            id = 3, walletId = 1, type = WalletTransactionType.ALLOCATION, fundType = FundType.ROUTE,
            amount = 5000, balanceAfter = 20000,
            description = "Allocated to route fund",
            createdAt = Instant.parse("2024-03-05T10:00:00Z"),
        ),
        WalletTransaction(
            id = 4, walletId = 1, type = WalletTransactionType.ALLOCATION, fundType = FundType.BOUNTY,
            amount = 3000, balanceAfter = 17000,
            description = "Allocated to bounty fund",
            createdAt = Instant.parse("2024-03-05T10:05:00Z"),
        ),
        WalletTransaction(
            id = 5, walletId = 1, type = WalletTransactionType.JOB_DEDUCTION, fundType = FundType.ROUTE,
            amount = 3500, balanceAfter = 13500,
            referenceId = 1, referenceType = WalletReferenceType.JOB, description = "Pulaski Road Sweep payout",
            createdAt = Instant.parse("2024-04-15T14:00:00Z"),
        ),
        WalletTransaction(
            id = 6, walletId = 1, type = WalletTransactionType.JOB_DEDUCTION, fundType = FundType.ROUTE,
            amount = 3000, balanceAfter = 10500,
            referenceId = 2, referenceType = WalletReferenceType.JOB, description = "Homan Ave Corridor payout",
            createdAt = Instant.parse("2024-05-02T11:30:00Z"),
        ),
        WalletTransaction(
            id = 7, walletId = 1, type = WalletTransactionType.BOUNTY_DEDUCTION, fundType = FundType.BOUNTY,
            amount = 2500, balanceAfter = 8000,
            referenceId = 1, referenceType = WalletReferenceType.BOUNTY, description = "Bounty: Illegal Dumping on Pulaski",
            createdAt = Instant.parse("2024-06-01T08:00:00Z"),
        ),
        WalletTransaction(
            id = 8, walletId = 1, type = WalletTransactionType.BOUNTY_DEDUCTION, fundType = FundType.BOUNTY,
            amount = 500, balanceAfter = 7500,
            referenceId = 2, referenceType = WalletReferenceType.BOUNTY_PLEDGE, description = "Pledge for Alley Cleanup Behind Homan",
            createdAt = Instant.parse("2024-06-05T16:00:00Z"),
        ),
    )

    // ── Badges (7) ─────────────────────────────────────────────────────
    val badges: List<Badge> = listOf(
        Badge(id = "first-sweep", category = BadgeCategory.JOBS, name = "First Sweep", description = "Complete your first cleanup job", tier = BadgeTier.BRONZE, requirement = 1, progress = 1, isUnlocked = true, unlockedAt = Instant.parse("2024-01-20T14:00:00Z")),
        Badge(id = "street-regular", category = BadgeCategory.JOBS, name = "Street Regular", description = "Complete 10 cleanup jobs", tier = BadgeTier.SILVER, requirement = 10, progress = 10, isUnlocked = true, unlockedAt = Instant.parse("2024-03-15T16:00:00Z")),
        Badge(id = "block-captain", category = BadgeCategory.JOBS, name = "Block Captain", description = "Complete 25 cleanup jobs", tier = BadgeTier.GOLD, requirement = 25, progress = 25, isUnlocked = true, unlockedAt = Instant.parse("2024-05-10T12:00:00Z")),
        Badge(id = "money-maker", category = BadgeCategory.EARNINGS, name = "Money Maker", description = "Earn over $1,000 total", tier = BadgeTier.SILVER, requirement = 100000, progress = 235000, isUnlocked = true, unlockedAt = Instant.parse("2024-04-01T10:00:00Z")),
        Badge(id = "week-warrior", category = BadgeCategory.STREAK, name = "Week Warrior", description = "Complete jobs 7 days in a row", tier = BadgeTier.BRONZE, requirement = 7, progress = 7, isUnlocked = true, unlockedAt = Instant.parse("2024-04-20T18:00:00Z")),
        Badge(id = "community-voice", category = BadgeCategory.COMMUNITY, name = "Community Voice", description = "Submit your first community spot report", tier = BadgeTier.BRONZE, requirement = 1, progress = 1, isUnlocked = true, unlockedAt = Instant.parse("2024-02-28T09:00:00Z")),
        Badge(id = "eagle-eye", category = BadgeCategory.OBSERVE, name = "Eagle Eye", description = "Have 10 spot reports verified", tier = BadgeTier.SILVER, requirement = 10, progress = 10, isUnlocked = true, unlockedAt = Instant.parse("2024-06-03T13:45:00Z")),
    )

    // ── Community Report ───────────────────────────────────────────────
    val communityReport: CommunityReport = CommunityReport(
        id = 1,
        reporterId = 1,
        type = CommunityReportType.SIGHTING,
        coordinates = Coordinates(lng = -87.7200, lat = 41.8595),
        description = "Large tire dump spotted in alley",
        status = CommunityReportStatus.PENDING,
        upvotes = 3,
        observerRewardPoints = 10,
        createdAt = Instant.parse("2024-06-10T11:00:00Z"),
        updatedAt = Instant.parse("2024-06-10T11:00:00Z"),
    )

    // ── Spot Response ──────────────────────────────────────────────────
    val spotResponse: SpotResponse = SpotResponse(
        report = communityReport,
        matchingBounties = emptyList(),
    )

    // ── Volunteer Cleanup ──────────────────────────────────────────────
    val volunteerCleanup: VolunteerCleanup = VolunteerCleanup(
        id = 1,
        reportId = 1,
        workerId = 1,
        locationLat = 41.8595,
        locationLng = -87.7200,
        locationVerified = true,
        startedAt = Instant.parse("2024-06-09T08:00:00Z"),
        completedAt = Instant.parse("2024-06-09T08:45:00Z"),
        durationMinutes = 45,
        status = VolunteerCleanupStatus.COMPLETED,
        pointsAwarded = 50,
        createdAt = Instant.parse("2024-06-09T08:00:00Z"),
        updatedAt = Instant.parse("2024-06-09T08:45:00Z"),
    )

    // ── Blocks (10 fundable North Lawndale blocks) ─────────────────────
    val blocks: List<Block> = listOf(
        Block(
            id = 1, code = "NL-001", name = "Pulaski & Roosevelt", serviceAreaId = 1,
            status = BlockStatus.FUNDED,
            boundary = GeoJSONPolygon(coordinates = listOf(listOf(
                listOf(-87.7290, 41.8695), listOf(-87.7230, 41.8695),
                listOf(-87.7230, 41.8665), listOf(-87.7290, 41.8665),
                listOf(-87.7290, 41.8695),
            ))),
            center = GeoJSONPoint(coordinates = listOf(-87.7260, 41.8680)),
            fundingGoal = 500000, fundingCurrent = 500000,
            createdAt = Instant.parse("2024-01-15T10:00:00Z"), updatedAt = Instant.parse("2024-06-10T12:00:00Z"),
        ),
        Block(
            id = 2, code = "NL-002", name = "Homan & Ogden", serviceAreaId = 1,
            status = BlockStatus.IN_PROGRESS,
            boundary = GeoJSONPolygon(coordinates = listOf(listOf(
                listOf(-87.7135, 41.8570), listOf(-87.7075, 41.8570),
                listOf(-87.7075, 41.8540), listOf(-87.7135, 41.8540),
                listOf(-87.7135, 41.8570),
            ))),
            center = GeoJSONPoint(coordinates = listOf(-87.7105, 41.8555)),
            fundingGoal = 350000, fundingCurrent = 280000,
            createdAt = Instant.parse("2024-02-01T08:00:00Z"), updatedAt = Instant.parse("2024-06-09T14:00:00Z"),
        ),
        Block(
            id = 3, code = "NL-003", name = "Kedzie & Cermak", serviceAreaId = 1,
            status = BlockStatus.AVAILABLE,
            boundary = GeoJSONPolygon(coordinates = listOf(listOf(
                listOf(-87.7075, 41.8520), listOf(-87.7015, 41.8520),
                listOf(-87.7015, 41.8490), listOf(-87.7075, 41.8490),
                listOf(-87.7075, 41.8520),
            ))),
            center = GeoJSONPoint(coordinates = listOf(-87.7045, 41.8505)),
            fundingGoal = 400000, fundingCurrent = 120000,
            createdAt = Instant.parse("2024-02-10T08:00:00Z"), updatedAt = Instant.parse("2024-06-01T00:00:00Z"),
        ),
        Block(
            id = 4, code = "NL-004", name = "Central Park & Fillmore", serviceAreaId = 1,
            status = BlockStatus.AVAILABLE,
            boundary = GeoJSONPolygon(coordinates = listOf(listOf(
                listOf(-87.7195, 41.8640), listOf(-87.7135, 41.8640),
                listOf(-87.7135, 41.8610), listOf(-87.7195, 41.8610),
                listOf(-87.7195, 41.8640),
            ))),
            center = GeoJSONPoint(coordinates = listOf(-87.7165, 41.8625)),
            fundingGoal = 300000, fundingCurrent = 75000,
            createdAt = Instant.parse("2024-02-15T08:00:00Z"), updatedAt = Instant.parse("2024-06-01T00:00:00Z"),
        ),
        Block(
            id = 5, code = "NL-005", name = "Independence & 16th", serviceAreaId = 1,
            status = BlockStatus.COMPLETED,
            boundary = GeoJSONPolygon(coordinates = listOf(listOf(
                listOf(-87.7030, 41.8605), listOf(-87.6970, 41.8605),
                listOf(-87.6970, 41.8575), listOf(-87.7030, 41.8575),
                listOf(-87.7030, 41.8605),
            ))),
            center = GeoJSONPoint(coordinates = listOf(-87.7000, 41.8590)),
            fundingGoal = 250000, fundingCurrent = 250000,
            createdAt = Instant.parse("2024-01-20T08:00:00Z"), updatedAt = Instant.parse("2024-05-20T16:00:00Z"),
        ),
        Block(
            id = 6, code = "NL-006", name = "Springfield & 13th", serviceAreaId = 1,
            status = BlockStatus.AVAILABLE,
            boundary = GeoJSONPolygon(coordinates = listOf(listOf(
                listOf(-87.7230, 41.8660), listOf(-87.7170, 41.8660),
                listOf(-87.7170, 41.8630), listOf(-87.7230, 41.8630),
                listOf(-87.7230, 41.8660),
            ))),
            center = GeoJSONPoint(coordinates = listOf(-87.7200, 41.8645)),
            fundingGoal = 350000, fundingCurrent = 50000,
            createdAt = Instant.parse("2024-03-01T08:00:00Z"), updatedAt = Instant.parse("2024-06-01T00:00:00Z"),
        ),
        Block(
            id = 7, code = "NL-007", name = "Kostner & Roosevelt", serviceAreaId = 1,
            status = BlockStatus.FUNDED,
            boundary = GeoJSONPolygon(coordinates = listOf(listOf(
                listOf(-87.7370, 41.8695), listOf(-87.7310, 41.8695),
                listOf(-87.7310, 41.8665), listOf(-87.7370, 41.8665),
                listOf(-87.7370, 41.8695),
            ))),
            center = GeoJSONPoint(coordinates = listOf(-87.7340, 41.8680)),
            fundingGoal = 450000, fundingCurrent = 450000,
            createdAt = Instant.parse("2024-02-20T08:00:00Z"), updatedAt = Instant.parse("2024-06-08T12:00:00Z"),
        ),
        Block(
            id = 8, code = "NL-008", name = "Lawndale & Grenshaw", serviceAreaId = 1,
            status = BlockStatus.AVAILABLE,
            boundary = GeoJSONPolygon(coordinates = listOf(listOf(
                listOf(-87.7260, 41.8625), listOf(-87.7200, 41.8625),
                listOf(-87.7200, 41.8595), listOf(-87.7260, 41.8595),
                listOf(-87.7260, 41.8625),
            ))),
            center = GeoJSONPoint(coordinates = listOf(-87.7230, 41.8610)),
            fundingGoal = 300000, fundingCurrent = 90000,
            createdAt = Instant.parse("2024-03-10T08:00:00Z"), updatedAt = Instant.parse("2024-06-01T00:00:00Z"),
        ),
        Block(
            id = 9, code = "NL-009", name = "California & 21st", serviceAreaId = 1,
            status = BlockStatus.IN_PROGRESS,
            boundary = GeoJSONPolygon(coordinates = listOf(listOf(
                listOf(-87.6970, 41.8540), listOf(-87.6920, 41.8540),
                listOf(-87.6920, 41.8510), listOf(-87.6970, 41.8510),
                listOf(-87.6970, 41.8540),
            ))),
            center = GeoJSONPoint(coordinates = listOf(-87.6945, 41.8525)),
            fundingGoal = 400000, fundingCurrent = 320000,
            createdAt = Instant.parse("2024-03-15T08:00:00Z"), updatedAt = Instant.parse("2024-06-09T10:00:00Z"),
        ),
        Block(
            id = 10, code = "NL-010", name = "Hamlin & 19th", serviceAreaId = 1,
            status = BlockStatus.AVAILABLE,
            boundary = GeoJSONPolygon(coordinates = listOf(listOf(
                listOf(-87.7200, 41.8570), listOf(-87.7140, 41.8570),
                listOf(-87.7140, 41.8545), listOf(-87.7200, 41.8545),
                listOf(-87.7200, 41.8570),
            ))),
            center = GeoJSONPoint(coordinates = listOf(-87.7170, 41.8558)),
            fundingGoal = 350000, fundingCurrent = 100000,
            createdAt = Instant.parse("2024-03-20T08:00:00Z"), updatedAt = Instant.parse("2024-06-01T00:00:00Z"),
        ),
    )

    // ── Stats ──────────────────────────────────────────────────────────
    val stats: Map<String, Int> = mapOf(
        "totalJobs" to 2847,
        "totalWorkers" to 156,
        "totalEarnings" to 142350,
        "activeBounties" to 23,
        "activeRoutes" to 42,
        "volunteerCleanups" to 189,
    )

    // ── Admin Models ─────────────────────────────────────────────────

    val adminStats: AdminStatsResponse = AdminStatsResponse(
        totalWorkers = 156,
        verifiedWorkers = 89,
        pendingVerifications = 12,
        totalSponsors = 34,
        totalJobs = 2847,
        completedJobs = 2650,
        activeJobs = 47,
        totalRevenue = 28470000,
        totalRoutes = 42,
        totalBlocks = 10,
        totalSpots = 67,
        activeSpots = 23,
        totalBounties = 12,
        activeBounties = 5,
        totalDisputes = 8,
        openDisputes = 2,
    )

    val adminActivity: List<AdminActivityItem> = listOf(
        AdminActivityItem(1, AdminActivityType.WORKER_VERIFIED, "Marcus Johnson verified by admin", "Admin", Instant.parse("2024-06-10T14:30:00Z")),
        AdminActivityItem(2, AdminActivityType.JOB_COMPLETED, "Block NL-005 cleaning completed", "Keisha Williams", Instant.parse("2024-06-10T13:15:00Z")),
        AdminActivityItem(3, AdminActivityType.BOUNTY_POSTED, "New bounty: Pulaski & 16th illegal dumping", "Community", Instant.parse("2024-06-10T12:00:00Z")),
        AdminActivityItem(4, AdminActivityType.BLOCK_FUNDED, "Block NL-007 fully funded by sponsor", "Chicago Foundation", Instant.parse("2024-06-10T11:00:00Z")),
        AdminActivityItem(5, AdminActivityType.WORKER_REGISTERED, "New worker signup: David Thompson", null, Instant.parse("2024-06-10T10:30:00Z")),
        AdminActivityItem(6, AdminActivityType.DISPUTE_OPENED, "Job #2841 dispute: incomplete cleanup", "Resident Report", Instant.parse("2024-06-10T09:45:00Z")),
        AdminActivityItem(7, AdminActivityType.SPONSOR_REGISTERED, "New sponsor: West Side Community Fund", null, Instant.parse("2024-06-10T09:00:00Z")),
        AdminActivityItem(8, AdminActivityType.ROUTE_PUBLISHED, "Route NL-R003 published", "Admin", Instant.parse("2024-06-10T08:30:00Z")),
        AdminActivityItem(9, AdminActivityType.BOUNTY_CLAIMED, "Bounty #12 claimed by worker", "Marcus Johnson", Instant.parse("2024-06-09T16:00:00Z")),
        AdminActivityItem(10, AdminActivityType.DISPUTE_RESOLVED, "Dispute #5 resolved: payment issued", "Admin", Instant.parse("2024-06-09T15:00:00Z")),
    )

    val workerList: List<Worker> = listOf(
        worker,
        worker.copy(id = 2, name = "Keisha Williams", email = "keisha.w@example.com", phone = "+17735552345",
            stats = WorkerStats(jobsCompleted = 34, totalEarnings = 340000, averageRating = 4.9)),
        worker.copy(id = 3, name = "David Thompson", email = "david.t@example.com", phone = "+17735553456",
            stats = WorkerStats(jobsCompleted = 12, totalEarnings = 120000, averageRating = 4.5)),
        worker.copy(id = 4, name = "Angela Davis", email = "angela.d@example.com", phone = "+17735554567",
            stats = WorkerStats(jobsCompleted = 67, totalEarnings = 670000, averageRating = 4.95)),
        worker.copy(id = 5, name = "Robert Brown", email = "robert.b@example.com", phone = "+17735555678",
            stats = WorkerStats(jobsCompleted = 8, totalEarnings = 80000, averageRating = 4.2)),
    )

    val sponsorList: List<Sponsor> = listOf(
        Sponsor(id = 1, name = "Chicago Foundation", email = "info@chicagofound.org",
            type = SponsorType.ORGANIZATION,
            stats = SponsorStats(totalDonated = 1500000, blocksSponsored = 3),
            createdAt = Instant.parse("2024-01-10T00:00:00Z"), updatedAt = Instant.parse("2024-06-10T00:00:00Z")),
        Sponsor(id = 2, name = "West Side Community Fund", email = "westside@fund.org",
            type = SponsorType.ORGANIZATION,
            stats = SponsorStats(totalDonated = 800000, blocksSponsored = 2),
            createdAt = Instant.parse("2024-02-15T00:00:00Z"), updatedAt = Instant.parse("2024-06-09T00:00:00Z")),
        Sponsor(id = 3, name = "Maria Garcia", email = "maria.g@example.com",
            type = SponsorType.INDIVIDUAL,
            stats = SponsorStats(totalDonated = 100000, blocksSponsored = 1),
            createdAt = Instant.parse("2024-03-01T00:00:00Z"), updatedAt = Instant.parse("2024-06-05T00:00:00Z")),
    )

    // ── Daily Stats (30 days) ────────────────────────────────────────
    val dailyStats: List<AdminDailyStats> = (0 until 30).map { daysAgo ->
        val seed = daysAgo * 7 + 3
        AdminDailyStats(
            date = "2024-${if (daysAgo < 10) "06" else "05"}-${(10 - daysAgo % 28).coerceAtLeast(1).toString().padStart(2, '0')}",
            jobsCompleted = 80 + (seed % 41),
            jobsCreated = 90 + (seed % 41),
            revenue = 800000 + (seed * 23333) % 700001,
            newWorkers = 1 + seed % 5,
            newSponsors = seed % 3,
            bountiesPosted = 1 + seed % 4,
            bountiesClaimed = 1 + seed % 3,
        )
    }

    // ── Revenue by Area ──────────────────────────────────────────────
    val revenueByArea: List<RevenueByArea> = listOf(
        RevenueByArea(serviceAreaId = 1, serviceAreaName = "North Lawndale", totalRevenue = 18500000, jobCount = 1850, workerCount = 89),
        RevenueByArea(serviceAreaId = 2, serviceAreaName = "East Garfield Park", totalRevenue = 5200000, jobCount = 520, workerCount = 34),
        RevenueByArea(serviceAreaId = 3, serviceAreaName = "Austin", totalRevenue = 3100000, jobCount = 310, workerCount = 21),
        RevenueByArea(serviceAreaId = 4, serviceAreaName = "Englewood", totalRevenue = 1670000, jobCount = 167, workerCount = 12),
    )

    // ── Worker Metrics ───────────────────────────────────────────────
    val workerMetrics: List<WorkerMetrics> = listOf(
        WorkerMetrics(workerId = 5, workerName = "Deshawn Williams", jobsCompleted = 62, totalEarnings = 310000, averageRating = 4.8, volunteersCompleted = 15, badgesEarned = 9),
        WorkerMetrics(workerId = 3, workerName = "Keisha Thompson", jobsCompleted = 55, totalEarnings = 275000, averageRating = 5.0, volunteersCompleted = 22, badgesEarned = 8),
        WorkerMetrics(workerId = 1, workerName = "Marcus Johnson", jobsCompleted = 47, totalEarnings = 235000, averageRating = 4.9, volunteersCompleted = 12, badgesEarned = 7),
        WorkerMetrics(workerId = 7, workerName = "Andre Davis", jobsCompleted = 41, totalEarnings = 205000, averageRating = 4.7, volunteersCompleted = 8, badgesEarned = 6),
        WorkerMetrics(workerId = 2, workerName = "Tanya Robinson", jobsCompleted = 38, totalEarnings = 190000, averageRating = 4.6, volunteersCompleted = 10, badgesEarned = 5),
    )

    // ── Sponsor Metrics ──────────────────────────────────────────────
    val sponsorMetrics: List<SponsorMetrics> = listOf(
        SponsorMetrics(sponsorId = 1, sponsorName = "Chicago Foundation", totalFunded = 1500000, blocksSponsored = 3, bountiesPosted = 12, walletBalance = 350000),
        SponsorMetrics(sponsorId = 2, sponsorName = "West Side Community Fund", totalFunded = 800000, blocksSponsored = 2, bountiesPosted = 8, walletBalance = 150000),
        SponsorMetrics(sponsorId = 3, sponsorName = "Maria Garcia", totalFunded = 100000, blocksSponsored = 1, bountiesPosted = 3, walletBalance = 25000),
    )

    // ── Disputes ─────────────────────────────────────────────────────
    val disputeList: List<Dispute> = listOf(
        Dispute(
            id = 1, jobId = 2841, reporterId = 101, reporterType = DisputePartyType.SPONSOR,
            respondentId = 8, respondentType = DisputePartyType.WORKER,
            type = DisputeType.INCOMPLETE, status = DisputeStatus.OPEN,
            description = "Worker marked job as complete but alley was only partially cleaned. Before/after photos show debris remaining near dumpster.",
            evidence = listOf("demo://dispute-1-evidence-1.jpg", "demo://dispute-1-evidence-2.jpg"),
            createdAt = Instant.parse("2024-06-10T09:45:00Z"), updatedAt = Instant.parse("2024-06-10T09:45:00Z"),
        ),
        Dispute(
            id = 2, jobId = 2835, reporterId = 5, reporterType = DisputePartyType.WORKER,
            respondentId = 102, respondentType = DisputePartyType.SPONSOR,
            type = DisputeType.PAYMENT, status = DisputeStatus.UNDER_REVIEW,
            description = "Completed bounty cleanup but payment has not been processed after 48 hours.",
            createdAt = Instant.parse("2024-06-08T14:00:00Z"), updatedAt = Instant.parse("2024-06-09T10:00:00Z"),
        ),
        Dispute(
            id = 3, jobId = 2820, reporterId = 103, reporterType = DisputePartyType.SPONSOR,
            respondentId = 4, respondentType = DisputePartyType.WORKER,
            type = DisputeType.QUALITY, status = DisputeStatus.RESOLVED,
            description = "Quality of cleanup did not meet expectations. Grass clippings left on sidewalk.",
            resolution = DisputeResolution.REFUND_PARTIAL, resolutionNotes = "50% refund issued, worker counseled on quality standards.",
            resolvedBy = 1, resolvedAt = Instant.parse("2024-06-07T16:00:00Z"),
            createdAt = Instant.parse("2024-06-06T11:00:00Z"), updatedAt = Instant.parse("2024-06-07T16:00:00Z"),
        ),
    )

    // ── Feedback ─────────────────────────────────────────────────────
    val feedbackList: List<FeedbackTicket> = listOf(
        FeedbackTicket(
            id = 1, userId = 1, userType = UserType.WORKER, category = FeedbackCategory.APP_ISSUE,
            subject = "Map not loading on slow connection",
            description = "When I'm in areas with poor cell signal, the map tiles don't load and I can't see my route checkpoints.",
            status = FeedbackStatus.IN_PROGRESS, priority = FeedbackPriority.HIGH,
            assignedTo = "Engineering",
            createdAt = Instant.parse("2024-06-09T08:00:00Z"), updatedAt = Instant.parse("2024-06-10T10:00:00Z"),
        ),
        FeedbackTicket(
            id = 2, userId = 3, userType = UserType.WORKER, category = FeedbackCategory.FEATURE_REQUEST,
            subject = "Add route difficulty ratings",
            description = "It would be helpful to see difficulty ratings for routes so I can plan my day better. Some routes have steep hills.",
            status = FeedbackStatus.OPEN, priority = FeedbackPriority.MEDIUM,
            createdAt = Instant.parse("2024-06-08T14:30:00Z"), updatedAt = Instant.parse("2024-06-08T14:30:00Z"),
        ),
        FeedbackTicket(
            id = 3, userId = 1, userType = UserType.SPONSOR, category = FeedbackCategory.PAYMENT_ISSUE,
            subject = "Duplicate charge on wallet deposit",
            description = "I deposited $100 but was charged twice. Please refund the extra charge.",
            status = FeedbackStatus.RESOLVED, priority = FeedbackPriority.URGENT,
            assignedTo = "Finance",
            createdAt = Instant.parse("2024-06-05T09:00:00Z"), updatedAt = Instant.parse("2024-06-06T11:00:00Z"),
        ),
    )

    // ── Address Changes ──────────────────────────────────────────────
    val addressChanges: List<AddressChangeRequest> = listOf(
        AddressChangeRequest(
            workerId = 2,
            newAddress = WorkerAddress(
                street = "4100 W Roosevelt Rd",
                city = "Chicago", state = "IL", zip = "60624",
                neighborhood = "North Lawndale", isNorthLawndale = true,
                latitude = 41.8665, longitude = -87.7290,
            ),
            reason = "Moved to a new apartment within the neighborhood",
        ),
        AddressChangeRequest(
            workerId = 5,
            newAddress = WorkerAddress(
                street = "3200 W 16th St",
                city = "Chicago", state = "IL", zip = "60623",
                neighborhood = "North Lawndale", isNorthLawndale = true,
                latitude = 41.8585, longitude = -87.7105,
            ),
            reason = "Relocating closer to primary route area",
        ),
    )
}
