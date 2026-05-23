package com.gcc.server.routes

import com.gcc.server.plugins.requireAdmin
import com.gcc.server.plugins.workerId
import com.gcc.server.services.RouteNotificationService
import com.gcc.server.services.RouteQueueService
import com.gcc.shared.model.AdminRouteReleaseRequest
import com.gcc.shared.model.ApiResponse
import com.gcc.shared.model.RouteOfferAcceptRequest
import com.gcc.shared.model.RouteOfferDeclineRequest
import com.gcc.shared.model.RouteReleaseRunResponse
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.gcc.server.plugins.koinGet

fun Route.authenticatedRouteQueueRoutes() {
    val routeQueueService = application.koinGet<RouteQueueService>()

    route("/api/workers/me/route-offers") {
        get {
            val offers = routeQueueService.getWorkerOffers(call.workerId())
            call.respond(HttpStatusCode.OK, ApiResponse(data = offers))
        }

        post("/{id}/accept") {
            val offerId = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Offer ID must be an integer")
            val request = call.receive<RouteOfferAcceptRequest>()
            val job = routeQueueService.acceptOffer(
                offerId = offerId,
                workerId = call.workerId(),
                routeId = request.routeId,
            )
            call.respond(HttpStatusCode.OK, ApiResponse(data = job))
        }

        patch("/{id}/decline") {
            val offerId = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Offer ID must be an integer")
            val request = runCatching { call.receive<RouteOfferDeclineRequest>() }.getOrNull()
            val offer = routeQueueService.declineOffer(
                offerId = offerId,
                workerId = call.workerId(),
                reason = request?.reason,
            )
            call.respond(HttpStatusCode.OK, ApiResponse(data = offer))
        }
    }
}

fun Route.adminRouteReleaseRoutes() {
    val routeQueueService = application.koinGet<RouteQueueService>()
    val notificationService = application.koinGet<RouteNotificationService>()

    route("/api/admin/route-releases") {
        get {
            call.requireAdmin()
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
            call.respond(HttpStatusCode.OK, ApiResponse(data = routeQueueService.listReleases(limit)))
        }

        post {
            call.requireAdmin()
            val request = call.receive<AdminRouteReleaseRequest>()
            val release = routeQueueService.createRelease(request, adminWorkerId = call.workerId())
            call.respond(HttpStatusCode.Created, ApiResponse(data = release))
        }

        post("/process-notifications") {
            call.requireAdmin()
            val scheduled = notificationService.schedulePendingOfferNotifications()
            val dispatched = notificationService.dispatchDueNotifications()
            call.respond(HttpStatusCode.OK, ApiResponse(data = RouteReleaseRunResponse(emptyList(), scheduled, dispatched)))
        }

        post("/{id}/process") {
            call.requireAdmin()
            val releaseId = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Release ID must be an integer")
            val result = routeQueueService.processBatch(releaseId)
            val scheduled = notificationService.schedulePendingOfferNotifications(releaseId)
            val dispatched = notificationService.dispatchDueNotifications()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse(data = RouteReleaseRunResponse(listOf(result), scheduled, dispatched)),
            )
        }
    }
}

fun Route.routeQueueCronRoutes() {
    val routeQueueService = application.koinGet<RouteQueueService>()
    val notificationService = application.koinGet<RouteNotificationService>()

    post("/api/cron/route-releases/process") {
        val expected = System.getenv("GCC_CRON_SECRET")
        val provided = call.request.headers["x-cron-secret"]
            ?: call.request.headers["authorization"]?.removePrefix("Bearer ")?.trim()
        if (expected.isNullOrBlank() || provided != expected) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Invalid cron secret"))
            return@post
        }

        val results = routeQueueService.processDueBatches()
        val scheduled = notificationService.schedulePendingOfferNotifications()
        val dispatched = notificationService.dispatchDueNotifications()
        call.respond(HttpStatusCode.OK, ApiResponse(data = RouteReleaseRunResponse(results, scheduled, dispatched)))
    }
}
