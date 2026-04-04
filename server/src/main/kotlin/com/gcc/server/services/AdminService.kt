package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.*
import com.gcc.shared.model.*
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

/**
 * Administrative operations: worker verification, bulk actions, address changes.
 */
class AdminService(
    private val workerService: WorkerService,
) {

    /** Verify a worker's identity documents and update their verification status. */
    suspend fun verifyWorker(
        workerId: Int,
        status: VerificationStatus,
        governmentIdStatus: DocumentStatus?,
        proofOfAddressStatus: DocumentStatus?,
        reviewNotes: String?,
    ): Worker? = dbQuery {
        val now = Clock.System.now()
        val existingRow = Workers.selectAll().where { Workers.id eq workerId }
            .firstOrNull() ?: return@dbQuery null

        // Block approval if required documents haven't been submitted
        if (status == VerificationStatus.APPROVED) {
            val hasGovId = existingRow[Workers.verificationGovernmentIdId] != null
            val hasProofOfAddress = existingRow[Workers.verificationProofOfAddressId] != null
            if (!hasGovId || !hasProofOfAddress) {
                throw IllegalStateException("Cannot approve: worker has not submitted required verification documents")
            }
        }

        Workers.update({ Workers.id eq workerId }) {
            it[verificationStatus] = status.name.lowercase()
            governmentIdStatus?.let { govStatus ->
                it[verificationGovernmentIdStatus] = govStatus.name.lowercase()
            }
            proofOfAddressStatus?.let { poaStatus ->
                it[verificationProofOfAddressStatus] = poaStatus.name.lowercase()
            }
            reviewNotes?.let { notes ->
                it[verificationReviewNotes] = notes
            }
            it[verificationReviewedAt] = now
            it[updatedAt] = now
        }

        Workers.selectAll().where { Workers.id eq workerId }
            .firstOrNull()?.toWorker()
    }

    /** Update a worker's role — role is managed via JWT claims, not a DB column. */
    suspend fun updateWorkerRole(workerId: Int, role: String): Worker? = dbQuery {
        // Role is not stored in the production DB; it's managed via JWT claims.
        // This is a no-op for now until a role column is added via migration.
        Workers.selectAll().where { Workers.id eq workerId }
            .firstOrNull()?.toWorker()
    }

    /** Bulk verify multiple workers at once. Only approves workers who have submitted documents. */
    suspend fun bulkVerifyWorkers(workerIds: List<Int>): Int = dbQuery {
        val now = Clock.System.now()
        var count = 0
        for (id in workerIds) {
            val row = Workers.selectAll().where { Workers.id eq id }
                .firstOrNull() ?: continue

            // Skip workers who haven't submitted required documents
            val hasGovId = row[Workers.verificationGovernmentIdId] != null
            val hasProofOfAddress = row[Workers.verificationProofOfAddressId] != null
            if (!hasGovId || !hasProofOfAddress) continue

            Workers.update({ Workers.id eq id }) {
                it[verificationStatus] = "approved"
                it[verificationGovernmentIdStatus] = "approved"
                it[verificationProofOfAddressStatus] = "approved"
                it[verificationReviewedAt] = now
                it[updatedAt] = now
            }
            count++
        }
        count
    }

    /** Update a worker's address (admin-initiated address change). */
    suspend fun updateWorkerAddress(workerId: Int, newAddress: WorkerAddress): Worker? = dbQuery {
        val now = Clock.System.now()
        Workers.update({ Workers.id eq workerId }) {
            it[addressStreet] = newAddress.street
            it[addressCity] = newAddress.city
            it[addressState] = newAddress.state
            it[addressZip] = newAddress.zip
            newAddress.neighborhood?.let { n -> it[addressNeighborhood] = n }
            newAddress.isNorthLawndale?.let { v -> it[addressIsNorthLawndale] = v }
            it[updatedAt] = now
        }
        Workers.selectAll().where { Workers.id eq workerId }
            .firstOrNull()?.toWorker()
    }

    /** Get workers filtered by verification status. */
    suspend fun getWorkersByVerificationStatus(
        status: String,
        limit: Int = 50,
        offset: Long = 0,
    ): List<Worker> = dbQuery {
        Workers.selectAll()
            .andWhere { Workers.verificationStatus eq status.lowercase() }
            .orderBy(Workers.createdAt, SortOrder.DESC)
            .limit(limit).offset(offset)
            .map { it.toWorker() }
    }

    /** Get all workers (admin list view with pagination). */
    suspend fun getAllWorkers(
        limit: Int = 50,
        offset: Long = 0,
        search: String? = null,
    ): List<Worker> = dbQuery {
        val query = Workers.selectAll()
        search?.let { term ->
            query.andWhere {
                (Workers.name like "%$term%") or (Workers.email like "%$term%")
            }
        }
        query.orderBy(Workers.createdAt, SortOrder.DESC)
            .limit(limit).offset(offset)
            .map { it.toWorker() }
    }

    /** Delete a worker (soft delete could be added later; for now hard delete). */
    suspend fun deleteWorker(workerId: Int): Boolean = dbQuery {
        Workers.deleteWhere { Workers.id eq workerId } > 0
    }
}
