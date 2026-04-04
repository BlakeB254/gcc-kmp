package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.*
import com.gcc.shared.model.*
import org.jetbrains.exposed.sql.*

class FeedService {

    /**
     * Aggregate a multi-source activity feed from spot reports, bounties,
     * completed jobs, volunteer cleanups, and badge unlocks.
     *
     * Each source is queried independently, then merged and sorted by
     * timestamp descending before applying offset/limit pagination.
     */
    suspend fun getFeed(
        limit: Int = 30,
        offset: Int = 0,
        type: String? = null,
        communityArea: String? = null,
    ): List<FeedItem> = dbQuery {
        val perSourceLimit = limit  // fetch this many from each source
        val items = mutableListOf<FeedItem>()

        val typeFilter = type?.let {
            try { FeedItemType.valueOf(it.uppercase()) } catch (_: Exception) { null }
        }

        // 1. Spot reports — include photos and location
        if (typeFilter == null || typeFilter == FeedItemType.SPOT_REPORT) {
            val spots = CommunityReports
                .join(Workers, JoinType.LEFT) { CommunityReports.reporterId eq Workers.id }
                .selectAll()
                .orderBy(CommunityReports.createdAt, SortOrder.DESC)
                .limit(perSourceLimit)
                .map { row ->
                    val coords = row[CommunityReports.coordinates]
                    val lat = coords.getOrElse(1) { 0.0 }
                    val lng = coords.getOrElse(0) { 0.0 }
                    // Extract photo URLs from beforePhotos JSON array
                    val photos = try {
                        val json = row[CommunityReports.beforePhotos]
                        if (json is kotlinx.serialization.json.JsonArray) {
                            json.mapNotNull { el ->
                                (el as? kotlinx.serialization.json.JsonPrimitive)?.content
                            }
                        } else emptyList()
                    } catch (_: Exception) { emptyList<String>() }

                    FeedItem(
                        id = "spot_${row[CommunityReports.id]}",
                        type = FeedItemType.SPOT_REPORT,
                        actorName = row.getOrNull(Workers.name) ?: row[CommunityReports.reporterName] ?: "Community Member",
                        actorTier = row.getOrNull(Workers.earlyAdopterTier)?.let { enumValueOfOrDefault<EarlyAdopterTier>(it) } ?: EarlyAdopterTier.STANDARD,
                        title = row[CommunityReports.title] ?: "Reported ${row[CommunityReports.type]} sighting",
                        description = row[CommunityReports.description] ?: "Community report submitted",
                        mediaUrls = photos,
                        locationLat = lat,
                        locationLng = lng,
                        communityArea = row[CommunityReports.locationNeighborhood],
                        timestamp = row[CommunityReports.createdAt],
                    )
                }
            items.addAll(spots)
        }

        // 2. Bounties created (exclude expired/cancelled)
        if (typeFilter == null || typeFilter == FeedItemType.BOUNTY_CREATED) {
            val query = Bounties
                .selectAll()
                .where {
                    (Bounties.status neq "expired") and (Bounties.status neq "cancelled")
                }
            communityArea?.let { query.andWhere { Bounties.communityArea eq it } }

            val bountiesCreated = query
                .orderBy(Bounties.createdAt, SortOrder.DESC)
                .limit(perSourceLimit)
                .map { row ->
                    FeedItem(
                        id = "bounty_created_${row[Bounties.id]}",
                        type = FeedItemType.BOUNTY_CREATED,
                        actorName = "Sponsor",
                        actorTier = EarlyAdopterTier.STANDARD,
                        title = row[Bounties.title],
                        description = row[Bounties.description] ?: "New bounty posted",
                        amount = row[Bounties.amount],
                        communityArea = row[Bounties.communityArea],
                        mediaUrls = row[Bounties.mediaUrls],
                        locationLat = row[Bounties.locationLat],
                        locationLng = row[Bounties.locationLng],
                        timestamp = row[Bounties.createdAt],
                    )
                }
            items.addAll(bountiesCreated)
        }

        // 3. Bounties completed (join Workers via claimedBy)
        if (typeFilter == null || typeFilter == FeedItemType.BOUNTY_COMPLETED) {
            val query = Bounties
                .join(Workers, JoinType.INNER) { Bounties.claimedBy eq Workers.id }
                .select(Bounties.columns + Workers.columns)
                .where { Bounties.status eq "completed" }
            communityArea?.let { query.andWhere { Bounties.communityArea eq it } }

            val bountiesCompleted = query
                .orderBy(Bounties.updatedAt, SortOrder.DESC)
                .limit(perSourceLimit)
                .map { row ->
                    FeedItem(
                        id = "bounty_completed_${row[Bounties.id]}",
                        type = FeedItemType.BOUNTY_COMPLETED,
                        actorName = row[Workers.name],
                        actorTier = enumValueOfOrDefault(row[Workers.earlyAdopterTier]),
                        title = "Completed: ${row[Bounties.title]}",
                        description = row[Bounties.description] ?: "Bounty completed",
                        amount = row[Bounties.amount],
                        communityArea = row[Bounties.communityArea],
                        completionMediaUrls = row[Bounties.completionMediaUrls] ?: emptyList(),
                        timestamp = row[Bounties.updatedAt],
                    )
                }
            items.addAll(bountiesCompleted)
        }

        // 4. Jobs completed
        if (typeFilter == null || typeFilter == FeedItemType.JOB_COMPLETED) {
            val jobsCompleted = Jobs
                .join(Workers, JoinType.INNER) { Jobs.workerId eq Workers.id }
                .select(Jobs.columns + Workers.columns)
                .where { Jobs.status eq "completed" }
                .orderBy(Jobs.completedAt, SortOrder.DESC)
                .limit(perSourceLimit)
                .map { row ->
                    FeedItem(
                        id = "job_${row[Jobs.id]}",
                        type = FeedItemType.JOB_COMPLETED,
                        actorName = row[Workers.name],
                        actorTier = enumValueOfOrDefault(row[Workers.earlyAdopterTier]),
                        title = "Completed a cleaning job",
                        description = "Route cleaned successfully",
                        amount = row[Jobs.payoutAmount]?.toIntOrNull(),
                        timestamp = row[Jobs.completedAt] ?: row[Jobs.updatedAt],
                    )
                }
            items.addAll(jobsCompleted)
        }

        // 5. Volunteer cleanups verified
        if (typeFilter == null || typeFilter == FeedItemType.VOLUNTEER_CLEANUP) {
            val cleanups = VolunteerCleanups
                .join(Workers, JoinType.INNER) { VolunteerCleanups.workerId eq Workers.id }
                .select(VolunteerCleanups.columns + Workers.columns)
                .where { VolunteerCleanups.status eq "verified" }
                .orderBy(VolunteerCleanups.updatedAt, SortOrder.DESC)
                .limit(perSourceLimit)
                .map { row ->
                    FeedItem(
                        id = "cleanup_${row[VolunteerCleanups.id]}",
                        type = FeedItemType.VOLUNTEER_CLEANUP,
                        actorName = row[Workers.name],
                        actorTier = enumValueOfOrDefault(row[Workers.earlyAdopterTier]),
                        title = "Volunteer cleanup verified",
                        description = "Community cleanup completed and verified",
                        points = row[VolunteerCleanups.pointsAwarded],
                        timestamp = row[VolunteerCleanups.updatedAt],
                    )
                }
            items.addAll(cleanups)
        }

        // 6. Badges unlocked
        if (typeFilter == null || typeFilter == FeedItemType.BADGE_UNLOCKED) {
            val badges = WorkerBadges
                .join(Workers, JoinType.INNER) { WorkerBadges.workerId eq Workers.id }
                .select(WorkerBadges.columns + Workers.columns)
                .where { WorkerBadges.unlockedAt.isNotNull() }
                .orderBy(WorkerBadges.unlockedAt, SortOrder.DESC)
                .limit(perSourceLimit)
                .map { row ->
                    FeedItem(
                        id = "badge_${row[WorkerBadges.id]}",
                        type = FeedItemType.BADGE_UNLOCKED,
                        actorName = row[Workers.name],
                        actorTier = enumValueOfOrDefault(row[Workers.earlyAdopterTier]),
                        title = "Unlocked: ${row[WorkerBadges.name]}",
                        description = row[WorkerBadges.description],
                        timestamp = row[WorkerBadges.unlockedAt]!!,
                    )
                }
            items.addAll(badges)
        }

        // Sort all items by timestamp DESC, apply offset + limit
        items.sortByDescending { it.timestamp }

        // Enrich items with captions and profile opt-ins
        val feedItemIds = items.map { it.id }
        val captions = FeedCaptions
            .selectAll()
            .where { FeedCaptions.feedItemId inList feedItemIds }
            .associate { it[FeedCaptions.feedItemId] to it[FeedCaptions.caption] }
        val optIns = FeedProfileOptIns
            .selectAll()
            .where { FeedProfileOptIns.feedItemId inList feedItemIds }
            .map { it[FeedProfileOptIns.feedItemId] }
            .toSet()
        val commentCounts = FeedComments
            .select(FeedComments.feedItemId, FeedComments.feedItemId.count())
            .where { FeedComments.feedItemId inList feedItemIds }
            .groupBy(FeedComments.feedItemId)
            .associate { it[FeedComments.feedItemId] to it[FeedComments.feedItemId.count()].toInt() }

        items.drop(offset).take(limit).map { item ->
            item.copy(
                caption = captions[item.id],
                actorProfileVisible = item.id in optIns,
                commentCount = commentCounts[item.id] ?: 0,
            )
        }
    }

