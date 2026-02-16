package com.gcc.server.routes

import com.gcc.server.plugins.workerId
import com.gcc.server.services.GamificationService
import com.gcc.shared.model.ApiResponse
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.gcc.server.plugins.koinGet

/**
 * Public gamification endpoints.
 *
 * GET  /api/badges       — All badge definitions
 * GET  /api/leaderboard  — Leaderboard (override if gamification query params are present)
 */
fun Routing.gamificationRoutes() {
    val gamificationService = koinGet<GamificationService>()

    route("/api/badges") {
        get {
            val badges = gamificationService.getAllBadgeDefinitions()
            call.respond(HttpStatusCode.OK, ApiResponse(data = badges))
        }
    }
}

/**
 * Authenticated gamification endpoints.
 *
 * GET  /api/workers/me/badges       — Current user's earned badges
 * GET  /api/sponsors/me/milestones  — Current sponsor's milestones
 */
fun Route.authenticatedGamificationRoutes() {
    val gamificationService = application.koinGet<GamificationService>()

    get("/api/workers/me/badges") {
        val badges = gamificationService.getWorkerBadges(call.workerId())
        call.respond(HttpStatusCode.OK, ApiResponse(data = badges))
    }
}
