package com.gcc.server.routes

import com.gcc.server.plugins.koinGet
import com.gcc.server.plugins.workerId
import com.gcc.server.services.VerificationService
import com.gcc.shared.model.ApiResponse
import com.gcc.shared.model.VerifySpotRequest
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Public verification endpoints.
 *
 * GET /api/spots/needs-verification  — Spots needing re-verification
 * GET /api/spots/{id}/verifications  — Verification history for a spot
 */
fun Routing.verificationRoutes() {
    val verificationService = koinGet<VerificationService>()

    route("/api/spots") {
        get("/needs-verification") {
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
            val spots = verificationService.getSpotsNeedingVerification(limit)
            call.respond(HttpStatusCode.OK, ApiResponse(data = spots))
        }

        get("/{id}/verifications") {
            val spotId = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Spot ID must be an integer")
            val verifications = verificationService.getVerificationsForSpot(spotId)
            call.respond(HttpStatusCode.OK, ApiResponse(data = verifications))
        }
    }
}

/**
 * Authenticated verification endpoint.
 *
 * POST /api/spots/{id}/verify — Submit a verification (still dirty or already clean)
 */
fun Route.authenticatedVerificationRoutes() {
    val verificationService = application.koinGet<VerificationService>()

    route("/api/spots") {
        post("/{id}/verify") {
            val spotId = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Spot ID must be an integer")
            val request = call.receive<VerifySpotRequest>()
            val result = verificationService.verifySpot(
                spotId = spotId,
                verifierId = call.workerId(),
                request = request,
            )
            call.respond(HttpStatusCode.OK, ApiResponse(data = result))
        }
    }
}
