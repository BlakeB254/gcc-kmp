package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.SponsorMilestones
import com.gcc.server.db.tables.WorkerBadges
import com.gcc.shared.model.*
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*

class GamificationService {

    // ---- Badge definitions (server-side source of truth) ----

    private data class BadgeDef(
        val id: String,
        val category: BadgeCategory,
        val tier: BadgeTier,
        val name: String,
        val description: String,
        val requirement: Int,
    )

    private val badgeDefinitions = listOf(
        // Jobs
        BadgeDef("jobs_bronze", BadgeCategory.JOBS, BadgeTier.BRONZE, "Getting Started", "Complete 5 jobs", 5),
        BadgeDef("jobs_silver", BadgeCategory.JOBS, BadgeTier.SILVER, "Steady Worker", "Complete 25 jobs", 25),
        BadgeDef("jobs_gold", BadgeCategory.JOBS, BadgeTier.GOLD, "Pro Cleaner", "Complete 100 jobs", 100),
        BadgeDef("jobs_platinum", BadgeCategory.JOBS, BadgeTier.PLATINUM, "Elite Cleaner", "Complete 500 jobs", 500),
        BadgeDef("jobs_diamond", BadgeCategory.JOBS, BadgeTier.DIAMOND, "Legend", "Complete 2000 jobs", 2000),
        // Observe
        BadgeDef("observe_bronze", BadgeCategory.OBSERVE, BadgeTier.BRONZE, "Sharp Eyes", "Submit 5 spot reports", 5),
        BadgeDef("observe_silver", BadgeCategory.OBSERVE, BadgeTier.SILVER, "Watchful", "Submit 25 spot reports", 25),
        BadgeDef("observe_gold", BadgeCategory.OBSERVE, BadgeTier.GOLD, "Community Scout", "Submit 100 spot reports", 100),
        BadgeDef("observe_platinum", BadgeCategory.OBSERVE, BadgeTier.PLATINUM, "Sentinel", "Submit 500 spot reports", 500),
        BadgeDef("observe_diamond", BadgeCategory.OBSERVE, BadgeTier.DIAMOND, "All-Seeing Eye", "Submit 2000 spot reports", 2000),
        // Volunteer
        BadgeDef("volunteer_bronze", BadgeCategory.VOLUNTEER, BadgeTier.BRONZE, "Good Samaritan", "Complete 3 volunteer cleanups", 3),
        BadgeDef("volunteer_silver", BadgeCategory.VOLUNTEER, BadgeTier.SILVER, "Dedicated Volunteer", "Complete 10 volunteer cleanups", 10),
        BadgeDef("volunteer_gold", BadgeCategory.VOLUNTEER, BadgeTier.GOLD, "Community Hero", "Complete 50 volunteer cleanups", 50),
        BadgeDef("volunteer_platinum", BadgeCategory.VOLUNTEER, BadgeTier.PLATINUM, "Cleanup Champion", "Complete 200 volunteer cleanups", 200),
        BadgeDef("volunteer_diamond", BadgeCategory.VOLUNTEER, BadgeTier.DIAMOND, "Volunteer Legend", "Complete 1000 volunteer cleanups", 1000),
        // First Responder
        BadgeDef("first_responder_bronze", BadgeCategory.FIRST_RESPONDER, BadgeTier.BRONZE, "Quick Responder", "Accept 3 bounties within 5 min", 3),
        BadgeDef("first_responder_silver", BadgeCategory.FIRST_RESPONDER, BadgeTier.SILVER, "Rapid Responder", "Accept 10 bounties within 5 min", 10),
        BadgeDef("first_responder_gold", BadgeCategory.FIRST_RESPONDER, BadgeTier.GOLD, "First on Scene", "Accept 25 bounties within 5 min", 25),
        // Streak
        BadgeDef("streak_bronze", BadgeCategory.STREAK, BadgeTier.BRONZE, "Consistent", "3-day streak", 3),
        BadgeDef("streak_silver", BadgeCategory.STREAK, BadgeTier.SILVER, "Dedicated", "7-day streak", 7),
        BadgeDef("streak_gold", BadgeCategory.STREAK, BadgeTier.GOLD, "Unstoppable", "30-day streak", 30),
        // Community
        BadgeDef("community_bronze", BadgeCategory.COMMUNITY, BadgeTier.BRONZE, "Team Player", "5 community interactions", 5),
        BadgeDef("community_silver", BadgeCategory.COMMUNITY, BadgeTier.SILVER, "Community Builder", "25 community interactions", 25),
        BadgeDef("community_gold", BadgeCategory.COMMUNITY, BadgeTier.GOLD, "Community Leader", "100 community interactions", 100),
    )

    // ---- Sponsor milestone definitions ----

    private data class MilestoneDef(
        val type: SponsorMilestoneType,
        val tier: BadgeTier,
        val name: String,
        val description: String,
        val requirement: Int,
    )

