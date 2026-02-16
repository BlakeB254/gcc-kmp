package com.gcc.server.routes

import com.gcc.server.plugins.requireAdmin
import com.gcc.server.services.AdminService
import com.gcc.shared.model.AddressChangeRequest
import com.gcc.shared.model.ApiResponse
import com.gcc.shared.model.BulkActionRequest
import com.gcc.shared.model.UpdateWorkerRoleRequest
import com.gcc.shared.model.VerifyWorkerRequest
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.gcc.server.plugins.koinGet

fun Route.adminWorkerRoutes() {
    val adminService = application.koinGet<AdminService>()

    route("/api/admin/workers") {
        get {
            call.requireAdmin()
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
            val offset = call.parameters["offset"]?.toLongOrNull() ?: 0
            val search = call.parameters["search"]
            val workers = adminService.getAllWorkers(limit, offset, search)
            call.respond(HttpStatusCode.OK, ApiResponse(data = workers))
        }

        get("/pending-verification") {
            call.requireAdmin()
            val workers = adminService.getWorkersByVerificationStatus("pending")
            call.respond(HttpStatusCode.OK, ApiResponse(data = workers))
        }

        post("/{id}/verify") {
            call.requireAdmin()
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Worker ID required")
            val request = call.receive<VerifyWorkerRequest>()
            val worker = adminService.verifyWorker(
                workerId = id,
                status = request.status,
                governmentIdStatus = request.governmentIdStatus,
                proofOfAddressStatus = request.proofOfAddressStatus,
                reviewNotes = request.reviewNotes,
            )
            if (worker != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = worker))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Worker not found", 404))
            }
        }

        put("/{id}/role") {
            call.requireAdmin()
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Worker ID required")
            val request = call.receive<UpdateWorkerRoleRequest>()
            val worker = adminService.updateWorkerRole(id, request.role.name.lowercase())
            if (worker != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = worker))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Worker not found", 404))
            }
        }

        put("/{id}/address") {
            call.requireAdmin()
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Worker ID required")
            val request = call.receive<AddressChangeRequest>()
            val worker = adminService.updateWorkerAddress(id, request.newAddress)
            if (worker != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = worker))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Worker not found", 404))
            }
        }

        post("/bulk-verify") {
            call.requireAdmin()
            val request = call.receive<BulkActionRequest>()
            val count = adminService.bulkVerifyWorkers(request.workerIds)
            call.respond(HttpStatusCode.OK, ApiResponse(data = mapOf("updated" to count)))
        }

        delete("/{id}") {
            call.requireAdmin()
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Worker ID required")
            val deleted = adminService.deleteWorker(id)
            if (deleted) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = mapOf("deleted" to true)))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Worker not found", 404))
            }
        }
    }
}
