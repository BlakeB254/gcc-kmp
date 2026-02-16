package com.gcc.server.routes

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.Jobs
import com.gcc.server.plugins.requireAdmin
import com.gcc.server.services.DisputeService
import com.gcc.server.services.JobService
import com.gcc.server.services.toJob
import com.gcc.shared.model.ApiResponse
import com.gcc.shared.model.ForceJobStatusRequest
import com.gcc.shared.model.JobStatus
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*
import com.gcc.server.plugins.koinGet

fun Route.adminJobRoutes() {
    val jobService = application.koinGet<JobService>()
    val disputeService = application.koinGet<DisputeService>()

    route("/api/admin/jobs") {
        get {
            call.requireAdmin()
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
            val offset = call.parameters["offset"]?.toLongOrNull() ?: 0
            val status = call.parameters["status"]?.let {
                try { enumValueOf<JobStatus>(it.uppercase()) } catch (_: Exception) { null }
            }
            val workerId = call.parameters["workerId"]?.toIntOrNull()
            val jobs = jobService.getAll(
                status = status,
                workerId = workerId,
                limit = limit,
                offset = offset,
            )
            call.respond(HttpStatusCode.OK, ApiResponse(data = jobs))
        }

        get("/{id}") {
            call.requireAdmin()
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Job ID required")
            val job = jobService.getById(id)
            if (job != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = job))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Job not found", 404))
            }
        }

        put("/{id}/status") {
            call.requireAdmin()
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Job ID required")
            val request = call.receive<ForceJobStatusRequest>()
            val now = Clock.System.now()
            val updated = dbQuery {
                Jobs.update({ Jobs.id eq id }) {
                    it[status] = request.status.name.lowercase()
                    it[ratingNotes] = request.reason
                    it[updatedAt] = now
                }
                Jobs.selectAll().where { Jobs.id eq id }
                    .firstOrNull()?.toJob()
            }
            if (updated != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = updated))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Job not found", 404))
            }
        }

        get("/{id}/disputes") {
            call.requireAdmin()
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Job ID required")
            val disputes = disputeService.getByJob(id)
            call.respond(HttpStatusCode.OK, ApiResponse(data = disputes))
        }
    }
}
