package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.Blocks
import com.gcc.server.db.tables.Jobs
import com.gcc.shared.model.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.*

class JobService {

    suspend fun getAll(
        status: JobStatus? = null,
        workerId: Int? = null,
        routeId: Int? = null,
        limit: Int = 50,
        offset: Long = 0
    ): List<Job> = dbQuery {
        val routeBlockIds = routeId?.let { id ->
            Blocks.select(Blocks.id)
                .where { Blocks.routeId eq id }
                .map { it[Blocks.id] }
        }
        if (routeBlockIds != null && routeBlockIds.isEmpty()) {
            return@dbQuery emptyList()
        }

        val query = Jobs.selectAll()
        status?.let { query.andWhere { Jobs.status eq it.name.lowercase() } }
        workerId?.let { query.andWhere { Jobs.workerId eq it } }
        routeBlockIds?.let { ids -> query.andWhere { Jobs.blockId inList ids } }

        val rows = query
            .orderBy(Jobs.createdAt, SortOrder.DESC)
            .limit(limit).offset(offset)

        val blockIds = rows.map { it[Jobs.blockId] }.distinct()
        val routeIdsByBlock = if (blockIds.isEmpty()) {
            emptyMap()
        } else {
            Blocks.select(Blocks.id, Blocks.routeId)
                .where { Blocks.id inList blockIds }
                .associate { it[Blocks.id] to it[Blocks.routeId] }
        }

        rows.map { it.toJob(routeId = routeIdsByBlock[it[Jobs.blockId]]) }
    }

    suspend fun getById(id: Int): Job? = dbQuery {
        val row = Jobs.selectAll().where { Jobs.id eq id }
            .firstOrNull() ?: return@dbQuery null
        val routeId = Blocks.select(Blocks.routeId)
            .where { Blocks.id eq row[Jobs.blockId] }
            .firstOrNull()
            ?.get(Blocks.routeId)
        row.toJob(routeId = routeId)
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
                    put(Jobs.payoutStatus, TransactionStatus.COMPLETED.name.lowercase())
                }
            }
        }

    suspend fun declineJob(jobId: Int, workerId: Int, isAdmin: Boolean = false): Job = dbQuery {
        val row = Jobs.selectAll().where { Jobs.id eq jobId }.firstOrNull()
            ?: throw IllegalArgumentException("Job not found")
        val routeId = Blocks.select(Blocks.routeId)
            .where { Blocks.id eq row[Jobs.blockId] }
            .firstOrNull()
            ?.get(Blocks.routeId)
        val job = row.toJob(routeId = routeId)
        if (!isAdmin && job.workerId != workerId) {
            throw IllegalStateException("Job belongs to a different worker")
        }
        if (job.status != JobStatus.CLAIMED) {
            throw IllegalStateException("Only assigned jobs can be declined before start")
        }

        val now = Clock.System.now()
        Jobs.update({ Jobs.id eq jobId }) {
            it[Jobs.workerId] = null
            it[Jobs.status] = JobStatus.AVAILABLE.name.lowercase()
            it[Jobs.claimedAt] = null
            it[Jobs.payoutStatus] = null
            it[Jobs.updatedAt] = now
        }

        Jobs.selectAll().where { Jobs.id eq jobId }.first().toJob(routeId = routeId)
    }

    suspend fun addEvidence(
        jobId: Int,
        workerId: Int,
        isAdmin: Boolean,
        request: JobEvidenceRequest,
    ): Job = dbQuery {
        val row = Jobs.selectAll().where { Jobs.id eq jobId }.firstOrNull()
            ?: throw IllegalArgumentException("Job not found")
        val job = row.toJob()
        if (!isAdmin && job.workerId != workerId) {
            throw IllegalStateException("Job belongs to a different worker")
        }

        val now = Clock.System.now()
        val evidence = JsonObject(
            buildMap {
                request.checkpointId?.let { put("checkpointId", JsonPrimitive(it)) }
                put("url", JsonPrimitive(request.beforePhotoUrl))
                put("capturedAt", JsonPrimitive(now.toString()))
            }
        )
        val afterEvidence = JsonObject(
            buildMap {
                request.checkpointId?.let { put("checkpointId", JsonPrimitive(it)) }
                put("url", JsonPrimitive(request.afterPhotoUrl))
                put("capturedAt", JsonPrimitive(now.toString()))
            }
        )
        val beforePhotos = appendJsonArray(row[Jobs.beforePhotos] as? JsonArray, evidence)
        val afterPhotos = appendJsonArray(row[Jobs.afterPhotos] as? JsonArray, afterEvidence)

        Jobs.update({ Jobs.id eq jobId }) {
            it[Jobs.beforePhotos] = beforePhotos
            it[Jobs.afterPhotos] = afterPhotos
            it[updatedAt] = now
        }

        val refreshed = Jobs.selectAll().where { Jobs.id eq jobId }.first()
        val routeId = Blocks.select(Blocks.routeId)
            .where { Blocks.id eq refreshed[Jobs.blockId] }
            .firstOrNull()
            ?.get(Blocks.routeId)
        refreshed.toJob(routeId = routeId)
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
        val row = Jobs.selectAll().where { Jobs.id eq jobId }.firstOrNull()
            ?: throw IllegalArgumentException("Job not found")
        val routeId = Blocks.select(Blocks.routeId)
            .where { Blocks.id eq row[Jobs.blockId] }
            .firstOrNull()
            ?.get(Blocks.routeId)
        val job = row.toJob(routeId = routeId)

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

        Jobs.selectAll().where { Jobs.id eq jobId }.first().toJob(routeId = routeId)
    }
}

private fun appendJsonArray(existing: JsonArray?, item: JsonObject): JsonArray =
    JsonArray((existing?.toMutableList() ?: mutableListOf()).also { it.add(item) })

/**
 * Map a Jobs row to the shared Job model.
 * Several model fields (routeId, fundingSource, bountyId, sponsorId, completionReport)
 * don't exist in the production Drizzle schema and are defaulted to null.
 */
fun ResultRow.toJob(routeId: Int? = null): Job = Job(
    id = this[Jobs.id],
    blockId = this[Jobs.blockId],
    routeId = routeId,
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
