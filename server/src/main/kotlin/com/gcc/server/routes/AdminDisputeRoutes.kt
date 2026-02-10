package com.gcc.server.routes

import com.gcc.server.plugins.requireAdmin
import com.gcc.server.plugins.workerId
import com.gcc.server.services.DisputeService
import com.gcc.shared.model.ApiResponse
import com.gcc.shared.model.CreateDisputeRequest
import com.gcc.shared.model.ResolveDisputeRequest
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get as koinGet

fun Route.adminDisputeRoutes() {
    val disputeService = application.koinGet<DisputeService>()

    route("/api/admin/disputes") {
        get {
            call.requireAdmin()
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
            val offset = call.parameters["offset"]?.toLongOrNull() ?: 0
            val status = call.parameters["status"]
            val disputes = disputeService.getAll(
                status = status,
                limit = limit,
                offset = offset,
            )
            call.respond(HttpStatusCode.OK, ApiResponse(data = disputes))
        }

        get("/{id}") {
            call.requireAdmin()
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Dispute ID required")
            val dispute = disputeService.getById(id)
            if (dispute != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = dispute))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Dispute not found", 404))
            }
        }

        post {
            call.requireAdmin()
            val request = call.receive<CreateDisputeRequest>()
            val adminId = call.workerId()
            val dispute = disputeService.create(
                jobId = request.jobId,
                reporterId = adminId,
                reporterType = "admin",
                respondentId = request.respondentId,
                respondentType = request.respondentType.name.lowercase(),
                type = request.type.name.lowercase(),
                description = request.description,
                evidence = request.evidence,
            )
            call.respond(HttpStatusCode.Created, ApiResponse(data = dispute))
        }

        put("/{id}/review") {
            call.requireAdmin()
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Dispute ID required")
            val dispute = disputeService.startReview(id)
            if (dispute != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = dispute))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Dispute not found", 404))
            }
        }

        put("/{id}/resolve") {
            call.requireAdmin()
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Dispute ID required")
            val adminId = call.workerId()
            val request = call.receive<ResolveDisputeRequest>()
            val dispute = disputeService.resolve(
                disputeId = id,
                adminId = adminId,
                resolution = request.resolution.name.lowercase(),
                notes = request.notes,
                refundAmount = request.refundAmount,
            )
            if (dispute != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = dispute))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Dispute not found", 404))
            }
        }
    }
}
