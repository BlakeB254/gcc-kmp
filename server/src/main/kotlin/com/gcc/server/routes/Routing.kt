package com.gcc.server.routes

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.routing.*

/**
 * Central route registration — every endpoint group is registered here.
 *
 * Public routes are registered directly on [Routing].
 * Authenticated routes (mutations, /me) are wrapped in `authenticate("auth-jwt")`.
 */
fun Application.configureRouting() {
    routing {
        // Infrastructure
        healthRoutes()

        // Auth (public — login/register)
        authRoutes()

        // Public read-only (higher rate limit)
        rateLimit(RateLimitName("public")) {
            statsRoutes()
            leaderboardRoutes()
            workerRoutes()
            routeRoutes()
            blockRoutes()
            serviceAreaRoutes()
            jobRoutes()
            spotRoutes()
            bountyRoutes()
            gamificationRoutes()
            feedRoutes()
        }

        // Authenticated endpoints (require valid JWT)
        rateLimit(RateLimitName("api")) {
        authenticate("auth-jwt") {
            authenticatedWorkerRoutes()
            authenticatedJobRoutes()
            authenticatedSpotRoutes()
            authenticatedWalletRoutes()
            authenticatedBountyRoutes()
            authenticatedGamificationRoutes()

            // Admin routes (require admin role)
            adminWorkerRoutes()
            adminSponsorRoutes()
            adminJobRoutes()
            adminRouteRoutes()
            adminBlockRoutes()
            adminBountyRoutes()
            adminServiceAreaRoutes()
            adminDisputeRoutes()
            adminAnalyticsRoutes()
        }
        }
    }
}