    private val milestoneDefinitions = listOf(
        MilestoneDef(SponsorMilestoneType.TOTAL_FUNDED, BadgeTier.BRONZE, "Community Starter", "Fund $100 in cleanup", 10000),
        MilestoneDef(SponsorMilestoneType.TOTAL_FUNDED, BadgeTier.SILVER, "Community Supporter", "Fund $500 in cleanup", 50000),
        MilestoneDef(SponsorMilestoneType.TOTAL_FUNDED, BadgeTier.GOLD, "Impact Maker", "Fund $2,000 in cleanup", 200000),
        MilestoneDef(SponsorMilestoneType.TOTAL_FUNDED, BadgeTier.PLATINUM, "Impact Champion", "Fund $10,000 in cleanup", 1000000),
        MilestoneDef(SponsorMilestoneType.TOTAL_FUNDED, BadgeTier.DIAMOND, "Cleanup Mogul", "Fund $50,000 in cleanup", 5000000),
        MilestoneDef(SponsorMilestoneType.JOBS_FUNDED, BadgeTier.BRONZE, "First Sponsor", "Fund 5 jobs", 5),
        MilestoneDef(SponsorMilestoneType.JOBS_FUNDED, BadgeTier.SILVER, "Active Sponsor", "Fund 25 jobs", 25),
        MilestoneDef(SponsorMilestoneType.JOBS_FUNDED, BadgeTier.GOLD, "Major Sponsor", "Fund 100 jobs", 100),
        MilestoneDef(SponsorMilestoneType.BOUNTIES_FUNDED, BadgeTier.BRONZE, "Bounty Poster", "Fund 3 bounties", 3),
        MilestoneDef(SponsorMilestoneType.BOUNTIES_FUNDED, BadgeTier.SILVER, "Bounty Hunter", "Fund 15 bounties", 15),
        MilestoneDef(SponsorMilestoneType.BOUNTIES_FUNDED, BadgeTier.GOLD, "Bounty Baron", "Fund 50 bounties", 50),
    )

    /**
     * Get all badge definitions as Badge objects (for the client to display requirements).
     */
    fun getAllBadgeDefinitions(): List<Badge> =
        badgeDefinitions.map { def ->
            Badge(
                id = def.id,
                category = def.category,
                tier = def.tier,
                name = def.name,
                description = def.description,
                requirement = def.requirement,
            )
        }

    /**
     * Get a worker's earned badges (and progress toward unearned ones).
     */
    suspend fun getWorkerBadges(workerId: Int): List<Badge> = dbQuery {
        val earned = WorkerBadges.selectAll()
            .where { WorkerBadges.workerId eq workerId }
            .associate { it[WorkerBadges.badgeId] to it }

        badgeDefinitions.map { def ->
            val row = earned[def.id]
            Badge(
                id = def.id,
                category = def.category,
                tier = def.tier,
                name = def.name,
                description = def.description,
                requirement = def.requirement,
                progress = row?.get(WorkerBadges.progress) ?: 0,
                isUnlocked = row?.get(WorkerBadges.unlockedAt) != null,
                unlockedAt = row?.get(WorkerBadges.unlockedAt),
            )
        }
    }

    /**
     * Check thresholds and award any newly-earned badges for a worker.
     * Returns only the newly-unlocked badges.
     */
    suspend fun checkAndAwardBadges(workerId: Int, category: BadgeCategory, currentCount: Int): List<Badge> = dbQuery {
        val now = Clock.System.now()
        val newlyUnlocked = mutableListOf<Badge>()

        badgeDefinitions.filter { it.category == category }.forEach { def ->
            val existing = WorkerBadges.selectAll()
                .where { (WorkerBadges.workerId eq workerId) and (WorkerBadges.badgeId eq def.id) }
                .firstOrNull()

            if (existing == null) {
                // Create badge record
                val isUnlocked = currentCount >= def.requirement
                WorkerBadges.insert {
                    it[WorkerBadges.workerId] = workerId
                    it[badgeId] = def.id
                    it[WorkerBadges.category] = def.category.name.lowercase()
                    it[tier] = def.tier.name.lowercase()
                    it[name] = def.name
                    it[description] = def.description
                    it[requirement] = def.requirement
                    it[progress] = currentCount
                    it[unlockedAt] = if (isUnlocked) now else null
                    it[createdAt] = now
                }
                if (isUnlocked) {
                    newlyUnlocked.add(Badge(
                        id = def.id, category = def.category, tier = def.tier,
                        name = def.name, description = def.description,
                        requirement = def.requirement, progress = currentCount,
                        isUnlocked = true, unlockedAt = now,
                    ))
                }
            } else if (existing[WorkerBadges.unlockedAt] == null && currentCount >= def.requirement) {
                // Upgrade to unlocked
                WorkerBadges.update({
                    (WorkerBadges.workerId eq workerId) and (WorkerBadges.badgeId eq def.id)
                }) {
                    it[progress] = currentCount
                    it[unlockedAt] = now
                }
                newlyUnlocked.add(Badge(
                    id = def.id, category = def.category, tier = def.tier,
                    name = def.name, description = def.description,
                    requirement = def.requirement, progress = currentCount,
                    isUnlocked = true, unlockedAt = now,
                ))
            } else {
                // Just update progress
                WorkerBadges.update({
                    (WorkerBadges.workerId eq workerId) and (WorkerBadges.badgeId eq def.id)
                }) {
                    it[progress] = currentCount
                }
            }
        }

        newlyUnlocked
    }

