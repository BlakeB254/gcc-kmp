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
        val existing = Workers.selectAll().where { Workers.id eq workerId }
            .firstOrNull()?.toWorker() ?: return@dbQuery null

        val updatedVerification = existing.verification.copy(
            status = status,
            governmentIdStatus = governmentIdStatus ?: existing.verification.governmentIdStatus,
            proofOfAddressStatus = proofOfAddressStatus ?: existing.verification.proofOfAddressStatus,
            reviewNotes = reviewNotes ?: existing.verification.reviewNotes,
        )

        Workers.update({ Workers.id eq workerId }) {
            it[verification] = updatedVerification
            it[updatedAt] = now
        }

        Workers.selectAll().where { Workers.id eq workerId }
            .firstOrNull()?.toWorker()
    }

    /** Update a worker's role (admin promotion/demotion). */
    suspend fun updateWorkerRole(workerId: Int, role: String): Worker? = dbQuery {
        val now = Clock.System.now()
        Workers.update({ Workers.id eq workerId }) {
            it[Workers.role] = role
            it[updatedAt] = now
        }
        Workers.selectAll().where { Workers.id eq workerId }
            .firstOrNull()?.toWorker()
    }

    /** Bulk verify multiple workers at once. Returns count of updated workers. */
    suspend fun bulkVerifyWorkers(workerIds: List<Int>): Int = dbQuery {
        val now = Clock.System.now()
        var count = 0
        for (id in workerIds) {
            val existing = Workers.selectAll().where { Workers.id eq id }
                .firstOrNull()?.toWorker() ?: continue

            val updatedVerification = existing.verification.copy(
                status = VerificationStatus.APPROVED,
                governmentIdStatus = DocumentStatus.APPROVED,
                proofOfAddressStatus = DocumentStatus.APPROVED,
            )

            Workers.update({ Workers.id eq id }) {
                it[verification] = updatedVerification
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
            it[address] = newAddress
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
            .orderBy(Workers.createdAt, SortOrder.DESC)
            .limit(limit).offset(offset)
            .map { it.toWorker() }
            .filter { it.verification.status.name.equals(status, ignoreCase = true) }
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
