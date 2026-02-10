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

        // 1. Spot reports
        if (typeFilter == null || typeFilter == FeedItemType.SPOT_REPORT) {
            val spots = CommunityReports
                .join(Workers, JoinType.INNER) { CommunityReports.reporterId eq Workers.id }
                .selectAll()
                .orderBy(CommunityReports.createdAt, SortOrder.DESC)
                .limit(perSourceLimit)
                .map { row ->
                    FeedItem(
                        id = "spot_${row[CommunityReports.id]}",
                        type = FeedItemType.SPOT_REPORT,
                        actorName = row[Workers.name],
                        actorTier = enumValueOfOrDefault(row[Workers.earlyAdopterTier]),
                        title = "Reported ${row[CommunityReports.type]} sighting",
                        description = row[CommunityReports.description] ?: "Community report submitted",
                        mediaUrls = row[CommunityReports.mediaUrls],
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
                        amount = row[Jobs.payoutAmount],
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
        items.drop(offset).take(limit)
    }
}
