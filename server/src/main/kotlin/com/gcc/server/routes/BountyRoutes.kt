package com.gcc.server.routes

import com.gcc.server.plugins.workerId
import com.gcc.server.services.BountyService
import com.gcc.server.services.WalletService
import com.gcc.shared.model.AddPledgeRequest
import com.gcc.shared.model.ApiResponse
import com.gcc.shared.model.Bounty
import com.gcc.shared.model.BountyPledge
import com.gcc.shared.model.BountyWithPledges
import com.gcc.shared.model.CompleteBountyRequest
import com.gcc.shared.model.CreateBountyRequest
import com.gcc.shared.model.PosterType
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get as koinGet

/**
 * Public bounty endpoints.
 *
 * GET  /api/bounties               — List open bounties (with location/area filters)
 * GET  /api/bounties/{id}          — Single bounty
 * GET  /api/bounties/{id}/pledges  — Bounty with all pledges (detail view)
 */
fun Routing.bountyRoutes() {
    val bountyService = koinGet<BountyService>()

    route("/api/bounties") {
        get {
            val lat = call.parameters["lat"]?.toDoubleOrNull()
            val lng = call.parameters["lng"]?.toDoubleOrNull()
            val radius = call.parameters["radius"]?.toDoubleOrNull()
            val city = call.parameters["city"]
            val communityArea = call.parameters["community_area"]
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 50

            val bounties = bountyService.getOpenBounties(
                lat = lat, lng = lng, radiusMeters = radius,
                city = city, communityArea = communityArea, limit = limit,
            )
            call.respond(HttpStatusCode.OK, ApiResponse(data = bounties))
        }

        get("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Bounty ID must be an integer")
            val bounty = bountyService.getById(id)
            if (bounty != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = bounty))
            } else {
                call.respond(HttpStatusCode.NotFound, ApiResponse(
                    data = null as Bounty?, success = false, message = "Bounty not found"
                ))
            }
        }

        get("/{id}/pledges") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Bounty ID must be an integer")
            val detail = bountyService.getBountyWithPledges(id)
            if (detail != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = detail))
            } else {
                call.respond(HttpStatusCode.NotFound, ApiResponse(
                    data = null as BountyWithPledges?, success = false, message = "Bounty not found"
                ))
            }
        }
    }
}

/**
 * Authenticated bounty endpoints.
 *
 * POST   /api/bounties                — Create bounty (from wallet funds)
 * POST   /api/bounties/{id}/claim     — Claim a bounty
 * POST   /api/bounties/{id}/accept    — Observer accepts first-refusal
 * POST   /api/bounties/{id}/decline   — Observer declines first-refusal
 * PATCH  /api/bounties/{id}/complete  — Mark bounty cleanup complete
 * POST   /api/bounties/{id}/pledge    — Add a pledge to existing bounty (stackable)
 * GET    /api/bounties/my-pledges     — List current user's pledges
 * POST   /api/pledges/{id}/renew      — Renew an expired pledge for 24h
 * POST   /api/pledges/{id}/decline    — Decline renewal (triggers refund + recalculate)
 */
fun Route.authenticatedBountyRoutes() {
    val bountyService = application.koinGet<BountyService>()
    val walletService = application.koinGet<WalletService>()

    route("/api/bounties") {
        post {
            val request = call.receive<CreateBountyRequest>()
            val workerId = call.workerId()
            val wallet = walletService.getOrCreateWallet(workerId = workerId)

            val bounty = bountyService.createBounty(
                posterType = PosterType.WORKER,
                posterId = workerId,
                walletId = wallet.id,
                title = request.title,
                description = request.description,
                amount = request.amount,
                lat = request.locationLat,
                lng = request.locationLng,
                radiusMeters = request.radiusMeters,
                communityArea = request.communityArea,
                neighborhood = request.neighborhood,
                mediaUrls = request.mediaUrls,
            )
            call.respond(HttpStatusCode.Created, ApiResponse(data = bounty))
        }

        post("/{id}/claim") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Bounty ID must be an integer")
            val bounty = bountyService.claimBounty(id, call.workerId())
            call.respond(HttpStatusCode.OK, ApiResponse(data = bounty))
        }

        post("/{id}/accept") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Bounty ID must be an integer")
            val bounty = bountyService.acceptFirstRefusal(id, call.workerId())
            call.respond(HttpStatusCode.OK, ApiResponse(data = bounty))
        }

        post("/{id}/decline") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Bounty ID must be an integer")
            val bounty = bountyService.declineFirstRefusal(id)
            call.respond(HttpStatusCode.OK, ApiResponse(data = bounty))
        }

        patch("/{id}/complete") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Bounty ID must be an integer")
            val request = call.receive<CompleteBountyRequest>()
            val bounty = bountyService.completeBounty(id, call.workerId(), request.afterMediaUrls)
            call.respond(HttpStatusCode.OK, ApiResponse(data = bounty))
        }

        // Stackable pledges — add funding to an existing bounty
        post("/{id}/pledge") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Bounty ID must be an integer")
            val request = call.receive<AddPledgeRequest>()
            val workerId = call.workerId()
            val wallet = walletService.getOrCreateWallet(workerId = workerId)

            val pledge = bountyService.addPledge(
                bountyId = id,
                pledgerType = PosterType.WORKER,
                pledgerId = workerId,
                walletId = wallet.id,
                amount = request.amount,
            )
            call.respond(HttpStatusCode.Created, ApiResponse(data = pledge))
        }

        // My pledges across all bounties
        get("/my-pledges") {
            val pledges = bountyService.getPledgesByPledger(call.workerId())
            call.respond(HttpStatusCode.OK, ApiResponse(data = pledges))
        }
    }

    // Pledge lifecycle routes (at /api/pledges level)
    route("/api/pledges") {
        post("/{id}/renew") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Pledge ID must be an integer")
            val pledge = bountyService.renewPledge(id)
            call.respond(HttpStatusCode.OK, ApiResponse(data = pledge))
        }

        post("/{id}/decline") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Pledge ID must be an integer")
            val pledge = bountyService.declineRenewal(id)
            call.respond(HttpStatusCode.OK, ApiResponse(data = pledge))
        }
    }
}
