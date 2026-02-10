package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.Workers
import com.gcc.shared.model.*
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*

class WorkerService {

    suspend fun getAll(limit: Int = 50, offset: Long = 0): List<Worker> = dbQuery {
        Workers.selectAll()
            .orderBy(Workers.createdAt, SortOrder.DESC)
            .limit(limit).offset(offset)
            .map { it.toWorker() }
    }

    suspend fun getById(id: Int): Worker? = dbQuery {
        Workers.selectAll().where { Workers.id eq id }
            .firstOrNull()?.toWorker()
    }

    suspend fun getByEmail(email: String): Worker? = dbQuery {
        Workers.selectAll().where { Workers.email eq email }
            .firstOrNull()?.toWorker()
    }

    suspend fun getPasswordHash(email: String): String? = dbQuery {
        Workers.select(Workers.passwordHash)
            .where { Workers.email eq email }
            .firstOrNull()?.get(Workers.passwordHash)
    }

    suspend fun count(): Long = dbQuery {
        Workers.selectAll().count()
    }

    /** Top workers by jobs completed (from denormalized stats JSONB). */
    suspend fun getLeaderboard(limit: Int = 10): List<Worker> = dbQuery {
        Workers.selectAll()
            .orderBy(Workers.createdAt, SortOrder.ASC)
            .limit(200) // fetch a batch, sort in-memory by stats
            .map { it.toWorker() }
            .sortedByDescending { it.stats.jobsCompleted }
            .take(limit)
    }
}

// ---------------------------------------------------------------------------
// ResultRow → shared model mapping
// ---------------------------------------------------------------------------
fun ResultRow.toWorker(): Worker = Worker(
    id = this[Workers.id],
    name = this[Workers.name],
    email = this[Workers.email],
    phone = this[Workers.phone],
    emailVerified = this[Workers.emailVerified],
    address = this[Workers.address],
    verification = this[Workers.verification],
    stripe = this[Workers.stripe],
    stats = this[Workers.stats],
    earlyAdopterTier = enumValueOfOrDefault(this[Workers.earlyAdopterTier]),
    role = this[Workers.role],
    referralCode = this[Workers.referralCode],
    notifications = this[Workers.notifications],
    createdAt = this[Workers.createdAt],
    updatedAt = this[Workers.updatedAt]
)

/** Safely parse a @SerialName-annotated enum from its serialized string value. */
inline fun <reified T : Enum<T>> enumValueOfOrDefault(value: String): T {
    // Try matching the @SerialName value first (lowercase), then the enum constant name
    return enumValues<T>().firstOrNull { entry ->
        entry.name.equals(value, ignoreCase = true) ||
                entry.name.replace("_", "").equals(value.replace("_", ""), ignoreCase = true)
    } ?: enumValues<T>().first()
}
