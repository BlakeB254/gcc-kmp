package com.gcc.server.routes

import com.gcc.server.services.BlockService
import com.gcc.shared.model.ApiResponse
import com.gcc.shared.model.BlockStatus
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get as koinGet

/**
 * Block endpoints — city blocks that can be sponsored and cleaned.
 *
 * GET /api/blocks            — List blocks (filterable by status, service_area_id)
 * GET /api/blocks/{id}       — Get single block by ID
 * GET /api/blocks/available  — Shortcut: only available (unfunded) blocks
 */
fun Routing.blockRoutes() {
    val blockService = koinGet<BlockService>()

    route("/api/blocks") {
        // GET /api/blocks?status=available&service_area_id=1&limit=50&offset=0
        get {
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
            val offset = call.parameters["offset"]?.toLongOrNull() ?: 0
            val status = call.parameters["status"]?.let {
                try { BlockStatus.valueOf(it.uppercase()) } catch (_: Exception) { null }
            }
            val serviceAreaId = call.parameters["service_area_id"]?.toIntOrNull()

            val blocks = blockService.getAll(
                status = status,
                serviceAreaId = serviceAreaId,
                limit = limit,
                offset = offset
            )
            call.respond(HttpStatusCode.OK, ApiResponse(data = blocks))
        }

        // GET /api/blocks/available
        get("/available") {
            val blocks = blockService.getAvailable()
            call.respond(HttpStatusCode.OK, ApiResponse(data = blocks))
        }

        // GET /api/blocks/{id}
        get("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Block ID must be an integer")
            val block = blockService.getById(id)
            if (block != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = block))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Block not found", 404))
            }
        }
    }
}
