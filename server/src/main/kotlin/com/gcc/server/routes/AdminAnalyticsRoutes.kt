package com.gcc.server.routes

import com.gcc.server.plugins.requireAdmin
import com.gcc.server.services.AnalyticsService
import com.gcc.shared.model.ApiResponse
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.gcc.server.plugins.koinGet

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

        get("/activity") {
            call.requireAdmin()
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 20
            val activity = analyticsService.getActivity(limit)
            call.respond(HttpStatusCode.OK, ApiResponse(data = activity))
        }

        get("/daily") {
            call.requireAdmin()
            val days = call.parameters["days"]?.toIntOrNull() ?: 30
            val dailyStats = analyticsService.getDailyStats(days)
            call.respond(HttpStatusCode.OK, ApiResponse(data = dailyStats))
        }
    }

    // Bounty analytics stubs
    get("/api/admin/analytics/bounty-stats") {
        call.respond(mapOf(
            "totalPosted" to 147,
            "totalValueCents" to 285000L,
            "avgPriceCents" to 1938,
            "completionRate" to 0.72,
            "avgTimeToClaimMinutes" to 342,
        ))
    }

    get("/api/admin/analytics/bounty-timeseries") {
        val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 30
        val now = java.time.LocalDate.now()
        val points = (0 until days).map { i ->
            val date = now.minusDays(i.toLong())
            mapOf(
                "date" to date.toString(),
                "posted" to (3..8).random(),
                "claimed" to (2..6).random(),
                "completed" to (1..5).random(),
                "expired" to (0..2).random(),
                "totalValueCents" to (5000..25000).random().toLong(),
            )
        }.reversed()
        call.respond(points)
    }

    get("/api/admin/analytics/bounty-by-location") {
        call.respond(listOf(
            mapOf("communityAreaId" to 29, "communityAreaName" to "North Lawndale",
                  "bountyCount" to 89, "totalValueCents" to 178000L,
                  "avgPriceCents" to 2000, "completionRate" to 0.78,
                  "avgTimeToClaimMinutes" to 280),
            mapOf("communityAreaId" to 26, "communityAreaName" to "West Garfield Park",
                  "bountyCount" to 34, "totalValueCents" to 61200L,
                  "avgPriceCents" to 1800, "completionRate" to 0.65,
                  "avgTimeToClaimMinutes" to 420),
            mapOf("communityAreaId" to 27, "communityAreaName" to "East Garfield Park",
                  "bountyCount" to 24, "totalValueCents" to 45600L,
                  "avgPriceCents" to 1900, "completionRate" to 0.71,
                  "avgTimeToClaimMinutes" to 360),
        ))
    }

    get("/api/admin/analytics/bounty-leaderboard") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
        call.respond(listOf(
            mapOf("userId" to 1, "userName" to "Green Fund Chicago",
                  "posterType" to "SPONSOR", "bountiesPosted" to 45,
                  "totalValueCents" to 112500L, "completionRate" to 0.82),
            mapOf("userId" to 2, "userName" to "Block Club NL",
                  "posterType" to "SPONSOR", "bountiesPosted" to 28,
                  "totalValueCents" to 56000L, "completionRate" to 0.75),
            mapOf("userId" to 3, "userName" to "Maria Johnson",
                  "posterType" to "WORKER", "bountiesPosted" to 12,
                  "totalValueCents" to 18000L, "completionRate" to 0.92),
        ).take(limit))
    }
}
