package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.Jobs
import com.gcc.shared.model.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.*

class JobService {

    suspend fun getAll(
        status: JobStatus? = null,
        workerId: Int? = null,
        limit: Int = 50,
        offset: Long = 0
    ): List<Job> = dbQuery {
        val query = Jobs.selectAll()
        status?.let { query.andWhere { Jobs.status eq it.name.lowercase() } }
        workerId?.let { query.andWhere { Jobs.workerId eq it } }

        query
            .orderBy(Jobs.createdAt, SortOrder.DESC)
            .limit(limit).offset(offset)
            .map { it.toJob() }
    }

    suspend fun getById(id: Int): Job? = dbQuery {
        Jobs.selectAll().where { Jobs.id eq id }
            .firstOrNull()?.toJob()
    }

    suspend fun getByWorker(workerId: Int, limit: Int = 20): List<Job> =
        getAll(workerId = workerId, limit = limit)

    suspend fun getAvailable(limit: Int = 50): List<Job> =
        getAll(status = JobStatus.AVAILABLE, limit = limit)

    suspend fun count(): Long = dbQuery {
        Jobs.selectAll().count()
    }

    suspend fun countByStatus(status: JobStatus): Long = dbQuery {
        Jobs.selectAll().where { Jobs.status eq status.name.lowercase() }.count()
    }

    suspend fun claimJob(jobId: Int, workerId: Int): Job = dbQuery {
        val job = Jobs.selectAll().where { Jobs.id eq jobId }.firstOrNull()?.toJob()
            ?: throw IllegalArgumentException("Job not found")

        if (job.status != JobStatus.AVAILABLE) {
            throw IllegalStateException("Job is not available (current status: ${job.status})")
        }

        val now = Clock.System.now()
        Jobs.update({ Jobs.id eq jobId }) {
            it[Jobs.workerId] = workerId
            it[status] = JobStatus.CLAIMED.name.lowercase()
            it[claimedAt] = now
            it[updatedAt] = now
        }

        Jobs.selectAll().where { Jobs.id eq jobId }.first().toJob()
    }

    suspend fun startJob(jobId: Int, workerId: Int): Job = dbQuery {
        val job = Jobs.selectAll().where { Jobs.id eq jobId }.firstOrNull()?.toJob()
            ?: throw IllegalArgumentException("Job not found")

        if (job.status != JobStatus.CLAIMED) {
            throw IllegalStateException("Job must be claimed before starting (current status: ${job.status})")
        }
        if (job.workerId != workerId) {
            throw IllegalStateException("Job belongs to a different worker")
        }

        val now = Clock.System.now()
        Jobs.update({ Jobs.id eq jobId }) {
            it[status] = JobStatus.IN_PROGRESS.name.lowercase()
            it[startedAt] = now
            it[updatedAt] = now
        }

        Jobs.selectAll().where { Jobs.id eq jobId }.first().toJob()
    }

    suspend fun completeJob(jobId: Int, workerId: Int): Job = dbQuery {
        val job = Jobs.selectAll().where { Jobs.id eq jobId }.firstOrNull()?.toJob()
            ?: throw IllegalArgumentException("Job not found")

        if (job.status != JobStatus.IN_PROGRESS) {
            throw IllegalStateException("Job must be in progress to complete (current status: ${job.status})")
        }
        if (job.workerId != workerId) {
            throw IllegalStateException("Job belongs to a different worker")
        }

        val now = Clock.System.now()
        val duration = job.timing.startedAt?.let { startedAt ->
            ((now - startedAt).inWholeMinutes).toInt()
        }

        Jobs.update({ Jobs.id eq jobId }) {
            it[status] = JobStatus.COMPLETED.name.lowercase()
            it[completedAt] = now
            it[updatedAt] = now
        }

        Jobs.selectAll().where { Jobs.id eq jobId }.first().toJob()
    }
}

/**
 * Map a Jobs row to the shared Job model.
 * Several model fields (routeId, fundingSource, bountyId, sponsorId, completionReport)
 * don't exist in the production Drizzle schema and are defaulted to null.
 */
fun ResultRow.toJob(): Job = Job(
    id = this[Jobs.id],
    blockId = this[Jobs.blockId],
    routeId = null, // not in production schema
    workerId = this[Jobs.workerId],
    status = enumValueOfOrDefault(this[Jobs.status]),
    timing = JobTiming(
        claimedAt = this[Jobs.claimedAt],
        startedAt = this[Jobs.startedAt],
        completedAt = this[Jobs.completedAt],
        durationMinutes = null, // not in production schema
    ),
    photos = (this[Jobs.beforePhotos] ?: this[Jobs.afterPhotos]) as? kotlinx.serialization.json.JsonArray,
    gpsPath = this[Jobs.gpsPath] as? kotlinx.serialization.json.JsonArray,
    payoutAmount = this[Jobs.payoutAmount]?.toIntOrNull(),
    payoutStatus = this[Jobs.payoutStatus]?.let { enumValueOfOrDefault<TransactionStatus>(it) },
    rating = this[Jobs.rating]?.toDoubleOrNull(),
    reviewNotes = this[Jobs.ratingNotes],
    fundingSource = null,     // not in production schema
    bountyId = null,          // not in production schema
    sponsorId = null,         // not in production schema
    completionReport = null,  // not in production schema
    createdAt = this[Jobs.createdAt],
    updatedAt = this[Jobs.updatedAt]
)
