package com.gcc.server.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import kotlin.time.Duration.Companion.minutes

/**
 * Rate limiting configuration.
 *
 * Three tiers:
 * - "auth" : 10 requests per minute (login/register — brute-force protection)
 * - "api"  : 60 requests per minute (general authenticated endpoints)
 * - "public": 120 requests per minute (leaderboard, feed, stats)
 *
 * Keyed by client IP (CF-Connecting-IP when behind Cloudflare, else X-Forwarded-For, else remoteHost).
 */
fun Application.configureRateLimiting() {
    install(RateLimit) {
        register(RateLimitName("auth")) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
            requestKey { call -> call.clientIp() }
        }
        register(RateLimitName("api")) {
            rateLimiter(limit = 60, refillPeriod = 1.minutes)
            requestKey { call -> call.clientIp() }
        }
        register(RateLimitName("public")) {
            rateLimiter(limit = 120, refillPeriod = 1.minutes)
            requestKey { call -> call.clientIp() }
        }
    }
}

private fun ApplicationCall.clientIp(): String =
    request.headers["CF-Connecting-IP"]
        ?: request.headers["X-Forwarded-For"]?.substringBefore(",")?.trim()
        ?: request.local.remoteHost
