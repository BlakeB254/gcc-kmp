package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.*
import com.gcc.shared.model.*
import org.jetbrains.exposed.sql.*

/**
 * Analytics and reporting service for admin dashboard.
 * Provides aggregate stats, daily metrics, and per-area breakdowns.
 */
class AnalyticsService(
    private val workerService: WorkerService,
    private val jobService: JobService,
    private val bountyService: BountyService,
) {

    /** Get aggregate stats for the admin dashboard overview. */
    suspend fun getAdminStats(): AdminStatsResponse = dbQuery {
        val totalWorkers = Workers.selectAll().count().toInt()
        val verifiedWorkers = Workers.selectAll()
            .map { it.toWorker() }
            .count { it.verification.status == VerificationStatus.APPROVED }
        val pendingVerifications = Workers.selectAll()
            .map { it.toWorker() }
            .count { it.verification.status == VerificationStatus.PENDING }
        val totalSponsors = Sponsors.selectAll().count().toInt()
        val totalJobs = Jobs.selectAll().count().toInt()
        val completedJobs = Jobs.selectAll()
            .where { Jobs.status eq "completed" }
            .count().toInt()
        val activeJobs = Jobs.selectAll()
            .where { (Jobs.status eq "claimed") or (Jobs.status eq "in_progress") }
            .count().toInt()
        val totalRevenue = Jobs.selectAll()
            .where { Jobs.payoutAmount.isNotNull() }
            .sumOf { it[Jobs.payoutAmount] ?: 0 }
        val totalRoutes = Routes.selectAll().count().toInt()
        val totalBlocks = Blocks.selectAll().count().toInt()
        val totalBounties = Bounties.selectAll().count().toInt()
        val activeBounties = Bounties.selectAll()
            .where {
                (Bounties.status eq "open") or (Bounties.status eq "first_refusal") or
                        (Bounties.status eq "claimed")
            }
            .count().toInt()
        val totalDisputes = Disputes.selectAll().count().toInt()
        val openDisputes = Disputes.selectAll()
            .where { (Disputes.status eq "open") or (Disputes.status eq "under_review") }
            .count().toInt()

        AdminStatsResponse(
            totalWorkers = totalWorkers,
            verifiedWorkers = verifiedWorkers,
            pendingVerifications = pendingVerifications,
            totalSponsors = totalSponsors,
            totalJobs = totalJobs,
            completedJobs = completedJobs,
            activeJobs = activeJobs,
            totalRevenue = totalRevenue,
            totalRoutes = totalRoutes,
            totalBlocks = totalBlocks,
            totalBounties = totalBounties,
            activeBounties = activeBounties,
            totalDisputes = totalDisputes,
            openDisputes = openDisputes,
        )
    }

    /** Get revenue breakdown by service area. */
    suspend fun getRevenueByArea(): List<RevenueByArea> = dbQuery {
        val areas = ServiceAreas.selectAll().map { row ->
            val areaId = row[ServiceAreas.id]
            val areaName = row[ServiceAreas.name]

            // Get blocks in this area
            val blockIds = Blocks.selectAll()
                .where { Blocks.serviceAreaId eq areaId }
                .map { it[Blocks.id] }

            // Get jobs for these blocks
            val areaJobs = if (blockIds.isNotEmpty()) {
                Jobs.selectAll()
                    .where { Jobs.blockId inList blockIds }
                    .toList()
            } else emptyList()

            val totalRevenue = areaJobs.sumOf { it[Jobs.payoutAmount] ?: 0 }
            val workerIds = areaJobs.mapNotNull { it[Jobs.workerId] }.distinct()

            RevenueByArea(
                serviceAreaId = areaId,
                serviceAreaName = areaName,
                totalRevenue = totalRevenue,
                jobCount = areaJobs.size,
                workerCount = workerIds.size,
            )
        }

        areas.sortedByDescending { it.totalRevenue }
    }

    /** Get top worker metrics for the admin dashboard. */
    suspend fun getWorkerMetrics(limit: Int = 20): List<WorkerMetrics> = dbQuery {
        Workers.selectAll()
            .orderBy(Workers.createdAt, SortOrder.ASC)
            .limit(200)
            .map { row ->
                val worker = row.toWorker()
                val volunteersCompleted = VolunteerCleanups.selectAll()
                    .where {
                        (VolunteerCleanups.workerId eq worker.id) and
                                (VolunteerCleanups.status eq "completed")
                    }
                    .count().toInt()
                val badgesEarned = WorkerBadges.selectAll()
                    .where {
                        (WorkerBadges.workerId eq worker.id) and
                                WorkerBadges.unlockedAt.isNotNull()
                    }
                    .count().toInt()

                WorkerMetrics(
                    workerId = worker.id,
                    workerName = worker.name,
                    jobsCompleted = worker.stats.jobsCompleted,
                    totalEarnings = worker.stats.totalEarnings,
                    averageRating = worker.stats.averageRating,
                    volunteersCompleted = volunteersCompleted,
                    badgesEarned = badgesEarned,
                )
            }
            .sortedByDescending { it.jobsCompleted }
            .take(limit)
    }

    /** Get sponsor metrics for the admin dashboard. */
    suspend fun getSponsorMetrics(limit: Int = 20): List<SponsorMetrics> = dbQuery {
        Sponsors.selectAll()
            .limit(limit)
            .map { row ->
                val sponsorId = row[Sponsors.id]
                val sponsorName = row[Sponsors.name]

                val totalFunded = Blocks.selectAll()
                    .where { Blocks.sponsorId eq sponsorId }
                    .sumOf { it[Blocks.fundingCurrent] }

                val blocksSponsored = Blocks.selectAll()
                    .where { Blocks.sponsorId eq sponsorId }
                    .count().toInt()

                val bountiesPosted = Bounties.selectAll()
                    .where {
                        (Bounties.posterType eq "sponsor") and
                                (Bounties.posterId eq sponsorId)
                    }
                    .count().toInt()

                val walletBalance = SponsorWallets.selectAll()
                    .where { SponsorWallets.sponsorId eq sponsorId }
                    .firstOrNull()?.let { it[SponsorWallets.totalBalance] } ?: 0

                SponsorMetrics(
                    sponsorId = sponsorId,
                    sponsorName = sponsorName,
                    totalFunded = totalFunded,
                    blocksSponsored = blocksSponsored,
                    bountiesPosted = bountiesPosted,
                    walletBalance = walletBalance,
                )
            }
            .sortedByDescending { it.totalFunded }
    }
}
