package com.gcc.server.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class RouteResponse(
    val id: String,
    val name: String,
    val totalStops: Int,
    val estimatedDuration: String,
    val status: String
)

fun Routing.routeRoutes() {
    route("/api/routes") {
        get {
            // TODO: Replace with database query
            val mockRoutes = listOf(
                RouteResponse("r-001", "South Side Morning", 24, "4h 30m", "active"),
                RouteResponse("r-002", "West Loop Express", 18, "3h 15m", "active"),
                RouteResponse("r-003", "North Avenue Run", 31, "5h 45m", "pending")
            )
            call.respond(HttpStatusCode.OK, mockRoutes)
        }

        get("/{id}") {
            val id = call.parameters["id"] ?: throw IllegalArgumentException("Route ID required")
            // TODO: Replace with database lookup
            val mockRoute = RouteResponse(id, "South Side Morning", 24, "4h 30m", "active")
            call.respond(HttpStatusCode.OK, mockRoute)
        }
    }
}