    /**
     * Get a sponsor's milestones with progress.
     */
    suspend fun getSponsorMilestones(sponsorId: Int): List<SponsorMilestone> = dbQuery {
        val existing = SponsorMilestones.selectAll()
            .where { SponsorMilestones.sponsorId eq sponsorId }
            .map { it.toSponsorMilestone() }

        if (existing.isEmpty()) {
            // Initialize milestones for this sponsor
            val now = Clock.System.now()
            milestoneDefinitions.map { def ->
                val id = SponsorMilestones.insert {
                    it[SponsorMilestones.sponsorId] = sponsorId
                    it[milestoneType] = def.type.name.lowercase()
                    it[tier] = def.tier.name.lowercase()
                    it[name] = def.name
                    it[description] = def.description
                    it[requirement] = def.requirement
                    it[createdAt] = now
                }[SponsorMilestones.id]

                SponsorMilestone(
                    id = id,
                    sponsorId = sponsorId,
                    milestoneType = def.type,
                    tier = def.tier,
                    name = def.name,
                    description = def.description,
                    requirement = def.requirement,
                    currentValue = 0,
                    achievedAt = null,
                    createdAt = now,
                )
            }
        } else {
            existing
        }
    }

    /**
     * Check and award sponsor milestones. Returns newly-achieved milestones.
     */
    suspend fun checkAndAwardSponsorMilestones(
        sponsorId: Int,
        type: SponsorMilestoneType,
        currentValue: Int,
    ): List<SponsorMilestone> = dbQuery {
        val now = Clock.System.now()
        val newlyAchieved = mutableListOf<SponsorMilestone>()

        SponsorMilestones.selectAll()
            .where {
                (SponsorMilestones.sponsorId eq sponsorId) and
                        (SponsorMilestones.milestoneType eq type.name.lowercase())
            }
            .forEach { row ->
                val milestone = row.toSponsorMilestone()
                if (milestone.achievedAt == null && currentValue >= milestone.requirement) {
                    SponsorMilestones.update({ SponsorMilestones.id eq milestone.id }) {
                        it[SponsorMilestones.currentValue] = currentValue
                        it[achievedAt] = now
                    }
                    newlyAchieved.add(milestone.copy(currentValue = currentValue, achievedAt = now))
                } else if (milestone.achievedAt == null) {
                    SponsorMilestones.update({ SponsorMilestones.id eq milestone.id }) {
                        it[SponsorMilestones.currentValue] = currentValue
                    }
                }
            }

        newlyAchieved
    }

    /**
     * Simple leaderboard by category.
     */
    suspend fun getLeaderboard(
        category: BadgeCategory? = null,
        limit: Int = 10,
    ): List<Badge> = dbQuery {
        val query = WorkerBadges.selectAll()
            .where { WorkerBadges.unlockedAt.isNotNull() }

        category?.let {
            query.andWhere { WorkerBadges.category eq it.name.lowercase() }
        }

        query
            .orderBy(WorkerBadges.unlockedAt, SortOrder.DESC)
            .limit(limit)
            .map { row ->
                Badge(
                    id = row[WorkerBadges.badgeId],
                    category = enumValueOfOrDefault(row[WorkerBadges.category]),
                    tier = enumValueOfOrDefault(row[WorkerBadges.tier]),
                    name = row[WorkerBadges.name],
                    description = row[WorkerBadges.description],
                    requirement = row[WorkerBadges.requirement],
                    progress = row[WorkerBadges.progress],
                    isUnlocked = true,
                    unlockedAt = row[WorkerBadges.unlockedAt],
                )
            }
    }
}

fun ResultRow.toSponsorMilestone(): SponsorMilestone = SponsorMilestone(
    id = this[SponsorMilestones.id],
    sponsorId = this[SponsorMilestones.sponsorId],
    milestoneType = enumValueOfOrDefault(this[SponsorMilestones.milestoneType]),
    tier = enumValueOfOrDefault(this[SponsorMilestones.tier]),
    name = this[SponsorMilestones.name],
    description = this[SponsorMilestones.description],
    requirement = this[SponsorMilestones.requirement],
    currentValue = this[SponsorMilestones.currentValue],
    achievedAt = this[SponsorMilestones.achievedAt],
    createdAt = this[SponsorMilestones.createdAt],
)
