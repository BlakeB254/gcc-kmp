package com.gcc.server.routes

import com.gcc.server.plugins.isAdmin
import com.gcc.server.plugins.workerId
import com.gcc.server.services.SpotService
import com.gcc.server.services.VolunteerService
import com.gcc.shared.model.ApiResponse
import com.gcc.shared.model.CommunityReport
import com.gcc.shared.model.CreateSpotRequest
import com.gcc.shared.model.SpotResponse
import com.gcc.shared.model.StartVolunteerRequest
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.gcc.server.plugins.koinGet

/**
 * Public spot/observation endpoints.
 *
 * GET  /api/spots       — List reports (with lat/lng/radius filters)
 * GET  /api/spots/{id}  — Single report
 */
fun Routing.spotRoutes() {
    val spotService = koinGet<SpotService>()

    route("/api/spots") {
        get {
            val lat = call.parameters["lat"]?.toDoubleOrNull()
            val lng = call.parameters["lng"]?.toDoubleOrNull()
            val radius = call.parameters["radius"]?.toDoubleOrNull() ?: 500.0
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 50

            val reports = if (lat != null && lng != null) {
                spotService.getReportsByArea(lat, lng, radius, limit)
            } else {
                spotService.getAll(limit)
            }
            call.respond(HttpStatusCode.OK, ApiResponse(data = reports))
        }

        get("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Report ID must be an integer")
            val report = spotService.getById(id)
            if (report != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = report))
            } else {
                call.respond(HttpStatusCode.NotFound, ApiResponse(data = null as CommunityReport?, success = false, message = "Report not found"))
            }
        }
    }
}

/**
 * Authenticated spot endpoints.
 *
 * POST /api/spots               — Create spot report (returns report + matching bounties)
 * POST /api/spots/{id}/volunteer — Start volunteer cleanup from this report
 */
fun Route.authenticatedSpotRoutes() {
    val spotService = application.koinGet<SpotService>()
    val volunteerService = application.koinGet<VolunteerService>()

    route("/api/spots") {
        post {
            val request = call.receive<CreateSpotRequest>()
            val report = spotService.createReport(
                reporterId = call.workerId(),
                type = request.type,
                lat = request.lat,
                lng = request.lng,
                description = request.description,
                mediaUrls = request.mediaUrls,
                isAdmin = call.isAdmin(),
            )

            // Check for matching bounty zones
            val matchingBounties = spotService.checkBountyZones(request.lat, request.lng)

            call.respond(HttpStatusCode.Created, ApiResponse(
                data = SpotResponse(report = report, matchingBounties = matchingBounties)
            ))
        }

        post("/{id}/volunteer") {
            val reportId = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Report ID must be an integer")
            val request = call.receive<StartVolunteerRequest>()
            val cleanup = volunteerService.startCleanup(
                reportId = reportId,
                workerId = call.workerId(),
                beforeMediaUrls = request.beforeMediaUrls,
            )
            call.respond(HttpStatusCode.Created, ApiResponse(data = cleanup))
        }
    }
}
