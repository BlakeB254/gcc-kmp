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
        routeQueueCronRoutes()

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
            verificationRoutes()
            cleanlinessRoutes()
        }

        // Authenticated endpoints (auth first — reject bad tokens before consuming rate limit)
        authenticate("auth-jwt") {
            rateLimit(RateLimitName("api")) {
                authenticatedWorkerRoutes()
                authenticatedJobRoutes()
                authenticatedRouteQueueRoutes()
                authenticatedSpotRoutes()
                authenticatedVerificationRoutes()
                authenticatedPointsRoutes()
                authenticatedWalletRoutes()
                authenticatedBountyRoutes()
                authenticatedGamificationRoutes()
                authenticatedFeedRoutes()
                scanRoutes()

                // Admin routes (require admin role)
                adminWorkerRoutes()
                adminSponsorRoutes()
                adminJobRoutes()
                adminRouteRoutes()
                adminRouteReleaseRoutes()
                adminBlockRoutes()
                adminBountyRoutes()
                adminServiceAreaRoutes()
                adminDisputeRoutes()
                adminAnalyticsRoutes()
            }
        }
    }
}
