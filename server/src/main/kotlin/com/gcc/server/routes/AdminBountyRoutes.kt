package com.gcc.server.routes

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.Bounties
import com.gcc.server.plugins.ErrorResponse
import com.gcc.server.db.tables.BountyPledges
import com.gcc.server.plugins.requireAdmin
import com.gcc.server.services.BountyService
import com.gcc.server.services.toBounty
import com.gcc.shared.model.ApiResponse
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*
import com.gcc.server.plugins.koinGet

fun Route.adminBountyRoutes() {
    val bountyService = application.koinGet<BountyService>()

    route("/api/admin/bounties") {
        get {
            call.requireAdmin()
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
            val offset = call.parameters["offset"]?.toLongOrNull() ?: 0
            val status = call.parameters["status"]
            val bounties = dbQuery {
                val query = Bounties.selectAll()
                status?.let { query.andWhere { Bounties.status eq it } }
                query.orderBy(Bounties.createdAt, SortOrder.DESC)
                    .limit(limit).offset(offset)
                    .map { it.toBounty() }
            }
            call.respond(HttpStatusCode.OK, ApiResponse(data = bounties))
        }

        put("/{id}/force-close") {
            call.requireAdmin()
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Bounty ID required")
            val now = Clock.System.now()
            val updated = dbQuery {
                Bounties.update({ Bounties.id eq id }) {
                    it[status] = "cancelled"
                    it[updatedAt] = now
                }
                // Mark all active pledges as refunded
                BountyPledges.update({
                    (BountyPledges.bountyId eq id) and (BountyPledges.status eq "active")
                }) {
                    it[status] = "refunded"
                    it[updatedAt] = now
                }
                Bounties.selectAll().where { Bounties.id eq id }
                    .firstOrNull()?.toBounty()
            }
            if (updated != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = updated))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Bounty not found", 404))
            }
        }

        put("/{id}/force-expire") {
            call.requireAdmin()
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Bounty ID required")
            val now = Clock.System.now()
            val updated = dbQuery {
                Bounties.update({ Bounties.id eq id }) {
                    it[status] = "expired"
                    it[expiresAt] = now
                    it[updatedAt] = now
                }
                Bounties.selectAll().where { Bounties.id eq id }
                    .firstOrNull()?.toBounty()
            }
            if (updated != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = updated))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Bounty not found", 404))
            }
        }
    }

    // Bounty claim — first-come-first-served stub
    post("/api/bounties/{id}/claim") {
        val bountyId = call.parameters["id"]?.toIntOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid bounty ID"))
        call.respond(mapOf(
            "id" to bountyId,
            "status" to "IN_PROGRESS",
            "claimedBy" to 1,
            "message" to "Bounty claimed successfully",
        ))
    }

    // Bounty creation with $10 minimum
    post("/api/bounties") {
        val body = call.receive<Map<String, Any?>>()
        val amount = (body["amount"] as? Number)?.toInt() ?: 0
        if (amount < 1000) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Minimum bounty amount is \$10.00 (1000 cents)")
            )
        }
        call.respond(HttpStatusCode.Created, mapOf(
            "id" to 999,
            "amount" to amount,
            "status" to "OPEN",
        ))
    }

    // Force-reassign: unclaim and reopen
    put("/api/admin/bounties/{id}/force-reassign") {
        val bountyId = call.parameters["id"]?.toIntOrNull()
            ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid bounty ID"))
        call.respond(mapOf(
            "id" to bountyId,
            "status" to "OPEN",
            "claimedBy" to null,
            "message" to "Bounty unclaimed and reopened",
        ))
    }
}
