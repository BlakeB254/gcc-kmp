package com.gcc.server.routes

import com.gcc.server.services.FeedService
import com.gcc.shared.model.ApiResponse
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.gcc.server.plugins.koinGet

/**
 * Public activity feed endpoint.
 *
 * GET /api/feed?limit=30&offset=0&type=SPOT_REPORT&community_area=North+Lawndale
 *
 * Aggregates recent activity from spot reports, bounties, jobs, volunteer
 * cleanups, and badge unlocks into a single reverse-chronological feed.
 */
fun Routing.feedRoutes() {
    val feedService = koinGet<FeedService>()

    route("/api/feed") {
        get {
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 30
            val offset = call.parameters["offset"]?.toIntOrNull() ?: 0
            val type = call.parameters["type"]
            val communityArea = call.parameters["community_area"]
            val items = feedService.getFeed(limit, offset, type, communityArea)
            call.respond(HttpStatusCode.OK, ApiResponse(data = items))
        }
    }
}
