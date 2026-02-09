package com.gcc.server.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String = "ok",
    val service: String = "gcc-api",
    val version: String = "0.1.0"
)

fun Routing.healthRoutes() {
    get("/api/health") {
        call.respond(HttpStatusCode.OK, HealthResponse())
    }
}
