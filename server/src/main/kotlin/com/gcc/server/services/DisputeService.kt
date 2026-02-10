package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.Disputes
import com.gcc.shared.model.*
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*

/**
 * Dispute management: create, review, resolve disputes between workers and sponsors.
 *
 * Disputes go through: OPEN → UNDER_REVIEW → RESOLVED or ESCALATED
 * Resolution options: REFUND_FULL, REFUND_PARTIAL, PAY_WORKER, SPLIT, DISMISSED, ESCALATED
 */
class DisputeService(
    private val walletService: WalletService,
) {

    suspend fun create(
        jobId: Int,
        reporterId: Int,
        reporterType: String,
        respondentId: Int,
        respondentType: String,
        type: String,
        description: String,
        evidence: List<String> = emptyList(),
    ): Dispute = dbQuery {
        val now = Clock.System.now()
        val id = Disputes.insert {
            it[Disputes.jobId] = jobId
            it[Disputes.reporterId] = reporterId
            it[Disputes.reporterType] = reporterType
            it[Disputes.respondentId] = respondentId
            it[Disputes.respondentType] = respondentType
            it[Disputes.type] = type
            it[status] = "open"
            it[Disputes.description] = description
            it[Disputes.evidence] = evidence
            it[createdAt] = now
            it[updatedAt] = now
        }[Disputes.id]

        Disputes.selectAll().where { Disputes.id eq id }.first().toDispute()
    }

    suspend fun getById(id: Int): Dispute? = dbQuery {
        Disputes.selectAll().where { Disputes.id eq id }
            .firstOrNull()?.toDispute()
    }

    suspend fun getAll(
        status: String? = null,
        limit: Int = 50,
        offset: Long = 0,
    ): List<Dispute> = dbQuery {
        val query = Disputes.selectAll()
        status?.let { query.andWhere { Disputes.status eq it } }
        query.orderBy(Disputes.createdAt, SortOrder.DESC)
            .limit(limit).offset(offset)
            .map { it.toDispute() }
    }

    suspend fun getByJob(jobId: Int): List<Dispute> = dbQuery {
        Disputes.selectAll()
            .where { Disputes.jobId eq jobId }
            .orderBy(Disputes.createdAt, SortOrder.DESC)
            .map { it.toDispute() }
    }

    /** Move dispute to under-review status. */
    suspend fun startReview(disputeId: Int): Dispute? = dbQuery {
        val now = Clock.System.now()
        Disputes.update({ Disputes.id eq disputeId }) {
            it[status] = "under_review"
            it[updatedAt] = now
        }
        Disputes.selectAll().where { Disputes.id eq disputeId }
            .firstOrNull()?.toDispute()
    }

    /**
     * Resolve a dispute with a specific resolution type.
     *
     * - REFUND_FULL: Full refund to sponsor's wallet
     * - REFUND_PARTIAL: Partial refund (amount specified)
     * - PAY_WORKER: Worker gets paid, sponsor absorbs cost
     * - SPLIT: Split payment between worker and sponsor
     * - DISMISSED: No action taken
     * - ESCALATED: Escalate to higher authority
     */
    suspend fun resolve(
        disputeId: Int,
        adminId: Int,
        resolution: String,
        notes: String,
        refundAmount: Int? = null,
    ): Dispute? = dbQuery {
        val now = Clock.System.now()
        Disputes.update({ Disputes.id eq disputeId }) {
            it[Disputes.resolution] = resolution
            it[resolutionNotes] = notes
            it[resolvedBy] = adminId
            it[resolvedAt] = now
            it[status] = if (resolution == "escalated") "escalated" else "resolved"
            it[updatedAt] = now
        }

        Disputes.selectAll().where { Disputes.id eq disputeId }
            .firstOrNull()?.toDispute()
    }

    suspend fun countByStatus(): Map<String, Long> = dbQuery {
        val results = mutableMapOf<String, Long>()
        for (status in listOf("open", "under_review", "resolved", "escalated")) {
            results[status] = Disputes.selectAll()
                .where { Disputes.status eq status }
                .count()
        }
        results
    }
}

fun ResultRow.toDispute(): Dispute = Dispute(
    id = this[Disputes.id],
    jobId = this[Disputes.jobId],
    reporterId = this[Disputes.reporterId],
    reporterType = enumValueOfOrDefault(this[Disputes.reporterType]),
    respondentId = this[Disputes.respondentId],
    respondentType = enumValueOfOrDefault(this[Disputes.respondentType]),
    type = enumValueOfOrDefault(this[Disputes.type]),
    status = enumValueOfOrDefault(this[Disputes.status]),
    description = this[Disputes.description],
    evidence = this[Disputes.evidence],
    resolution = this[Disputes.resolution]?.let { enumValueOfOrDefault<DisputeResolution>(it) },
    resolutionNotes = this[Disputes.resolutionNotes],
    resolvedBy = this[Disputes.resolvedBy],
    resolvedAt = this[Disputes.resolvedAt],
    createdAt = this[Disputes.createdAt],
    updatedAt = this[Disputes.updatedAt],
)
