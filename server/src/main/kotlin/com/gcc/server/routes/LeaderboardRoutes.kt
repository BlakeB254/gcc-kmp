package com.gcc.server.routes

import com.gcc.server.services.StatsService
import com.gcc.shared.model.ApiResponse
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get as koinGet

/**
 * Leaderboard endpoint — top workers by jobs completed.
 *
 * GET /api/leaderboard?limit=10  — Top N workers ranked by performance
 */
fun Routing.leaderboardRoutes() {
    val statsService = koinGet<StatsService>()

    route("/api/leaderboard") {
        get {
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 10
            val leaderboard = statsService.getLeaderboard(limit)
            call.respond(HttpStatusCode.OK, ApiResponse(data = leaderboard))
        }
    }
}
