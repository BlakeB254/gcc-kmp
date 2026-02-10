package com.gcc.server.routes

import com.gcc.server.plugins.workerId
import com.gcc.server.services.JobService
import com.gcc.shared.model.ApiResponse
import com.gcc.shared.model.JobStatus
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get as koinGet

/**
 * Public job endpoints — read-only queries.
 *
 * GET /api/jobs              — List jobs (filterable by status, worker_id, route_id)
 * GET /api/jobs/{id}         — Get single job by ID
 * GET /api/jobs/available    — Shortcut: only available jobs
 */
fun Routing.jobRoutes() {
    val jobService = koinGet<JobService>()

    route("/api/jobs") {
        get {
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
            val offset = call.parameters["offset"]?.toLongOrNull() ?: 0
            val status = call.parameters["status"]?.let {
                try { JobStatus.valueOf(it.uppercase()) } catch (_: Exception) { null }
            }
            val workerId = call.parameters["worker_id"]?.toIntOrNull()
            val routeId = call.parameters["route_id"]?.toIntOrNull()

            val jobs = jobService.getAll(
                status = status,
                workerId = workerId,
                routeId = routeId,
                limit = limit,
                offset = offset
            )
            call.respond(HttpStatusCode.OK, ApiResponse(data = jobs))
        }

        get("/available") {
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
            val jobs = jobService.getAvailable(limit)
            call.respond(HttpStatusCode.OK, ApiResponse(data = jobs))
        }

        get("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Job ID must be an integer")
            val job = jobService.getById(id)
            if (job != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = job))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Job not found", 404))
            }
        }
    }
}

/**
 * Authenticated job endpoints — mutations requiring a valid JWT.
 *
 * POST  /api/jobs/{id}/claim    — Claim an available job
 * PATCH /api/jobs/{id}/start    — Start a claimed job
 * PATCH /api/jobs/{id}/complete — Complete an in-progress job
 *
 * Must be called inside an `authenticate("auth-jwt") { }` block.
 */
fun Route.authenticatedJobRoutes() {
    val jobService = application.koinGet<JobService>()

    route("/api/jobs") {
        post("/{id}/claim") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Job ID must be an integer")
            val job = jobService.claimJob(id, call.workerId())
            call.respond(HttpStatusCode.OK, ApiResponse(data = job))
        }

        patch("/{id}/start") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Job ID must be an integer")
            val job = jobService.startJob(id, call.workerId())
            call.respond(HttpStatusCode.OK, ApiResponse(data = job))
        }

        patch("/{id}/complete") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Job ID must be an integer")
            val job = jobService.completeJob(id, call.workerId())
            call.respond(HttpStatusCode.OK, ApiResponse(data = job))
        }
    }
}
