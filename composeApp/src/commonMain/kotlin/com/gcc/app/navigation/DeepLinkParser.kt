package com.gcc.app.navigation

/**
 * Parses deep link URIs into navigation [Screen] instances.
 *
 * Supported URI patterns:
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
            segments.size == 1 && segments[0] == "wallet" -> Screen.Wallet
            segments.size == 1 && segments[0] == "leaderboard" -> Screen.Leaderboard
            segments.size == 1 && segments[0] == "feed" -> Screen.Feed
            else -> null
        }
    }
}
