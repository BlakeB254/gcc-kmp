package com.gcc.server.routes

import com.gcc.server.plugins.koinGet
import com.gcc.server.plugins.workerId
import com.gcc.server.services.PointsService
import com.gcc.shared.model.ApiResponse
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Authenticated points endpoint.
 *
 * GET /api/workers/me/points — Current worker's points summary (total, breakdown, recent)
 */
fun Route.authenticatedPointsRoutes() {
    val pointsService = application.koinGet<PointsService>()

    get("/api/workers/me/points") {
        val summary = pointsService.getPointsSummary(call.workerId())
        call.respond(HttpStatusCode.OK, ApiResponse(data = summary))
    }
}
