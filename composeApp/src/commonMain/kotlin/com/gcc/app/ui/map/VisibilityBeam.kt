package com.gcc.app.ui.map

import com.gcc.app.platform.CoverageTracker
import com.gcc.app.platform.METERS_PER_DEG_LAT
import com.gcc.app.platform.RaycastCoverageHit
import com.gcc.app.platform.lngScaleFor
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Scene-aware "flashlight" beam (spec WS2): from the user dot, a polygon whose
 * reach in each heading bin is the FARTHEST ray landing in that bin this tick,
 * clamped to [clampMeters]. Because the raycast fan's rays stop on real
 * surfaces, the beam is long across open street (far hits), short when aimed
 * at near ground (near hits), and truncated at walls (rays stop). The AR
 * heading is implicit: the fan only casts in front of the camera, so the
 * populated bins already sit around the facing direction.
 *
 * Pure + deterministic (unit-tested). Returns a CLOSED lat/lng ring
 * [self, ...arc sorted by bearing..., self] in the SAME (lat, lng) pair
 * convention [ScanMinimapOverlay]'s toOffset projects. Empty when no hits.
 */
fun visibilityBeamPolygon(
    selfLat: Double,
    selfLng: Double,
    hits: List<RaycastCoverageHit>,
    binCount: Int = 16,
    clampMeters: Double = CoverageTracker.SEEN_RANGE_METERS,
): List<Pair<Double, Double>> {
    if (hits.isEmpty()) return emptyList()
    val lngScale = lngScaleFor(selfLat)
    // bin -> (reachMeters, bearingRadians). Bearing 0 = north, clockwise.
    val reachByBin = HashMap<Int, Pair<Double, Double>>()
    for (h in hits) {
        val north = (h.lat - selfLat) * METERS_PER_DEG_LAT
        val east = (h.lng - selfLng) * METERS_PER_DEG_LAT * lngScale
        val dist = sqrt(north * north + east * east)
        if (dist <= 0.0) continue
        val reach = if (dist > clampMeters) clampMeters else dist
        var bearing = atan2(east, north)
        if (bearing < 0.0) bearing += 2.0 * PI
        val bin = ((bearing / (2.0 * PI)) * binCount).toInt().coerceIn(0, binCount - 1)
        val prev = reachByBin[bin]
        if (prev == null || reach > prev.first) reachByBin[bin] = reach to bearing
    }
    if (reachByBin.isEmpty()) return emptyList()

    val latPerMeter = 1.0 / METERS_PER_DEG_LAT
    val lngPerMeter = 1.0 / (METERS_PER_DEG_LAT * lngScale)
    val arc = reachByBin.entries
        .sortedBy { it.key }
        .map { entry ->
            val (reach, bearing) = entry.value
            val dLat = reach * cos(bearing) * latPerMeter
            val dLng = reach * sin(bearing) * lngPerMeter
            (selfLat + dLat) to (selfLng + dLng)
        }

    val ring = ArrayList<Pair<Double, Double>>(arc.size + 2)
    ring.add(selfLat to selfLng)
    ring.addAll(arc)
    ring.add(selfLat to selfLng)
    return ring
}
