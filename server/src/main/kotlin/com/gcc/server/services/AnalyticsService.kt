package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.*
import com.gcc.shared.model.*
import kotlinx.datetime.*
import org.jetbrains.exposed.sql.*

/**
 * Analytics and reporting service for admin dashboard.
 * Provides aggregate stats, daily metrics, and per-area breakdowns.
 */
class AnalyticsService(
    @Suppress("unused") private val workerService: WorkerService,
    @Suppress("unused") private val jobService: JobService,
    @Suppress("unused") private val bountyService: BountyService,
) {

    /** Get aggregate stats for the admin dashboard overview. */
    suspend fun getAdminStats(): AdminStatsResponse = dbQuery {
        val totalWorkers = Workers.selectAll().count().toInt()
        val verifiedWorkers = Workers.selectAll()
            .where { Workers.verificationStatus eq "approved" }
            .count().toInt()
        val pendingVerifications = Workers.selectAll()
            .where { Workers.verificationStatus eq "pending" }
            .count().toInt()
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
            .sumOf { (it[Jobs.payoutAmount]?.toIntOrNull() ?: 0) as Int }
        val totalRoutes = Routes.selectAll().count().toInt()
        val totalBlocks = Blocks.selectAll().count().toInt()

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
            totalBounties = 0,      // bounties table not yet in production
            activeBounties = 0,
            totalDisputes = 0,      // disputes table not yet in production
            openDisputes = 0,
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

            val totalRevenue = areaJobs.sumOf { (it[Jobs.payoutAmount]?.toIntOrNull() ?: 0) as Int }
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
                WorkerMetrics(
                    workerId = worker.id,
                    workerName = worker.name,
                    jobsCompleted = worker.stats.jobsCompleted,
                    totalEarnings = worker.stats.totalEarnings,
                    averageRating = worker.stats.averageRating,
                    volunteersCompleted = 0, // volunteer_cleanups table not yet in production
                    badgesEarned = 0,        // worker_badges table not yet in production
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
                    .where { Blocks.fundedById eq sponsorId }
                    .sumOf { (it[Blocks.fundingCurrent]?.toIntOrNull() ?: 0) as Int }

                val blocksSponsored = Blocks.selectAll()
                    .where { Blocks.fundedById eq sponsorId }
                    .count().toInt()

                SponsorMetrics(
                    sponsorId = sponsorId,
                    sponsorName = sponsorName,
                    totalFunded = totalFunded,
                    blocksSponsored = blocksSponsored,
                    bountiesPosted = 0,  // bounties table not yet in production
                    walletBalance = 0,   // sponsor_wallets table not yet in production
                )
            }
            .sortedByDescending { it.totalFunded }
    }

    /** Get recent activity feed for the admin dashboard. */
    suspend fun getActivity(limit: Int = 20): List<AdminActivityItem> = dbQuery {
        val items = mutableListOf<AdminActivityItem>()
        var nextId = 1

        // Recent worker registrations
        Workers.selectAll()
            .orderBy(Workers.createdAt, SortOrder.DESC)
            .limit(limit)
            .forEach { row ->
                items += AdminActivityItem(
                    id = nextId++,
                    type = AdminActivityType.WORKER_REGISTERED,
                    description = "${row[Workers.name]} registered as a worker",
                    actorName = row[Workers.name],
                    timestamp = row[Workers.createdAt],
                )
            }

        // Recent sponsor registrations
        Sponsors.selectAll()
            .orderBy(Sponsors.createdAt, SortOrder.DESC)
            .limit(limit)
            .forEach { row ->
                items += AdminActivityItem(
                    id = nextId++,
                    type = AdminActivityType.SPONSOR_REGISTERED,
                    description = "${row[Sponsors.name]} registered as a sponsor",
                    actorName = row[Sponsors.name],
                    timestamp = row[Sponsors.createdAt],
                )
            }

        // Sort all items by timestamp descending and take the limit
        items.sortedByDescending { it.timestamp }.take(limit)
    }

    /** Get daily stats for the past N days. */
    suspend fun getDailyStats(days: Int = 30): List<AdminDailyStats> = dbQuery {
        val now = Clock.System.now()
        val cutoff = now.minus(days.toLong() * 24 * 60 * 60, DateTimeUnit.SECOND)

        // Build a map of date → stats by scanning tables once
        val dateMap = mutableMapOf<String, MutableList<String>>()
        fun dateKey(instant: Instant): String =
            instant.toLocalDateTime(TimeZone.UTC).date.toString()

        // Count new workers per day
        val workersByDate = mutableMapOf<String, Int>()
        Workers.selectAll()
            .where { Workers.createdAt greaterEq cutoff }
            .forEach { row ->
                val key = dateKey(row[Workers.createdAt])
                workersByDate[key] = (workersByDate[key] ?: 0) + 1
            }

        // Count new sponsors per day
        val sponsorsByDate = mutableMapOf<String, Int>()
        Sponsors.selectAll()
            .where { Sponsors.createdAt greaterEq cutoff }
            .forEach { row ->
                val key = dateKey(row[Sponsors.createdAt])
                sponsorsByDate[key] = (sponsorsByDate[key] ?: 0) + 1
            }

        // Collect all dates
        val allDates = (workersByDate.keys + sponsorsByDate.keys).toSortedSet()

        allDates.map { date ->
            AdminDailyStats(
                date = date,
                jobsCompleted = 0,
                jobsCreated = 0,
                revenue = 0,
                newWorkers = workersByDate[date] ?: 0,
                newSponsors = sponsorsByDate[date] ?: 0,
                bountiesPosted = 0,
                bountiesClaimed = 0,
            )
        }
    }
}
