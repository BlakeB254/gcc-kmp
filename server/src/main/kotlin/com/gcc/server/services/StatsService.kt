package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.*
import com.gcc.shared.model.LeaderboardEntry
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*

@Serializable
data class PublicStats(
    val totalWorkers: Long,
    val totalSponsors: Long,
    val totalBlocks: Long,
    val fundedBlocks: Long,
    val completedJobs: Long,
    val totalRoutes: Long,
    val activeServiceAreas: Long,
    val waitlistCount: Long
)

class StatsService(
    private val workerService: WorkerService,
    private val blockService: BlockService,
    private val jobService: JobService,
    private val routeService: RouteService,
    private val serviceAreaService: ServiceAreaService,
    private val gamificationService: GamificationService
) {

    suspend fun getPublicStats(): PublicStats = dbQuery {
        PublicStats(
            totalWorkers = Workers.selectAll().count(),
            totalSponsors = Sponsors.selectAll().count(),
            totalBlocks = Blocks.selectAll().count(),
            fundedBlocks = Blocks.selectAll()
                .where { Blocks.status eq "funded" }.count(),
            completedJobs = Jobs.selectAll()
                .where { Jobs.status eq "completed" }.count(),
            totalRoutes = Routes.selectAll().count(),
            activeServiceAreas = ServiceAreas.selectAll()
                .where { ServiceAreas.status eq "active" }.count(),
            waitlistCount = WaitlistEntries.selectAll().count()
        )
    }

    suspend fun getLeaderboard(limit: Int = 10): List<LeaderboardEntry> {
        val topWorkers = workerService.getLeaderboard(limit)
        return topWorkers.mapIndexed { index, worker ->
            val badges = gamificationService.getWorkerBadges(worker.id)
            val badgeCount = badges.count { it.isUnlocked }
            LeaderboardEntry(
                rank = index + 1,
                workerId = worker.id,
                name = worker.name,
                jobsCompleted = worker.stats.jobsCompleted,
                totalEarnings = worker.stats.totalEarnings,
                averageRating = worker.stats.averageRating,
                earlyAdopterTier = worker.earlyAdopterTier,
                badgeCount = badgeCount
            )
        }
    }
}
