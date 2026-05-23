package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.RouteJobOffers
import com.gcc.server.db.tables.RouteOfferNotifications
import com.gcc.server.db.tables.RouteReleaseBatches
import com.gcc.server.db.tables.Routes
import com.gcc.server.db.tables.Workers
import com.gcc.shared.model.RouteNotificationProcessResult
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.minus
import kotlinx.datetime.toJavaInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class RouteNotificationService {
    private val httpClient = HttpClient.newHttpClient()

    suspend fun sendDirectAssignment(workerId: Int, routeId: Int, jobId: Int): RouteNotificationProcessResult {
        val context = dbQuery {
            val worker = Workers.selectAll().where { Workers.id eq workerId }.firstOrNull()
                ?: return@dbQuery null
            val route = Routes.selectAll().where { Routes.id eq routeId }.firstOrNull()
                ?: return@dbQuery null
            DirectAssignmentContext(worker = worker, route = route, jobId = jobId)
        } ?: return RouteNotificationProcessResult(failed = 1)

        var sent = 0
        var failed = 0
        val email = sendDirectAssignmentEmail(context)
        if (email.success) sent += 1 else failed += 1
        val push = sendDirectAssignmentPush(context)
        if (push.success) sent += 1 else failed += 1
        return RouteNotificationProcessResult(sent = sent, failed = failed)
    }

    suspend fun schedulePendingOfferNotifications(batchId: Int? = null): RouteNotificationProcessResult = dbQuery {
        val query = RouteJobOffers.selectAll()
            .where { RouteJobOffers.status eq "pending" }
        batchId?.let { query.andWhere { RouteJobOffers.batchId eq it } }

        var scheduled = 0
        val now = Clock.System.now()
        for (offer in query) {
            val offerId = offer[RouteJobOffers.id]
            scheduled += insertNotificationIfMissing(
                offerId = offerId,
                workerId = offer[RouteJobOffers.workerId],
                channel = "email",
                kind = "initial",
                scheduledAt = offer[RouteJobOffers.offeredAt],
                now = now,
            )
            scheduled += insertNotificationIfMissing(
                offerId = offerId,
                workerId = offer[RouteJobOffers.workerId],
                channel = "push",
                kind = "initial",
                scheduledAt = offer[RouteJobOffers.offeredAt],
                now = now,
            )

            val oneHour = offer[RouteJobOffers.expiresAt] - 1.hours
            if (oneHour > offer[RouteJobOffers.offeredAt]) {
                scheduled += insertNotificationIfMissing(
                    offerId = offerId,
                    workerId = offer[RouteJobOffers.workerId],
                    channel = "push",
                    kind = "reminder_1h",
                    scheduledAt = oneHour,
                    now = now,
                )
            }

            val fiveMinutes = offer[RouteJobOffers.expiresAt] - 5.minutes
            if (fiveMinutes > offer[RouteJobOffers.offeredAt]) {
                scheduled += insertNotificationIfMissing(
                    offerId = offerId,
                    workerId = offer[RouteJobOffers.workerId],
                    channel = "push",
                    kind = "reminder_5m",
                    scheduledAt = fiveMinutes,
                    now = now,
                )
            }

            RouteJobOffers.update({ RouteJobOffers.id eq offerId }) {
                it[RouteJobOffers.notificationStatus] = "scheduled"
                it[RouteJobOffers.updatedAt] = now
            }
        }

        RouteNotificationProcessResult(scheduled = scheduled)
    }

    suspend fun dispatchDueNotifications(limit: Int = 100): RouteNotificationProcessResult {
        val now = Clock.System.now()
        val dueIds = dbQuery {
            RouteOfferNotifications.select(RouteOfferNotifications.id)
                .where {
                    (RouteOfferNotifications.status eq "pending") and
                        (RouteOfferNotifications.scheduledAt lessEq now)
                }
                .orderBy(RouteOfferNotifications.scheduledAt, SortOrder.ASC)
                .limit(limit)
                .map { it[RouteOfferNotifications.id] }
        }

        var sent = 0
        var skipped = 0
        var failed = 0

        for (notificationId in dueIds) {
            val context = claimNotification(notificationId, now)
            if (context == null) {
                skipped += 1
                continue
            }

            val outcome = when (context.notification[RouteOfferNotifications.channel]) {
                "email" -> sendEmail(context)
                "push" -> sendPush(context)
                else -> DeliveryOutcome(false, "Unknown notification channel")
            }

            dbQuery {
                RouteOfferNotifications.update({ RouteOfferNotifications.id eq notificationId }) {
                    it[RouteOfferNotifications.status] = if (outcome.success) "sent" else "failed"
                    it[RouteOfferNotifications.sentAt] = if (outcome.success) Clock.System.now() else null
                    it[RouteOfferNotifications.lastError] = outcome.error
                    it[RouteOfferNotifications.updatedAt] = Clock.System.now()
                }
                if (context.notification[RouteOfferNotifications.kind] == "initial") {
                    RouteJobOffers.update({ RouteJobOffers.id eq context.offer[RouteJobOffers.id] }) {
                        it[RouteJobOffers.notificationStatus] = if (outcome.success) "sent" else "failed"
                        it[RouteJobOffers.notificationLastError] = outcome.error
                        it[RouteJobOffers.updatedAt] = Clock.System.now()
                    }
                }
            }

            if (outcome.success) sent += 1 else failed += 1
        }

        return RouteNotificationProcessResult(sent = sent, skipped = skipped, failed = failed)
    }

    private suspend fun claimNotification(notificationId: Int, now: Instant): NotificationContext? = dbQuery {
        val notification = RouteOfferNotifications.selectAll()
            .where { RouteOfferNotifications.id eq notificationId }
            .firstOrNull()
            ?: return@dbQuery null
        if (notification[RouteOfferNotifications.status] != "pending") return@dbQuery null

        val offer = RouteJobOffers.selectAll()
            .where { RouteJobOffers.id eq notification[RouteOfferNotifications.offerId] }
            .firstOrNull()
            ?: return@dbQuery skipNotification(notificationId, "Offer not found", now)

        val isReminder = notification[RouteOfferNotifications.kind].startsWith("reminder")
        if (offer[RouteJobOffers.status] != "pending" || offer[RouteJobOffers.expiresAt] <= now) {
            return@dbQuery skipNotification(notificationId, "Offer is no longer pending", now)
        }
        if (isReminder && notification[RouteOfferNotifications.scheduledAt] >= offer[RouteJobOffers.expiresAt]) {
            return@dbQuery skipNotification(notificationId, "Reminder is outside offer window", now)
        }

        val worker = Workers.selectAll()
            .where { Workers.id eq notification[RouteOfferNotifications.workerId] }
            .firstOrNull()
            ?: return@dbQuery skipNotification(notificationId, "Worker not found", now)

        val batch = RouteReleaseBatches.selectAll()
            .where { RouteReleaseBatches.id eq offer[RouteJobOffers.batchId] }
            .firstOrNull()
            ?: return@dbQuery skipNotification(notificationId, "Release batch not found", now)

        RouteOfferNotifications.update({ RouteOfferNotifications.id eq notificationId }) {
            it[RouteOfferNotifications.status] = "sending"
            it[RouteOfferNotifications.attempts] = notification[RouteOfferNotifications.attempts] + 1
            it[RouteOfferNotifications.updatedAt] = now
        }

        NotificationContext(
            notification = RouteOfferNotifications.selectAll()
                .where { RouteOfferNotifications.id eq notificationId }
                .first(),
            offer = offer,
            worker = worker,
            batch = batch,
        )
    }

    private fun skipNotification(notificationId: Int, reason: String, now: Instant): NotificationContext? {
        RouteOfferNotifications.update({ RouteOfferNotifications.id eq notificationId }) {
            it[RouteOfferNotifications.status] = "skipped"
            it[RouteOfferNotifications.lastError] = reason
            it[RouteOfferNotifications.updatedAt] = now
        }
        return null
    }

    private fun insertNotificationIfMissing(
        offerId: Int,
        workerId: Int,
        channel: String,
        kind: String,
        scheduledAt: Instant,
        now: Instant,
    ): Int {
        val existing = RouteOfferNotifications.select(RouteOfferNotifications.id)
            .where {
                (RouteOfferNotifications.offerId eq offerId) and
                    (RouteOfferNotifications.channel eq channel) and
                    (RouteOfferNotifications.kind eq kind)
            }
            .firstOrNull()
        if (existing != null) return 0

        RouteOfferNotifications.insert {
            it[RouteOfferNotifications.offerId] = offerId
            it[RouteOfferNotifications.workerId] = workerId
            it[RouteOfferNotifications.channel] = channel
            it[RouteOfferNotifications.kind] = kind
            it[RouteOfferNotifications.status] = "pending"
            it[RouteOfferNotifications.scheduledAt] = scheduledAt
            it[RouteOfferNotifications.payload] = JsonObject(mapOf("offerId" to JsonPrimitive(offerId), "kind" to JsonPrimitive(kind)))
            it[RouteOfferNotifications.createdAt] = now
            it[RouteOfferNotifications.updatedAt] = now
        }
        return 1
    }

    private fun sendEmail(context: NotificationContext): DeliveryOutcome {
        if (!context.worker[Workers.notificationsEmailEnabled]) {
            return DeliveryOutcome(false, "Worker email notifications are disabled")
        }
        val apiKey = System.getenv("RESEND_API_KEY")
            ?: return DeliveryOutcome(false, "RESEND_API_KEY is not configured")
        val from = System.getenv("EMAIL_FROM") ?: "GCC <noreply@globalcommunitycleaners.org>"
        val replyTo = System.getenv("EMAIL_REPLY_TO") ?: "hello@globalcommunitycleaners.org"
        val copy = offerCopy(context)

        val body = JsonObject(
            mapOf(
                "from" to JsonPrimitive(from),
                "to" to JsonPrimitive(context.worker[Workers.email]),
                "subject" to JsonPrimitive(copy.emailSubject),
                "html" to JsonPrimitive(copy.emailHtml),
                "text" to JsonPrimitive(copy.emailText),
                "reply_to" to JsonPrimitive(replyTo),
            )
        ).toString()

        return postJson(
            url = "https://api.resend.com/emails",
            bearerToken = apiKey,
            body = body,
        )
    }

    private fun sendPush(context: NotificationContext): DeliveryOutcome {
        if (!context.worker[Workers.notificationsPushEnabled]) {
            return DeliveryOutcome(false, "Worker push notifications are disabled")
        }
        val subscription = context.worker[Workers.notificationsPushSubscription]
            ?: return DeliveryOutcome(false, "Worker has no push token")
        val gatewayUrl = System.getenv("PUSH_GATEWAY_URL")
            ?: return DeliveryOutcome(false, "PUSH_GATEWAY_URL is not configured")
        val gatewayToken = System.getenv("PUSH_GATEWAY_TOKEN")
        val subscriptionObject = runCatching { subscription.jsonObject }.getOrNull()
            ?: return DeliveryOutcome(false, "Worker push token is not a JSON object")
        val token = subscriptionObject["token"]?.jsonPrimitive?.contentOrNull
            ?: subscriptionObject["endpoint"]?.jsonPrimitive?.contentOrNull
            ?: return DeliveryOutcome(false, "Worker push token is missing token/endpoint")
        val platform = subscriptionObject["platform"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val copy = offerCopy(context)
        val body = JsonObject(
            mapOf(
                "token" to JsonPrimitive(token),
                "platform" to JsonPrimitive(platform),
                "title" to JsonPrimitive(copy.pushTitle),
                "body" to JsonPrimitive(copy.pushBody),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("route_offer"),
                        "offerId" to JsonPrimitive(context.offer[RouteJobOffers.id]),
                        "batchId" to JsonPrimitive(context.batch[RouteReleaseBatches.id]),
                        "expiresAt" to JsonPrimitive(context.offer[RouteJobOffers.expiresAt].toString()),
                    )
                ),
            )
        ).toString()

        return postJson(gatewayUrl, gatewayToken, body)
    }

    private fun sendDirectAssignmentEmail(context: DirectAssignmentContext): DeliveryOutcome {
        if (!context.worker[Workers.notificationsEmailEnabled]) {
            return DeliveryOutcome(false, "Worker email notifications are disabled")
        }
        val apiKey = System.getenv("RESEND_API_KEY")
            ?: return DeliveryOutcome(false, "RESEND_API_KEY is not configured")
        val from = System.getenv("EMAIL_FROM") ?: "GCC <noreply@globalcommunitycleaners.org>"
        val replyTo = System.getenv("EMAIL_REPLY_TO") ?: "hello@globalcommunitycleaners.org"
        val routeName = context.route[Routes.displayName]
        val text = """
            Hi ${context.worker[Workers.name]},

            You have been assigned a GCC route: $routeName. Open the GCC app to review and start the route.
        """.trimIndent()
        val body = JsonObject(
            mapOf(
                "from" to JsonPrimitive(from),
                "to" to JsonPrimitive(context.worker[Workers.email]),
                "subject" to JsonPrimitive("You have been assigned a GCC route"),
                "html" to JsonPrimitive("<p>Hi ${escapeHtml(context.worker[Workers.name])},</p><p>You have been assigned a GCC route: ${escapeHtml(routeName)}. Open the GCC app to review and start the route.</p>"),
                "text" to JsonPrimitive(text),
                "reply_to" to JsonPrimitive(replyTo),
            )
        ).toString()
        return postJson("https://api.resend.com/emails", apiKey, body)
    }

    private fun sendDirectAssignmentPush(context: DirectAssignmentContext): DeliveryOutcome {
        if (!context.worker[Workers.notificationsPushEnabled]) {
            return DeliveryOutcome(false, "Worker push notifications are disabled")
        }
        val subscription = context.worker[Workers.notificationsPushSubscription]
            ?: return DeliveryOutcome(false, "Worker has no push token")
        val gatewayUrl = System.getenv("PUSH_GATEWAY_URL")
            ?: return DeliveryOutcome(false, "PUSH_GATEWAY_URL is not configured")
        val subscriptionObject = runCatching { subscription.jsonObject }.getOrNull()
            ?: return DeliveryOutcome(false, "Worker push token is not a JSON object")
        val token = subscriptionObject["token"]?.jsonPrimitive?.contentOrNull
            ?: subscriptionObject["endpoint"]?.jsonPrimitive?.contentOrNull
            ?: return DeliveryOutcome(false, "Worker push token is missing token/endpoint")
        val platform = subscriptionObject["platform"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val body = JsonObject(
            mapOf(
                "token" to JsonPrimitive(token),
                "platform" to JsonPrimitive(platform),
                "title" to JsonPrimitive("GCC route assigned"),
                "body" to JsonPrimitive("${context.route[Routes.displayName]} is assigned to you. Open the app to start."),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("route_assignment"),
                        "jobId" to JsonPrimitive(context.jobId),
                        "routeId" to JsonPrimitive(context.route[Routes.id]),
                    )
                ),
            )
        ).toString()
        return postJson(gatewayUrl, System.getenv("PUSH_GATEWAY_TOKEN"), body)
    }

    private fun postJson(url: String, bearerToken: String?, body: String): DeliveryOutcome {
        return try {
            val builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
            if (!bearerToken.isNullOrBlank()) {
                builder.header("Authorization", "Bearer $bearerToken")
            }
            val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) {
                DeliveryOutcome(true)
            } else {
                DeliveryOutcome(false, "HTTP ${response.statusCode()}: ${response.body().take(300)}")
            }
        } catch (error: Exception) {
            DeliveryOutcome(false, error.message ?: "Notification request failed")
        }
    }

    private fun offerCopy(context: NotificationContext): OfferCopy {
        val kind = context.notification[RouteOfferNotifications.kind]
        val cleanupDate = formatOfferDate(
            context.batch[RouteReleaseBatches.cleanupStartsAt]
                ?: context.batch[RouteReleaseBatches.releaseAt],
            context.batch[RouteReleaseBatches.timezone],
        )
        val expiresAt = formatOfferTime(
            context.offer[RouteJobOffers.expiresAt],
            context.batch[RouteReleaseBatches.timezone],
        )
        val community = context.batch[RouteReleaseBatches.communityName]
        val body = when (kind) {
            "reminder_1h" -> "1 hour left: log in to the GCC app now for your opportunity to make $100 on $cleanupDate."
            "reminder_5m" -> "5 minutes left: log in to the GCC app now for your opportunity to make $100 on $cleanupDate."
            else -> "Log in to the GCC app now for your opportunity to make $100 on $cleanupDate. Your offer expires at $expiresAt."
        }
        val title = when (kind) {
            "reminder_1h" -> "1 hour left for your GCC route"
            "reminder_5m" -> "5 minutes left for your GCC route"
            else -> "Your GCC route opportunity is open"
        }
        val text = """
            Hi ${context.worker[Workers.name]},

            $body

            This route release is for verified $community residents only. Open the GCC app to choose an available route before the offer expires.
        """.trimIndent()
        return OfferCopy(
            pushTitle = title,
            pushBody = body,
            emailSubject = "Your GCC route opportunity is open",
            emailText = text,
            emailHtml = """
                <p>Hi ${escapeHtml(context.worker[Workers.name])},</p>
                <p>${escapeHtml(body)}</p>
                <p>This route release is for verified ${escapeHtml(community)} residents only. Open the GCC app to choose an available route before the offer expires.</p>
            """.trimIndent(),
        )
    }

    private fun formatOfferDate(value: Instant, timezone: String): String =
        value.toJavaInstant()
            .atZone(ZoneId.of(timezone))
            .format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))

    private fun formatOfferTime(value: Instant, timezone: String): String =
        value.toJavaInstant()
            .atZone(ZoneId.of(timezone))
            .format(DateTimeFormatter.ofPattern("h:mm a z"))

    private fun escapeHtml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}

private data class NotificationContext(
    val notification: ResultRow,
    val offer: ResultRow,
    val worker: ResultRow,
    val batch: ResultRow,
)

private data class DirectAssignmentContext(
    val worker: ResultRow,
    val route: ResultRow,
    val jobId: Int,
)

private data class DeliveryOutcome(
    val success: Boolean,
    val error: String? = null,
)

private data class OfferCopy(
    val pushTitle: String,
    val pushBody: String,
    val emailSubject: String,
    val emailText: String,
    val emailHtml: String,
)
