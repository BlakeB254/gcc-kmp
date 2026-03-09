package com.gcc.server.routes

import com.gcc.server.plugins.koinGet
import com.gcc.server.services.CleanlinessService
import com.gcc.shared.model.ApiResponse
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Public cleanliness analytics endpoints.
 *
 * GET /api/analytics/cleanliness        — City-wide overview with per-area breakdown
 * GET /api/analytics/cleanliness/{area} — Single area with street-level breakdown
 */
fun Routing.cleanlinessRoutes() {
    val cleanlinessService = koinGet<CleanlinessService>()

    route("/api/analytics/cleanliness") {
        get {
            val overview = cleanlinessService.getCityOverview()
            call.respond(HttpStatusCode.OK, ApiResponse(data = overview))
        }

        get("/{area}") {
            val area = call.parameters["area"]
                ?: throw IllegalArgumentException("Area parameter is required")
            val decoded = java.net.URLDecoder.decode(area, "UTF-8")
            val stats = cleanlinessService.getAreaDetail(decoded)
            call.respond(HttpStatusCode.OK, ApiResponse(data = stats))
        }
    }
}