    // ── Comments ──────────────────────────────────────────────────────

    suspend fun getComments(feedItemId: String, limit: Int = 50): List<FeedComment> = dbQuery {
        FeedComments
            .selectAll()
            .where { FeedComments.feedItemId eq feedItemId }
            .orderBy(FeedComments.createdAt, SortOrder.ASC)
            .limit(limit)
            .map { row ->
                FeedComment(
                    id = row[FeedComments.id].toString(),
                    feedItemId = row[FeedComments.feedItemId],
                    authorName = row[FeedComments.authorName],
                    text = row[FeedComments.text],
                    timestamp = row[FeedComments.createdAt],
                )
            }
    }

    suspend fun addComment(feedItemId: String, workerId: Int, text: String): FeedComment = dbQuery {
        val workerName = Workers.selectAll()
            .where { Workers.id eq workerId }
            .firstOrNull()?.get(Workers.name) ?: "Anonymous"

        val now = kotlinx.datetime.Clock.System.now()
        val id = FeedComments.insert {
            it[FeedComments.feedItemId] = feedItemId
            it[authorId] = workerId
            it[authorName] = workerName
            it[FeedComments.text] = text
            it[createdAt] = now
        }[FeedComments.id]

        FeedComment(
            id = id.toString(),
            feedItemId = feedItemId,
            authorName = workerName,
            text = text,
            timestamp = now,
        )
    }

