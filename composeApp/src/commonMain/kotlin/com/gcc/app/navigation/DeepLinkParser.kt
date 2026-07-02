package com.gcc.app.navigation

/**
 * Parses deep link URIs into navigation [Screen] instances.
 *
 * Supported URI patterns:
 * - `/dashboard` → Dashboard (where route offers surface)
 * - `/routes/{id}` → RouteDetail
 * - `/bounties/{id}` → BountyDetail
 * - `/jobs/{jobId}` → ActiveJob
 * - `/jobs/{jobId}/{routeId}` → ActiveJob with explicit routeId
 * - `/wallet` → Wallet
 * - `/leaderboard` → Leaderboard
 * - `/feed` → Feed
 *
 * Works with both:
 * - Full URLs: `https://globalcommunitycleaners.org/routes/42`
 * - Path-only: `/routes/42`
 */
object DeepLinkParser {

    /**
     * If the URI is a Google OAuth callback for the mobile app, return the
     * exchange token from the `token` query param. Returns null if this is
     * not an auth callback URL.
     *
     * Patterns recognized:
     * - https://globalcommunitycleaners.org/auth/mobile-callback?token=XXX
     * - https://www.globalcommunitycleaners.org/auth/mobile-callback?token=XXX
     * - /auth/mobile-callback?token=XXX
     * - gcc://auth/callback?token=XXX
     */
    fun extractAuthCallbackToken(uri: String): String? {
        // Path detection — strip scheme+host if present
        val pathWithQuery = if (uri.contains("://")) {
            val afterScheme = uri.substringAfter("://")
            val pathStart = afterScheme.indexOf('/')
            if (pathStart < 0) return null
            afterScheme.substring(pathStart)
        } else uri

        val (path, query) = if (pathWithQuery.contains("?")) {
            pathWithQuery.substringBefore("?") to pathWithQuery.substringAfter("?")
        } else {
            pathWithQuery to ""
        }

        val cleanPath = path.trimEnd('/').lowercase()
        val isAuthCallback = cleanPath == "/auth/mobile-callback" ||
            cleanPath == "/auth/callback" ||
            cleanPath.endsWith("/auth/mobile-callback") ||
            cleanPath.endsWith("/auth/callback")
        if (!isAuthCallback) return null

        // Pull `token` query param
        return query.split("&")
            .firstNotNullOfOrNull { kv ->
                val (k, v) = kv.split("=", limit = 2).let {
                    if (it.size == 2) it[0] to it[1] else return@firstNotNullOfOrNull null
                }
                if (k == "token" && v.isNotBlank()) v else null
            }
    }

    fun parse(uri: String): Screen? {
        // Extract path from full URL or use as-is if already a path
        val path = if (uri.contains("://")) {
            try {
                // Remove scheme and host, keep path
                val afterScheme = uri.substringAfter("://")
                val pathStart = afterScheme.indexOf('/')
                if (pathStart >= 0) afterScheme.substring(pathStart) else "/"
            } catch (_: Exception) {
                return null
            }
        } else {
            uri
        }

        // Remove trailing slash and query parameters
        val cleanPath = path.split("?").first().trimEnd('/')

        // Match against known patterns
        val segments = cleanPath.split("/").filter { it.isNotEmpty() }
        return when {
            segments.size == 2 && segments[0] == "routes" -> {
                segments[1].toIntOrNull()?.let { Screen.RouteDetail(it) }
            }
            segments.size == 2 && segments[0] == "bounties" -> {
                segments[1].toIntOrNull()?.let { Screen.BountyDetail(it) }
            }
            segments.size >= 2 && segments[0] == "jobs" -> {
                val jobId = segments[1].toIntOrNull() ?: return null
                val routeId = segments.getOrNull(2)?.toIntOrNull() ?: 0
                Screen.ActiveJob(jobId = jobId, routeId = routeId)
            }
            segments.size == 1 && segments[0] == "dashboard" -> Screen.Dashboard
            segments.size == 1 && segments[0] == "wallet" -> Screen.Wallet
            segments.size == 1 && segments[0] == "leaderboard" -> Screen.Leaderboard
            segments.size == 1 && segments[0] == "feed" -> Screen.Feed
            else -> null
        }
    }
}
