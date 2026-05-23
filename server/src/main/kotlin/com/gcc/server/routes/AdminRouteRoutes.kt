package com.gcc.server.routes

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.Blocks
import com.gcc.server.db.tables.Jobs
import com.gcc.server.db.tables.RouteCheckpoints
import com.gcc.server.db.tables.Routes
import com.gcc.server.db.tables.SponsorWallets
import com.gcc.server.db.tables.WalletTransactions
import com.gcc.server.plugins.ErrorResponse
import com.gcc.server.plugins.requireAdmin
import com.gcc.server.services.RouteNotificationService
import com.gcc.server.services.RouteService
import com.gcc.server.services.toJob
import com.gcc.server.services.toRoute
import com.gcc.shared.model.AdminRouteAssignmentRequest
import com.gcc.shared.model.AdminRouteRequest
import com.gcc.shared.model.ApiResponse
import com.gcc.shared.model.FundType
import com.gcc.shared.model.WalletReferenceType
import com.gcc.shared.model.WalletTransactionType
import com.gcc.shared.model.RouteStatus
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import com.gcc.server.plugins.koinGet

fun Route.adminRouteRoutes() {
    val routeService = application.koinGet<RouteService>()
    val routeNotificationService = application.koinGet<RouteNotificationService>()

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
            val request = call.receive<AdminRouteAssignmentRequest>()
            require(request.workerId > 0) { "workerId required" }
            val now = Clock.System.now()

            val job = dbQuery {
                val routeRow = Routes.selectAll()
                    .where { Routes.id eq routeId }
                    .firstOrNull()
                    ?: throw IllegalArgumentException("Route not found")

                val routePayout = routeRow[Routes.payoutAmount]?.toIntOrNull() ?: 0
                val payoutAmount = if (request.isTest) 0 else routePayout
                if (!request.isTest && payoutAmount <= 0) {
                    throw IllegalStateException("Paid routes require a positive payout amount")
                }

                val blockIds = Blocks.select(Blocks.id)
                    .where { Blocks.routeId eq routeId }
                    .map { it[Blocks.id] }
                val activeStatuses = listOf("claimed", "in_progress")
                val duplicateAssignment = if (blockIds.isEmpty()) {
                    null
                } else {
                    Jobs.selectAll()
                        .where {
                            (Jobs.blockId inList blockIds) and
                                (Jobs.workerId eq request.workerId) and
                                (Jobs.status inList activeStatuses)
                        }
                        .firstOrNull()
                }
                if (duplicateAssignment != null) {
                    throw IllegalStateException("Worker already has an active assignment for this route")
                }

                val blockId = blockIds.firstOrNull() ?: Blocks.insert {
                    it[code] = null
                    it[name] = routeRow[Routes.displayName]
                    it[serviceAreaId] = routeRow[Routes.serviceAreaId]
                    it[Blocks.routeId] = routeRow[Routes.id]
                    it[status] = if (request.isTest) "available" else "funded"
                    it[fundingGoal] = payoutAmount.toString()
                    it[fundingCurrent] = if (request.isTest) "0" else payoutAmount.toString()
                    it[createdAt] = now
                    it[updatedAt] = now
                }[Blocks.id]

                val jobId = Jobs.insert {
                    it[Jobs.blockId] = blockId
                    it[Jobs.workerId] = request.workerId
                    it[Jobs.status] = "claimed"
                    it[Jobs.claimedAt] = now
                    it[Jobs.payoutAmount] = payoutAmount.toString()
                    it[Jobs.payoutStatus] = if (request.isTest) null else "pending"
                    it[Jobs.createdAt] = now
                    it[Jobs.updatedAt] = now
                }[Jobs.id]

                if (!request.isTest) {
                    reserveFundsForPaidRouteJob(jobId, payoutAmount, now)
                }

                Jobs.selectAll()
                    .where { Jobs.id eq jobId }
                    .first()
                    .toJob(routeId = routeId)
            }
            runCatching {
                job.workerId?.let { workerId ->
                    routeNotificationService.sendDirectAssignment(
                        workerId = workerId,
                        routeId = routeId,
                        jobId = job.id,
                    )
                }
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

private fun reserveFundsForPaidRouteJob(jobId: Int, amount: Int, now: Instant) {
    val routeWallet = SponsorWallets.selectAll()
        .where { SponsorWallets.routeFundBalance greaterEq amount }
        .orderBy(SponsorWallets.routeFundBalance, SortOrder.DESC)
        .firstOrNull()
    if (routeWallet != null) {
        deductWalletForRouteJob(routeWallet, FundType.ROUTE, amount, jobId, now)
        return
    }

    val generalWallet = SponsorWallets.selectAll()
        .where { SponsorWallets.generalFundBalance greaterEq amount }
        .orderBy(SponsorWallets.generalFundBalance, SortOrder.DESC)
        .firstOrNull()
    if (generalWallet != null) {
        deductWalletForRouteJob(generalWallet, FundType.GENERAL, amount, jobId, now)
        return
    }

    throw IllegalStateException(
        "No Stripe-backed route funds are available for this paid assignment. Use a test assignment until funds are deposited and allocated."
    )
}

private fun deductWalletForRouteJob(
    walletRow: ResultRow,
    fundType: FundType,
    amount: Int,
    jobId: Int,
    now: Instant,
) {
    val walletId = walletRow[SponsorWallets.id]
    val currentFundBalance = when (fundType) {
        FundType.GENERAL -> walletRow[SponsorWallets.generalFundBalance]
        FundType.ROUTE -> walletRow[SponsorWallets.routeFundBalance]
        FundType.BOUNTY -> walletRow[SponsorWallets.bountyFundBalance]
    }
    val newFundBalance = currentFundBalance - amount

    SponsorWallets.update({ SponsorWallets.id eq walletId }) {
        when (fundType) {
            FundType.GENERAL -> it[generalFundBalance] = newFundBalance
            FundType.ROUTE -> it[routeFundBalance] = newFundBalance
            FundType.BOUNTY -> it[bountyFundBalance] = newFundBalance
        }
        it[totalSpent] = walletRow[SponsorWallets.totalSpent] + amount
        it[updatedAt] = now
    }

    WalletTransactions.insert {
        it[WalletTransactions.walletId] = walletId
        it[type] = WalletTransactionType.JOB_DEDUCTION.name.lowercase()
        it[WalletTransactions.fundType] = fundType.name.lowercase()
        it[WalletTransactions.amount] = -amount
        it[balanceAfter] = newFundBalance
        it[referenceId] = jobId
        it[referenceType] = WalletReferenceType.JOB.name.lowercase()
        it[description] = "Reserved route payout for assigned job #$jobId"
        it[createdAt] = now
    }
}
