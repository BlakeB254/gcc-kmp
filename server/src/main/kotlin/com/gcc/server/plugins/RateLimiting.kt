package com.gcc.server.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Rate limiting configuration.
 *
 * Three tiers:
 * - "auth" : 10 requests per minute (login/register — brute-force protection)
 * - "api"  : 60 requests per minute (general authenticated endpoints)
 * - "public": 120 requests per minute (leaderboard, feed, stats)
 *
 * Keyed by client IP (X-Forwarded-For when behind Cloudflare, else remoteHost).
 */
fun Application.configureRateLimiting() {
    install(RateLimit) {
        register(RateLimitName("auth")) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.headers["CF-Connecting-IP"]
                    ?: call.request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()
                    ?: call.request.local.remoteHost
            }
        }
        register(RateLimitName("api")) {
            rateLimiter(limit = 60, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.headers["CF-Connecting-IP"]
                    ?: call.request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()
                    ?: call.request.local.remoteHost
            }
        }
        register(RateLimitName("public")) {
            rateLimiter(limit = 120, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.headers["CF-Connecting-IP"]
                    ?: call.request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()
                    ?: call.request.local.remoteHost
            }
        }
    }
}
