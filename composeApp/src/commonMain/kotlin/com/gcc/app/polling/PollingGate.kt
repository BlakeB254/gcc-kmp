package com.gcc.app.polling

/**
 * Platform-neutral polling cadence for shared iOS/Android map readers.
 *
 * Returns `null` when the app is backgrounded so callers issue no scheduled
 * request. Foreground delays start at [baseIntervalMillis] and grow with
 * consecutive unchanged responses up to [maxIntervalMillis], then reset after
 * a changed response.
 */
enum class AppVisibility {
    Foreground,
    Background,
}

class PollingGate(
    private val baseIntervalMillis: Long,
    private val maxIntervalMillis: Long,
) {
    init {
        require(baseIntervalMillis > 0L) { "baseIntervalMillis must be positive" }
        require(maxIntervalMillis >= baseIntervalMillis) {
            "maxIntervalMillis must be >= baseIntervalMillis"
        }
    }

    /**
     * Next delay before a scheduled poll, or `null` when polling must stop.
     *
     * @param consecutiveUnchanged number of successive polls that returned no
     *   material change (must be >= 0).
     */
    fun nextDelayMillis(visibility: AppVisibility, consecutiveUnchanged: Int): Long? {
        if (visibility != AppVisibility.Foreground) return null
        require(consecutiveUnchanged >= 0) { "consecutiveUnchanged must be non-negative" }
        if (consecutiveUnchanged == 0) return baseIntervalMillis

        // Exponential backoff: base * 2^(n-1), capped at max.
        var delay = baseIntervalMillis
        var steps = consecutiveUnchanged
        while (steps > 0 && delay < maxIntervalMillis) {
            val doubled = delay * 2L
            delay = if (doubled < 0L || doubled > maxIntervalMillis) maxIntervalMillis else doubled
            steps -= 1
        }
        return delay.coerceAtMost(maxIntervalMillis)
    }
}
