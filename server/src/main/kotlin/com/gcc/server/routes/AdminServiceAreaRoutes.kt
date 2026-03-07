package com.gcc.server.routes

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.ServiceAreas
import com.gcc.server.plugins.ErrorResponse
import com.gcc.server.plugins.requireAdmin
import com.gcc.server.services.toServiceArea
import com.gcc.shared.model.AdminServiceAreaRequest
import com.gcc.shared.model.ApiResponse
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

fun Route.adminServiceAreaRoutes() {
    route("/api/admin/service-areas") {
        get {
            call.requireAdmin()
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
            val offset = call.parameters["offset"]?.toLongOrNull() ?: 0
            val areas = dbQuery {
                ServiceAreas.selectAll()
                    .orderBy(ServiceAreas.name, SortOrder.ASC)
                    .limit(limit).offset(offset)
                    .map { it.toServiceArea() }
            }
            call.respond(HttpStatusCode.OK, ApiResponse(data = areas))
        }

        post {
            call.requireAdmin()
            val request = call.receive<AdminServiceAreaRequest>()
            val now = Clock.System.now()
            val area = dbQuery {
                val id = ServiceAreas.insert {
                    it[name] = request.name
                    it[city] = request.city
                    it[state] = request.state
                    request.boundary?.let { b -> it[boundary] = b }
                    request.mapCenter?.let { c -> it[mapCenter] = listOf(c.lng, c.lat) }
                    it[defaultZoom] = request.mapZoom.toString()
                    it[status] = request.status
                    it[createdAt] = now
                    it[updatedAt] = now
                }[ServiceAreas.id]

                ServiceAreas.selectAll().where { ServiceAreas.id eq id }
                    .first().toServiceArea()
            }
            call.respond(HttpStatusCode.Created, ApiResponse(data = area))
        }

        put("/{id}") {
            call.requireAdmin()
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Service Area ID required")
            val request = call.receive<AdminServiceAreaRequest>()
            val now = Clock.System.now()
            val updated = dbQuery {
                ServiceAreas.update({ ServiceAreas.id eq id }) {
                    it[name] = request.name
                    it[city] = request.city
                    it[state] = request.state
                    request.boundary?.let { b -> it[boundary] = b }
                    request.mapCenter?.let { c -> it[mapCenter] = listOf(c.lng, c.lat) }
                    it[defaultZoom] = request.mapZoom.toString()
                    it[status] = request.status
                    it[updatedAt] = now
                }
                ServiceAreas.selectAll().where { ServiceAreas.id eq id }
                    .firstOrNull()?.toServiceArea()
            }
            if (updated != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = updated))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Service area not found", 404))
            }
        }

        delete("/{id}") {
            call.requireAdmin()
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Service Area ID required")
            val deleted = dbQuery {
                ServiceAreas.deleteWhere { ServiceAreas.id eq id } > 0
            }
            if (deleted) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = mapOf("deleted" to true)))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Service area not found", 404))
            }
        }
    }
}
