package com.gcc.server.routes

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.RouteCheckpoints
import com.gcc.server.db.tables.Routes
import com.gcc.server.plugins.requireAdmin
import com.gcc.server.services.RouteService
import com.gcc.server.services.toRoute
import com.gcc.shared.model.AdminRouteRequest
import com.gcc.shared.model.ApiResponse
import com.gcc.shared.model.RouteStatus
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import com.gcc.server.plugins.koinGet

fun Route.adminRouteRoutes() {
    val routeService = application.koinGet<RouteService>()

    route("/api/admin/routes") {
        get {
            call.requireAdmin()
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
            val offset = call.parameters["offset"]?.toLongOrNull() ?: 0
            val status = call.parameters["status"]?.let {
                try { enumValueOf<RouteStatus>(it.uppercase()) } catch (_: Exception) { null }
            }
            val serviceAreaId = call.parameters["serviceAreaId"]?.toIntOrNull()
            val routes = routeService.getAll(
                status = status,
                serviceAreaId = serviceAreaId,
                limit = limit,
                offset = offset,
            )
            call.respond(HttpStatusCode.OK, ApiResponse(data = routes))
        }

        post {
            call.requireAdmin()
            val request = call.receive<AdminRouteRequest>()
            val now = Clock.System.now()
            val route = dbQuery {
                val id = Routes.insert {
                    it[code] = request.code
                    it[displayName] = request.displayName
                    it[serviceAreaId] = request.serviceAreaId
                    it[status] = "draft"
                    it[lengthMeters] = request.distanceMeters?.toString()
                    it[estimatedMinutes] = request.estimatedDurationMinutes?.toString()
                    it[payoutAmount] = request.payoutAmount?.toString() ?: "10000"
                    it[createdAt] = now
                    it[updatedAt] = now
                }[Routes.id]

                for (cp in request.checkpoints) {
                    RouteCheckpoints.insert {
                        it[routeId] = id
                        it[name] = cp.name
                        it[sequenceOrder] = cp.sequenceOrder
                        it[coordinates] = listOf(cp.lng, cp.lat)
                        it[photoRequired] = cp.photoRequired
                        it[geofenceRadius] = cp.geofenceRadius.toString()
                        it[type] = cp.type
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                }

                Routes.selectAll().where { Routes.id eq id }
                    .first().toRoute()
            }
            call.respond(HttpStatusCode.Created, ApiResponse(data = route))
        }

        put("/{id}") {
            call.requireAdmin()
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Route ID required")
            val request = call.receive<AdminRouteRequest>()
            val now = Clock.System.now()
            val updated = dbQuery {
                Routes.update({ Routes.id eq id }) {
                    it[code] = request.code
                    it[displayName] = request.displayName
                    it[serviceAreaId] = request.serviceAreaId
                    it[lengthMeters] = request.distanceMeters?.toString()
                    it[estimatedMinutes] = request.estimatedDurationMinutes?.toString()
                    it[payoutAmount] = request.payoutAmount?.toString() ?: "10000"
                    it[updatedAt] = now
                }
                Routes.selectAll().where { Routes.id eq id }
                    .firstOrNull()?.toRoute()
            }
            if (updated != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = updated))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Route not found", 404))
            }
        }

        put("/{id}/publish") {
            call.requireAdmin()
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Route ID required")
            val now = Clock.System.now()
            val updated = dbQuery {
                Routes.update({ Routes.id eq id }) {
                    it[status] = "published"
                    it[updatedAt] = now
                }
                Routes.selectAll().where { Routes.id eq id }
                    .firstOrNull()?.toRoute()
            }
            if (updated != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = updated))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Route not found", 404))
            }
        }

        put("/{id}/archive") {
            call.requireAdmin()
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Route ID required")
            val now = Clock.System.now()
            val updated = dbQuery {
                Routes.update({ Routes.id eq id }) {
                    it[status] = "archived"
                    it[updatedAt] = now
                }
                Routes.selectAll().where { Routes.id eq id }
                    .firstOrNull()?.toRoute()
            }
            if (updated != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = updated))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Route not found", 404))
            }
        }

        delete("/{id}") {
            call.requireAdmin()
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Route ID required")
            val deleted = dbQuery {
                RouteCheckpoints.deleteWhere { routeId eq id }
                Routes.deleteWhere { Routes.id eq id } > 0
            }
            if (deleted) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = mapOf("deleted" to true)))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Route not found", 404))
            }
        }
    }
}
