package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.Jobs
import com.gcc.shared.model.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.jetbrains.exposed.sql.*

class JobService {

    suspend fun getAll(
        status: JobStatus? = null,
        workerId: Int? = null,
        routeId: Int? = null,
        limit: Int = 50,
        offset: Long = 0
    ): List<Job> = dbQuery {
        val query = Jobs.selectAll()
        status?.let { query.andWhere { Jobs.status eq it.name.lowercase() } }
        workerId?.let { query.andWhere { Jobs.workerId eq it } }
        routeId?.let { query.andWhere { Jobs.routeId eq it } }

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

    /**
     * Claim an available job for a worker. Sets status to CLAIMED.
     * Validates: job exists, job is currently AVAILABLE.
     */
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

    /**
     * Start a claimed job. Sets status to IN_PROGRESS.
     * Validates: job exists, is CLAIMED, and belongs to the requesting worker.
     */
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

    /**
     * Complete an in-progress job. Sets status to COMPLETED and calculates duration.
     * Validates: job exists, is IN_PROGRESS, and belongs to the requesting worker.
     */
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
            it[durationMinutes] = duration
            it[updatedAt] = now
        }

        Jobs.selectAll().where { Jobs.id eq jobId }.first().toJob()
    }
}

fun ResultRow.toJob(): Job = Job(
    id = this[Jobs.id],
    blockId = this[Jobs.blockId],
    routeId = this[Jobs.routeId],
    workerId = this[Jobs.workerId],
    status = enumValueOfOrDefault(this[Jobs.status]),
    timing = JobTiming(
        claimedAt = this[Jobs.claimedAt],
        startedAt = this[Jobs.startedAt],
        completedAt = this[Jobs.completedAt],
        durationMinutes = this[Jobs.durationMinutes]
    ),
    photos = this[Jobs.photos]?.let { urls ->
        buildJsonArray { urls.forEach { add(JsonPrimitive(it)) } }
    },
    gpsPath = this[Jobs.gpsPath]?.let { path ->
        buildJsonArray {
            path.forEach { point ->
                add(buildJsonArray { point.forEach { add(JsonPrimitive(it)) } })
            }
        }
    },
    payoutAmount = this[Jobs.payoutAmount],
    payoutStatus = this[Jobs.payoutStatus]?.let { enumValueOfOrDefault<TransactionStatus>(it) },
    rating = this[Jobs.rating],
    reviewNotes = this[Jobs.reviewNotes],
    fundingSource = this[Jobs.fundingSource],
    bountyId = this[Jobs.bountyId],
    sponsorId = this[Jobs.sponsorId],
    completionReport = this[Jobs.completionReport],
    createdAt = this[Jobs.createdAt],
    updatedAt = this[Jobs.updatedAt]
)
