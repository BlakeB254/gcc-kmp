package com.gcc.server.routes

import com.gcc.server.plugins.koinGet
import com.gcc.server.plugins.requireAdmin
import com.gcc.server.plugins.workerId
import com.gcc.server.services.ScanService
import com.gcc.shared.model.AdvancePhaseRequest
import com.gcc.shared.model.ApiResponse
import com.gcc.shared.model.CompleteScanRequest
import com.gcc.shared.model.CompleteScanResponse
import com.gcc.shared.model.CreateHotspotRequest
import com.gcc.shared.model.CreateScanRequest
import com.gcc.shared.model.RouteScan
import com.gcc.shared.model.ScanHotspot
import com.gcc.shared.model.UpdateHotspotRequest
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Authenticated scan endpoints (all require Bearer JWT).
 *
 * POST   /api/scans                       — Create a new route scan
 * GET    /api/scans/{id}                  — Get scan by ID
 * PATCH  /api/scans/{id}/phase            — Advance scan phase
 * POST   /api/scans/{id}/hotspots         — Add hotspot to scan
 * PATCH  /api/scans/{id}/hotspots/{hid}   — Update hotspot (e.g. mark cleaned)
 * PATCH  /api/scans/{id}/coverage         — Update coverage percent
 * POST   /api/scans/{id}/complete         — Complete the scan and award points
 *
 * Admin-only:
 * GET    /api/admin/scans                 — List all scans
 * GET    /api/admin/scans/{id}            — Get scan by ID (admin view)
 */
fun Route.scanRoutes() {
    val scanService = application.koinGet<ScanService>()

    route("/api/scans") {

        // Create a new route scan
        post {
            val request = call.receive<CreateScanRequest>()
            val scan = scanService.createScan(call.workerId(), request)
            call.respond(HttpStatusCode.Created, ApiResponse(data = scan))
        }

        // Get single scan by ID
        get("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Scan ID must be an integer")
            val scan = scanService.getScanById(id)
            if (scan != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = scan))
            } else {
                call.respond(
                    HttpStatusCode.NotFound,
                    ApiResponse(data = null as RouteScan?, success = false, message = "Scan not found")
                )
            }
        }

        // Advance scan phase (scouting → scout_review → cleaning → complete)
        patch("/{id}/phase") {
            val scanId = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Scan ID must be an integer")
            val request = call.receive<AdvancePhaseRequest>()
            val scan = scanService.advancePhase(scanId, request)
            call.respond(HttpStatusCode.OK, ApiResponse(data = scan))
        }

        // Update coverage percent (uses same AdvancePhaseRequest, phase field may remain unchanged)
        patch("/{id}/coverage") {
            val scanId = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Scan ID must be an integer")
            val request = call.receive<AdvancePhaseRequest>()
            val scan = scanService.advancePhase(scanId, request)
            call.respond(HttpStatusCode.OK, ApiResponse(data = scan))
        }

        // Add hotspot to a scan
        post("/{id}/hotspots") {
            val scanId = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Scan ID must be an integer")
            val request = call.receive<CreateHotspotRequest>()
            val hotspot = scanService.addHotspot(scanId, request)
            call.respond(HttpStatusCode.Created, ApiResponse(data = hotspot))
        }

        // Update an existing hotspot (after-photo, cleaned status, verification score)
        patch("/{id}/hotspots/{hid}") {
            val hotspotId = call.parameters["hid"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Hotspot ID must be an integer")
            val request = call.receive<UpdateHotspotRequest>()
            val hotspot = scanService.updateHotspot(hotspotId, request)
            call.respond(HttpStatusCode.OK, ApiResponse(data = hotspot))
        }

        // Complete the scan — finalizes phase, calculates and awards points
        post("/{id}/complete") {
            val scanId = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Scan ID must be an integer")
            val request = call.receive<CompleteScanRequest>()
            val result = scanService.completeScan(scanId, request)
            call.respond(HttpStatusCode.OK, ApiResponse(data = result))
        }
    }

    // Admin scan endpoints
    route("/api/admin/scans") {

        get {
            call.requireAdmin()
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
            val scans = scanService.getAllScans(limit)
            call.respond(HttpStatusCode.OK, ApiResponse(data = scans))
        }

        get("/{id}") {
            call.requireAdmin()
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Scan ID must be an integer")
            val scan = scanService.getScanById(id)
            if (scan != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = scan))
            } else {
                call.respond(
                    HttpStatusCode.NotFound,
                    ApiResponse(data = null as RouteScan?, success = false, message = "Scan not found")
                )
            }
        }
    }
}