    // ── Captions ──────────────────────────────────────────────────────

    suspend fun setCaption(feedItemId: String, workerId: Int, caption: String): FeedItem = dbQuery {
        val now = kotlinx.datetime.Clock.System.now()
        val existing = FeedCaptions.selectAll()
            .where { FeedCaptions.feedItemId eq feedItemId }
            .firstOrNull()

        if (existing != null) {
            FeedCaptions.update({ FeedCaptions.feedItemId eq feedItemId }) {
                it[FeedCaptions.caption] = caption
                it[updatedAt] = now
            }
        } else {
            FeedCaptions.insert {
                it[FeedCaptions.feedItemId] = feedItemId
                it[FeedCaptions.workerId] = workerId
                it[FeedCaptions.caption] = caption
                it[createdAt] = now
                it[updatedAt] = now
            }
        }

        // Return a minimal FeedItem with the updated caption
        FeedItem(
            id = feedItemId,
            type = FeedItemType.SPOT_REPORT, // placeholder — client ignores type for caption updates
            actorName = "",
            title = "",
            description = "",
            caption = caption,
            timestamp = now,
        )
    }

    // ── Profile Opt-in ────────────────────────────────────────────────

    suspend fun optInProfile(feedItemId: String, workerId: Int): FeedItem = dbQuery {
        val now = kotlinx.datetime.Clock.System.now()
        val existing = FeedProfileOptIns.selectAll()
            .where { FeedProfileOptIns.feedItemId eq feedItemId }
            .firstOrNull()

        if (existing == null) {
            FeedProfileOptIns.insert {
                it[FeedProfileOptIns.feedItemId] = feedItemId
                it[FeedProfileOptIns.workerId] = workerId
                it[createdAt] = now
            }
        }

        FeedItem(
            id = feedItemId,
            type = FeedItemType.SPOT_REPORT,
            actorName = "",
            title = "",
            description = "",
            actorProfileVisible = true,
            timestamp = now,
        )
    }
}
