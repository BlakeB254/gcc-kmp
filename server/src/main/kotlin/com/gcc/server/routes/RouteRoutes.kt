package com.gcc.server.routes

import com.gcc.server.services.RouteService
import com.gcc.shared.model.ApiResponse
import com.gcc.shared.model.RouteStatus
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.gcc.server.plugins.koinGet

/**
 * Route endpoints — cleaning routes with checkpoints.
 *
 * GET /api/routes            — List routes (filterable by status, service_area_id)
 * GET /api/routes/{id}       — Get single route with its checkpoints
 * GET /api/routes/published  — Shortcut: only published routes
 */
fun Routing.routeRoutes() {
    val routeService = koinGet<RouteService>()

    route("/api/routes") {
        // GET /api/routes?status=published&service_area_id=1&limit=50&offset=0
        get {
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
            val offset = call.parameters["offset"]?.toLongOrNull() ?: 0
            val status = call.parameters["status"]?.let {
                try { RouteStatus.valueOf(it.uppercase()) } catch (_: Exception) { null }
            }
            val serviceAreaId = call.parameters["service_area_id"]?.toIntOrNull()

            val routes = routeService.getAll(
                status = status,
                serviceAreaId = serviceAreaId,
                limit = limit,
                offset = offset
            )
            call.respond(HttpStatusCode.OK, ApiResponse(data = routes))
        }

        // GET /api/routes/published — convenience endpoint
        get("/published") {
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
            val routes = routeService.getPublished(limit)
            call.respond(HttpStatusCode.OK, ApiResponse(data = routes))
        }

        // GET /api/routes/{id}
        get("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Route ID must be an integer")
            val route = routeService.getById(id)
            if (route != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = route))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Route not found", 404))
            }
        }
    }
}
