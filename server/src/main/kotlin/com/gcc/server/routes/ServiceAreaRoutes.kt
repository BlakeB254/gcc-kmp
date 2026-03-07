package com.gcc.server.routes

import com.gcc.server.plugins.ErrorResponse
import com.gcc.server.services.ServiceAreaService
import com.gcc.shared.model.ApiResponse
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.gcc.server.plugins.koinGet

/**
 * Service area endpoints — geographic regions where GCC operates.
 *
 * GET /api/service-areas        — List all service areas
 * GET /api/service-areas/{id}   — Get single service area
 * GET /api/service-areas/active — Active service areas only
 */
fun Routing.serviceAreaRoutes() {
    val serviceAreaService = koinGet<ServiceAreaService>()

    route("/api/service-areas") {
        // GET /api/service-areas?limit=50&offset=0
        get {
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
            val offset = call.parameters["offset"]?.toLongOrNull() ?: 0
            val areas = serviceAreaService.getAll(limit = limit, offset = offset)
            call.respond(HttpStatusCode.OK, ApiResponse(data = areas))
        }

        // GET /api/service-areas/active
        get("/active") {
            val areas = serviceAreaService.getActive()
            call.respond(HttpStatusCode.OK, ApiResponse(data = areas))
        }

        // GET /api/service-areas/{id}
        get("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Service area ID must be an integer")
            val area = serviceAreaService.getById(id)
            if (area != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = area))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Service area not found", 404))
            }
        }
    }
}
