package com.gcc.server.routes

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.Blocks
import com.gcc.server.plugins.ErrorResponse
import com.gcc.server.services.toBlock
import com.gcc.server.util.adminRoute
import com.gcc.server.util.intPathParam
import com.gcc.server.util.intQueryParam
import com.gcc.server.util.longQueryParam
import com.gcc.shared.model.AdminBlockRequest
import com.gcc.shared.model.ApiResponse
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

fun Route.adminBlockRoutes() {
    adminRoute("/api/admin/blocks") {
        get {
            val limit = call.intQueryParam("limit", 50) ?: 50
            val offset = call.longQueryParam("offset", 0) ?: 0
            val serviceAreaId = call.intQueryParam("serviceAreaId")
            val blocks = dbQuery {
                val query = Blocks.selectAll()
                serviceAreaId?.let { query.andWhere { Blocks.serviceAreaId eq it } }
                query.orderBy(Blocks.code, SortOrder.ASC)
                    .limit(limit).offset(offset)
                    .map { it.toBlock() }
            }
            call.respond(HttpStatusCode.OK, ApiResponse(data = blocks))
        }

        post {
            val request = call.receive<AdminBlockRequest>()
            val now = Clock.System.now()
            val block = dbQuery {
                val id = Blocks.insert {
                    it[code] = request.code
                    it[name] = request.name
                    it[serviceAreaId] = request.serviceAreaId
                    it[fundingGoal] = request.fundingGoal?.toString() ?: "100"
                    it[fundedById] = request.sponsorId
                    it[status] = "available"
                    it[createdAt] = now
                    it[updatedAt] = now
                }[Blocks.id]

                Blocks.selectAll().where { Blocks.id eq id }
                    .first().toBlock()
            }
            call.respond(HttpStatusCode.Created, ApiResponse(data = block))
        }

        put("/{id}") {
            val id = call.intPathParam("id")
            val request = call.receive<AdminBlockRequest>()
            val now = Clock.System.now()
            val updated = dbQuery {
                Blocks.update({ Blocks.id eq id }) {
                    it[code] = request.code
                    it[name] = request.name
                    it[serviceAreaId] = request.serviceAreaId
                    it[fundingGoal] = request.fundingGoal?.toString() ?: "100"
                    it[fundedById] = request.sponsorId
                    it[updatedAt] = now
                }
                Blocks.selectAll().where { Blocks.id eq id }
                    .firstOrNull()?.toBlock()
            }
            if (updated != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = updated))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Block not found", 404))
            }
        }

        delete("/{id}") {
            val id = call.intPathParam("id")
            val deleted = dbQuery {
                Blocks.deleteWhere { Blocks.id eq id } > 0
            }
            if (deleted) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = mapOf("deleted" to true)))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Block not found", 404))
            }
        }
    }
}
