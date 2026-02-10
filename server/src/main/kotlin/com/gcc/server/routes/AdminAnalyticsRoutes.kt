package com.gcc.server.routes

import com.gcc.server.plugins.requireAdmin
import com.gcc.server.services.AnalyticsService
import com.gcc.shared.model.ApiResponse
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get as koinGet

fun Route.adminAnalyticsRoutes() {
    val analyticsService = application.koinGet<AnalyticsService>()

    route("/api/admin/analytics") {
        get("/stats") {
            call.requireAdmin()
            val stats = analyticsService.getAdminStats()
            call.respond(HttpStatusCode.OK, ApiResponse(data = stats))
        }

        get("/revenue-by-area") {
            call.requireAdmin()
            val revenue = analyticsService.getRevenueByArea()
            call.respond(HttpStatusCode.OK, ApiResponse(data = revenue))
        }

        get("/worker-metrics") {
            call.requireAdmin()
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 20
            val metrics = analyticsService.getWorkerMetrics(limit)
            call.respond(HttpStatusCode.OK, ApiResponse(data = metrics))
        }

        get("/sponsor-metrics") {
            call.requireAdmin()
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 20
            val metrics = analyticsService.getSponsorMetrics(limit)
            call.respond(HttpStatusCode.OK, ApiResponse(data = metrics))
        }
    }
}
