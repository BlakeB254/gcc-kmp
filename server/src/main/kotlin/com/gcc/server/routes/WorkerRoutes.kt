package com.gcc.server.routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class WorkerResponse(
    val id: String,
    val name: String,
    val email: String,
    val status: String
)

@Serializable
data class RegisterRequest(
    val name: String,
    val email: String,
    val password: String,
    val phone: String? = null
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class AuthResponse(
    val token: String,
    val worker: WorkerResponse
)

@Serializable
data class MessageResponse(val message: String)

fun Routing.workerRoutes() {
    route("/api/workers") {
        get {
            // TODO: Replace with database query
            val mockWorkers = listOf(
                WorkerResponse("w-001", "Marcus Johnson", "marcus@example.com", "active"),
                WorkerResponse("w-002", "Terrence Williams", "terrence@example.com", "active"),
                WorkerResponse("w-003", "DeShawn Carter", "deshawn@example.com", "on_route")
            )
            call.respond(HttpStatusCode.OK, mockWorkers)
        }

        get("/{id}") {
            val id = call.parameters["id"] ?: throw IllegalArgumentException("Worker ID required")
            // TODO: Replace with database lookup
            val mockWorker = WorkerResponse(id, "Marcus Johnson", "marcus@example.com", "active")
            call.respond(HttpStatusCode.OK, mockWorker)
        }

        post("/register") {
            val request = call.receive<RegisterRequest>()
            // TODO: Implement registration with database + password hashing
            val mockResponse = AuthResponse(
                token = "placeholder-jwt-token",
                worker = WorkerResponse("w-new", request.name, request.email, "pending")
            )
            call.respond(HttpStatusCode.Created, mockResponse)
        }

        post("/login") {
            val request = call.receive<LoginRequest>()
            // TODO: Implement login with database lookup + bcrypt verification
            val mockResponse = AuthResponse(
                token = "placeholder-jwt-token",
                worker = WorkerResponse("w-001", "Marcus Johnson", request.email, "active")
            )
            call.respond(HttpStatusCode.OK, mockResponse)
        }
    }
}
