package com.gcc.server.routes

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.RouteCheckpoints
import com.gcc.server.db.tables.Routes
import com.gcc.server.plugins.ErrorResponse
import com.gcc.server.plugins.requireAdmin
import com.gcc.server.services.JobService
import com.gcc.server.services.RouteService
import com.gcc.server.services.toJob
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
                    it[imageUrl] = request.imageUrl
                    it[imageLocationLat] = request.imageLocationLat
                    it[imageLocationLng] = request.imageLocationLng
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
                        it[imageUrl] = cp.imageUrl
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
                    it[imageUrl] = request.imageUrl
                    it[imageLocationLat] = request.imageLocationLat
                    it[imageLocationLng] = request.imageLocationLng
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

        put("/{id}/image-location") {
            call.requireAdmin()
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Route ID required")
            val body = call.receive<Map<String, Double?>>()
            val lat = body["imageLocationLat"]
            val lng = body["imageLocationLng"]
            val now = Clock.System.now()
            val updated = dbQuery {
                Routes.update({ Routes.id eq id }) {
                    it[imageLocationLat] = lat
                    it[imageLocationLng] = lng
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

        // POST /api/admin/routes/{id}/assign — assign route to a specific worker
        post("/{id}/assign") {
            call.requireAdmin()
            val routeId = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Route ID required")
            val body = call.receive<Map<String, Any>>()
            val workerId = (body["workerId"] as? Number)?.toInt()
                ?: throw IllegalArgumentException("workerId required")
            val isTest = (body["isTest"] as? Boolean) ?: false
            val now = Clock.System.now()

            val job = dbQuery {
                // Find the first block for this route, or use a placeholder blockId
                val blockId = com.gcc.server.db.tables.Blocks
                    .selectAll()
                    .where { com.gcc.server.db.tables.Blocks.routeId eq routeId }
                    .firstOrNull()?.get(com.gcc.server.db.tables.Blocks.id)
                    ?: throw IllegalStateException("No blocks found for route $routeId")

                val jobId = com.gcc.server.db.tables.Jobs.insert {
                    it[com.gcc.server.db.tables.Jobs.blockId] = blockId
                    it[com.gcc.server.db.tables.Jobs.workerId] = workerId
                    it[com.gcc.server.db.tables.Jobs.status] = "assigned"
                    it[com.gcc.server.db.tables.Jobs.payoutAmount] = if (isTest) "0" else null
                    it[com.gcc.server.db.tables.Jobs.createdAt] = now
                    it[com.gcc.server.db.tables.Jobs.updatedAt] = now
                }[com.gcc.server.db.tables.Jobs.id]

                com.gcc.server.db.tables.Jobs
                    .selectAll()
                    .where { com.gcc.server.db.tables.Jobs.id eq jobId }
                    .first()
                    .toJob()
            }
            call.respond(HttpStatusCode.Created, ApiResponse(data = job))
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
