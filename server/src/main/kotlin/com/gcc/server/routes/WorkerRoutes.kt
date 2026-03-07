package com.gcc.server.routes

import com.gcc.server.plugins.ErrorResponse
import com.gcc.server.plugins.workerId
import com.gcc.server.services.ServiceAreaService
import com.gcc.server.services.WorkerService
import com.gcc.shared.model.ApiResponse
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import com.gcc.server.plugins.koinGet

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

        // POST /api/workers/me/device-token — register push notification token
        post("/me/device-token") {
            val id = call.workerId()
            val body = call.receive<DeviceTokenRequest>()
            if (body.token.isBlank() || body.platform.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("token and platform are required", 400))
                return@post
            }
            workerService.savePushToken(id, body.platform, body.token)
            call.respond(HttpStatusCode.OK, ApiResponse(data = mapOf("success" to true)))
        }

        // DELETE /api/workers/me/device-token — remove push token (sign out)
        delete("/me/device-token") {
            val id = call.workerId()
            workerService.removePushToken(id)
            call.respond(HttpStatusCode.OK, ApiResponse(data = mapOf("success" to true)))
        }

        get("/me/service-area") {
            val id = call.workerId()
            val worker = workerService.getById(id)
            if (worker != null) {
                val serviceAreaService = application.koinGet<ServiceAreaService>()
                val lat = worker.address.latitude
                val lng = worker.address.longitude
                if (lat != null && lng != null) {
                    val area = serviceAreaService.getServiceAreaForLocation(lat, lng)
                    if (area != null) {
                        call.respond(HttpStatusCode.OK, ApiResponse(data = area))
                    } else {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("No service area found for location", 404))
                    }
                } else {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Worker address has no coordinates", 400))
                }
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Worker not found", 404))
            }
        }
    }
}

@Serializable
data class DeviceTokenRequest(val platform: String, val token: String)
