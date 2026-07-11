package com.gcc.app.ui.map

import com.gcc.app.platform.CoverageTracker
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Closed (lat, lng) ring for the persistent heading cone: apex at the user,
 * arc at [radiusMeters] spanning [spreadDegrees] centred on [headingDegrees].
 *
 * Global constraint 5: this is a pure function of the compass heading. It does
 * NOT depend on raycasts, so the cone stays on screen when the AR fan returns
 * nothing. The live [visibilityBeamPolygon] is drawn inside it and IS a
 * function of ray hits — the two must remain visually distinct.
 */
fun headingConePolygon(
    selfLat: Double,
    selfLng: Double,
    headingDegrees: Float,
    radiusMeters: Double = CoverageTracker.SEEN_RANGE_METERS,
    spreadDegrees: Double = 55.0,
    arcSteps: Int = 12,
): List<Pair<Double, Double>> {
    val metersPerDegLat = 111_000.0
    val metersPerDegLng = 111_320.0 * cos(selfLat * PI / 180.0)
    val start = headingDegrees - spreadDegrees / 2.0
    val step = spreadDegrees / arcSteps

    val ring = ArrayList<Pair<Double, Double>>(arcSteps + 3)
    ring.add(selfLat to selfLng)
    for (i in 0..arcSteps) {
        // Bearing: 0 = north (+lat), 90 = east (+lng).
        val bearingRad = (start + step * i) * PI / 180.0
        val dLat = radiusMeters * cos(bearingRad) / metersPerDegLat
        val dLng = radiusMeters * sin(bearingRad) / metersPerDegLng
        ring.add((selfLat + dLat) to (selfLng + dLng))
    }
    ring.add(selfLat to selfLng)
    return ring
}

/**
 * The same cone as a GeoJSON FeatureCollection for MapLibre FillLayers.
 * Delegates to [headingConePolygon] so the main map's layer and the minimap's
 * Canvas can never drift apart.
 *
 * Note the historical default radius of 22m is preserved for the main map's
 * user-location cone, which is a UI affordance, not the 55m scan range.
 */
fun headingConeGeoJson(
    lat: Double,
    lng: Double,
    headingDegrees: Float,
    radiusMeters: Double = 22.0,
    spreadDegrees: Double = 55.0,
    arcSteps: Int = 10,
): String {
    val ring = headingConePolygon(lat, lng, headingDegrees, radiusMeters, spreadDegrees, arcSteps)
        .joinToString(",") { (la, ln) -> "[$ln,$la]" }
    return """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[$ring]]},"properties":{}}]}"""
}

/**
 * Shortest signed angular delta from [previous] to [next], both mod-360
 * compass headings (0 = north). Wraps into (-180, 180] so a heading that
 * crosses the 0/360 seam (350 -> 10) yields +20, not the naive -340 a plain
 * subtraction would give.
 *
 * ADDENDUM (Blake, binding): callers accumulate this delta onto a running,
 * NOT-mod-360 cumulative angle (`cumulative += unwrapHeading(cumulative,
 * rawHeading)`) and animate that cumulative value with
 * `animateFloatAsState`/`tween`. Because [previous] only participates through
 * `(next - previous) mod 360`, it is safe to pass an unnormalized cumulative
 * angle (e.g. 370) directly — the shortest-path result is unaffected.
 */
fun unwrapHeading(previous: Float, next: Float): Float {
    var delta = (next - previous) % 360f
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return delta
}
