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

    // ── Job transitions (with optional admin bypass) ────────────────────

    suspend fun claimJob(jobId: Int, workerId: Int, isAdmin: Boolean = false): Job =
        transitionJob(jobId, JobStatus.CLAIMED, isAdmin) { job ->
            if (!isAdmin && job.status != JobStatus.AVAILABLE)
                throw IllegalStateException("Job is not available (current status: ${job.status})")
            mapOf(
                Jobs.workerId to workerId,
                Jobs.claimedAt to Clock.System.now(),
            )
        }

    suspend fun startJob(jobId: Int, workerId: Int, isAdmin: Boolean = false): Job =
        transitionJob(jobId, JobStatus.IN_PROGRESS, isAdmin) { job ->
            if (!isAdmin) {
                if (job.status != JobStatus.CLAIMED)
                    throw IllegalStateException("Job must be claimed before starting (current status: ${job.status})")
                if (job.workerId != workerId)
                    throw IllegalStateException("Job belongs to a different worker")
            }
            mapOf(
                Jobs.workerId to workerId,
                Jobs.startedAt to Clock.System.now(),
            )
        }

    suspend fun completeJob(jobId: Int, workerId: Int? = null, isAdmin: Boolean = false): Job =
        transitionJob(jobId, JobStatus.COMPLETED, isAdmin) { job ->
            if (!isAdmin) {
                if (job.status != JobStatus.IN_PROGRESS)
                    throw IllegalStateException("Job must be in progress to complete (current status: ${job.status})")
                if (job.workerId != workerId)
                    throw IllegalStateException("Job belongs to a different worker")
            }
            buildMap {
                put(Jobs.completedAt, Clock.System.now())
                if (isAdmin) {
                    put(Jobs.payoutAmount, "0")
                    put(Jobs.payoutStatus, "test_completion")
                }
            }
        }

    /**
     * Shared job transition: validate → update status + extra columns → return refreshed job.
     * [extraColumns] returns additional column values to set (beyond status and updatedAt).
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun transitionJob(
        jobId: Int,
        newStatus: JobStatus,
        isAdmin: Boolean,
        extraColumns: (Job) -> Map<Column<*>, Any?>,
    ): Job = dbQuery {
        val job = Jobs.selectAll().where { Jobs.id eq jobId }.firstOrNull()?.toJob()
            ?: throw IllegalArgumentException("Job not found")

        val extras = extraColumns(job)
        val now = Clock.System.now()

        Jobs.update({ Jobs.id eq jobId }) { stmt ->
            stmt[status] = newStatus.name.lowercase()
            stmt[updatedAt] = now
            for ((col, value) in extras) {
                when (value) {
                    is Int -> stmt[col as Column<Int?>] = value
                    is String -> stmt[col as Column<String?>] = value
                    is kotlinx.datetime.Instant -> stmt[col as Column<kotlinx.datetime.Instant?>] = value
                    null -> {} // skip nulls
                }
            }
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
