package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.Workers
import com.gcc.shared.model.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

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

    /**
     * Returns the salt and hash for Payload CMS-style authentication.
     * Production DB uses separate `salt` and `hash` columns (SHA-256 based).
     */
    suspend fun getAuthCredentials(email: String): Pair<String, String>? = dbQuery {
        Workers.select(Workers.salt, Workers.hash)
            .where { Workers.email eq email }
            .firstOrNull()?.let { row ->
                val salt = row[Workers.salt] ?: return@let null
                val hash = row[Workers.hash] ?: return@let null
                Pair(salt, hash)
            }
    }

    suspend fun count(): Long = dbQuery {
        Workers.selectAll().count()
    }

    /** Store a push notification device token for the worker. */
    suspend fun savePushToken(workerId: Int, platform: String, token: String): Unit = dbQuery {
        val subscription = JsonObject(mapOf(
            "platform" to JsonPrimitive(platform),
            "token" to JsonPrimitive(token),
        ))
        Workers.update({ Workers.id eq workerId }) {
            it[notificationsPushEnabled] = true
            it[notificationsPushSubscription] = subscription
            it[updatedAt] = Clock.System.now()
        }
    }

    /** Remove the push token for the worker (e.g. on sign out). */
    suspend fun removePushToken(workerId: Int): Unit = dbQuery {
        Workers.update({ Workers.id eq workerId }) {
            it[notificationsPushEnabled] = false
            it[notificationsPushSubscription] = null
            it[updatedAt] = Clock.System.now()
        }
    }

    /** Delete a worker account (hard delete). */
    suspend fun deleteAccount(workerId: Int): Boolean = dbQuery {
        Workers.deleteWhere { Workers.id eq workerId } > 0
    }

    /** Top workers by jobs completed (from denormalized flat stats columns). */
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
// ResultRow -> shared model mapping (flat columns -> nested model objects)
// ---------------------------------------------------------------------------
fun ResultRow.toWorker(): Worker = Worker(
    id = this[Workers.id],
    name = this[Workers.name],
    email = this[Workers.email],
    phone = this[Workers.phone],
    emailVerified = this[Workers.emailVerified],
    address = WorkerAddress(
        street = this[Workers.addressStreet],
        city = this[Workers.addressCity],
        state = this[Workers.addressState],
        zip = this[Workers.addressZip],
        neighborhood = this[Workers.addressNeighborhood],
        isNorthLawndale = this[Workers.addressIsNorthLawndale],
    ),
    verification = WorkerVerification(
        status = enumValueOfOrDefault(this[Workers.verificationStatus] ?: "pending"),
        governmentIdStatus = enumValueOfOrDefault(this[Workers.verificationGovernmentIdStatus] ?: "pending"),
        proofOfAddressStatus = enumValueOfOrDefault(this[Workers.verificationProofOfAddressStatus] ?: "pending"),
        reviewNotes = this[Workers.verificationReviewNotes],
    ),
    stripe = WorkerStripe(
        accountId = this[Workers.stripeAccountId],
        onboardingStatus = enumValueOfOrDefault(this[Workers.stripeOnboardingStatus] ?: "not_started"),
        instantPayoutsEnabled = this[Workers.stripeInstantPayoutsEnabled],
        defaultPayoutMethod = this[Workers.stripeDefaultPayoutMethod] ?: "standard",
    ),
    stats = WorkerStats(
        jobsCompleted = this[Workers.statsJobsCompleted]?.toIntOrNull() ?: 0,
        totalEarnings = this[Workers.statsTotalEarnings]?.toIntOrNull() ?: 0,
        averageRating = this[Workers.statsAverageRating]?.toDoubleOrNull(),
        lastJobAt = this[Workers.statsLastJobAt],
    ),
    earlyAdopterTier = enumValueOfOrDefault(this[Workers.earlyAdopterTier] ?: "standard"),
    role = "worker", // not stored in production DB; admin role lives in JWT claims
    referralCode = this[Workers.referralCode],
    notifications = WorkerNotifications(
        pushEnabled = this[Workers.notificationsPushEnabled],
        smsEnabled = this[Workers.notificationsSmsEnabled],
        emailEnabled = this[Workers.notificationsEmailEnabled],
    ),
    createdAt = this[Workers.createdAt],
    updatedAt = this[Workers.updatedAt],
)

/** Safely parse a @SerialName-annotated enum from its serialized string value. */
inline fun <reified T : Enum<T>> enumValueOfOrDefault(value: String): T {
    // Try matching the @SerialName value first (lowercase), then the enum constant name
    return enumValues<T>().firstOrNull { entry ->
        entry.name.equals(value, ignoreCase = true) ||
                entry.name.replace("_", "").equals(value.replace("_", ""), ignoreCase = true)
    } ?: enumValues<T>().first()
}
