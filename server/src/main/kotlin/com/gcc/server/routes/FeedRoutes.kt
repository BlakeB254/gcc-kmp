package com.gcc.server.routes

import com.gcc.server.plugins.koinGet
import com.gcc.server.plugins.workerId
import com.gcc.server.services.FeedService
import com.gcc.shared.model.ApiResponse
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Public activity feed endpoint.
 *
 * GET /api/feed?limit=30&offset=0&type=SPOT_REPORT&community_area=North+Lawndale
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

        // GET /api/feed/{id}/comments — list comments for a feed item
        get("/{id}/comments") {
            val feedItemId = call.parameters["id"] ?: throw IllegalArgumentException("Feed item ID required")
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
            val comments = feedService.getComments(feedItemId, limit)
            call.respond(HttpStatusCode.OK, ApiResponse(data = comments))
        }
    }
}

/**
 * Authenticated feed endpoints — posting comments, captions, profile opt-in.
 * Must be called inside an `authenticate("auth-jwt") { }` block.
 */
fun Route.authenticatedFeedRoutes() {
    val feedService = application.koinGet<FeedService>()

    route("/api/feed") {
        // POST /api/feed/{id}/comments — add a comment
        post("/{id}/comments") {
            val feedItemId = call.parameters["id"] ?: throw IllegalArgumentException("Feed item ID required")
            val body = call.receive<Map<String, String>>()
            val text = body["text"] ?: throw IllegalArgumentException("Comment text required")
            val comment = feedService.addComment(feedItemId, call.workerId(), text)
            call.respond(HttpStatusCode.Created, ApiResponse(data = comment))
        }

        // PATCH /api/feed/{id}/caption — add or update a caption on own event
        patch("/{id}/caption") {
            val feedItemId = call.parameters["id"] ?: throw IllegalArgumentException("Feed item ID required")
            val body = call.receive<Map<String, String>>()
            val caption = body["caption"] ?: throw IllegalArgumentException("Caption text required")
            val updated = feedService.setCaption(feedItemId, call.workerId(), caption)
            call.respond(HttpStatusCode.OK, ApiResponse(data = updated))
        }

        // POST /api/feed/{id}/opt-in-profile — attach profile to a feed event
        post("/{id}/opt-in-profile") {
            val feedItemId = call.parameters["id"] ?: throw IllegalArgumentException("Feed item ID required")
            val updated = feedService.optInProfile(feedItemId, call.workerId())
            call.respond(HttpStatusCode.OK, ApiResponse(data = updated))
        }
    }
}
