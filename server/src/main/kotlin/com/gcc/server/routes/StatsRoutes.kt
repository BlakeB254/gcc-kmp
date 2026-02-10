package com.gcc.server.routes

import com.gcc.server.services.StatsService
import com.gcc.shared.model.ApiResponse
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get as koinGet

/**
 * Public statistics endpoint — aggregate counts for the landing page.
 *
 * GET /api/stats — Returns total workers, blocks, jobs, etc.
 */
fun Routing.statsRoutes() {
    val statsService = koinGet<StatsService>()

    get("/api/stats") {
        val stats = statsService.getPublicStats()
        call.respond(HttpStatusCode.OK, ApiResponse(data = stats))
    }
}
