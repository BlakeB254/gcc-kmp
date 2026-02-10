package com.gcc.server.routes

import com.gcc.server.plugins.workerId
import com.gcc.server.services.WorkerService
import com.gcc.shared.model.ApiResponse
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.get as koinGet

/**
 * Worker endpoints — public read-only queries.
 *
 * GET  /api/workers       — List all workers (paginated)
 * GET  /api/workers/{id}  — Get single worker by ID
 */
fun Routing.workerRoutes() {
    val workerService = koinGet<WorkerService>()

    route("/api/workers") {
        get {
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
            val offset = call.parameters["offset"]?.toLongOrNull() ?: 0
            val workers = workerService.getAll(limit = limit, offset = offset)
            call.respond(HttpStatusCode.OK, ApiResponse(data = workers))
        }

        get("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Worker ID must be an integer")
            val worker = workerService.getById(id)
            if (worker != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = worker))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Worker not found", 404))
            }
        }
    }
}

/**
 * Authenticated worker endpoints.
 *
 * GET /api/workers/me — Get the current authenticated worker's profile.
 *
 * Must be called inside an `authenticate("auth-jwt") { }` block.
 */
fun Route.authenticatedWorkerRoutes() {
    val workerService = application.koinGet<WorkerService>()

    route("/api/workers") {
        get("/me") {
            val id = call.workerId()
            val worker = workerService.getById(id)
            if (worker != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = worker))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Worker not found", 404))
            }
        }
    }
}

@Serializable
data class ErrorResponse(val message: String, val code: Int)
