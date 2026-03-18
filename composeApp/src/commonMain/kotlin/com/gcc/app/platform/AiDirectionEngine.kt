package com.gcc.app.platform

import com.gcc.shared.geo.GeofenceVerification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

/**
 * Generates natural language direction prompts guiding the user toward unscanned areas.
 * Compares user's heading with bearing to nearest unscanned cluster.
 * Throttled: one prompt every 5 seconds minimum.
 */
class AiDirectionEngine {
    private val _directionText = MutableStateFlow<String?>(null)
    val directionText: StateFlow<String?> = _directionText.asStateFlow()

    private var lastPromptTimeMs = 0L
    private val cooldownMs = 5000L

    /**
     * Update based on user position, heading, and unscanned cell centers.
     * @param userLat current latitude
     * @param userLng current longitude
     * @param userHeading compass heading in degrees (0=north, 90=east), null if unknown
     * @param unscannedCenters list of [lat, lng] centers of unscanned cell clusters
     */
    fun update(
        userLat: Double,
        userLng: Double,
        userHeading: Float?,
        unscannedCenters: List<Pair<Double, Double>>,
    ) {
        val now = currentTimeMs()
        if (now - lastPromptTimeMs < cooldownMs) return
        if (unscannedCenters.isEmpty()) {
            _directionText.value = null
            return
        }

        // Find nearest unscanned cluster
        val nearest = unscannedCenters.minByOrNull { (lat, lng) ->
            GeofenceVerification.distanceMeters(userLat, userLng, lat, lng)
        } ?: return

        val dist = GeofenceVerification.distanceMeters(userLat, userLng, nearest.first, nearest.second)
        val bearing = bearingTo(userLat, userLng, nearest.first, nearest.second)

        val prompt = if (userHeading != null) {
            // AR tier: directional prompts relative to where user is looking
            val relativeBearing = normalizeDegrees(bearing - userHeading)
            generateDirectionalPrompt(relativeBearing, dist)
        } else {
            // GPS tier: positional prompts
            generatePositionalPrompt(bearing, dist)
        }

        _directionText.value = prompt
        lastPromptTimeMs = now
    }

    fun clear() {
        _directionText.value = null
    }

    private fun generateDirectionalPrompt(relativeBearing: Double, distMeters: Double): String {
        val distText = if (distMeters > 50) "${distMeters.toInt()}m ahead" else "nearby"
        return when {
            relativeBearing in -30.0..30.0 -> "Keep going — unscanned area $distText"
            relativeBearing in 30.0..90.0 -> "Pan right — unscanned area $distText"
            relativeBearing in 90.0..150.0 -> "Turn right — unscanned area $distText"
            relativeBearing > 150.0 || relativeBearing < -150.0 -> "Turn around — missed area behind you"
            relativeBearing in -90.0..-30.0 -> "Pan left — unscanned area $distText"
            relativeBearing in -150.0..-90.0 -> "Turn left — unscanned area $distText"
            else -> "Unscanned area $distText"
        }
    }

    private fun generatePositionalPrompt(bearing: Double, distMeters: Double): String {
        val cardinal = when {
            bearing in 337.5..360.0 || bearing in 0.0..22.5 -> "north"
            bearing in 22.5..67.5 -> "northeast"
            bearing in 67.5..112.5 -> "east"
            bearing in 112.5..157.5 -> "southeast"
            bearing in 157.5..202.5 -> "south"
            bearing in 202.5..247.5 -> "southwest"
            bearing in 247.5..292.5 -> "west"
            bearing in 292.5..337.5 -> "northwest"
            else -> "ahead"
        }
        return "Walk ${distMeters.toInt()}m $cardinal to unscanned section"
    }

    companion object {
        /** Bearing from point A to point B in degrees (0-360). */
        fun bearingTo(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val dLng = (lng2 - lng1) * PI / 180.0
            val lat1Rad = lat1 * PI / 180.0
            val lat2Rad = lat2 * PI / 180.0
            val y = sin(dLng) * cos(lat2Rad)
            val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLng)
            val bearing = atan2(y, x) * 180.0 / PI
            return (bearing + 360.0) % 360.0
        }

        fun normalizeDegrees(deg: Double): Double {
            var d = deg % 360.0
            if (d > 180.0) d -= 360.0
            if (d < -180.0) d += 360.0
            return d
        }

        fun currentTimeMs(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
    }
}
