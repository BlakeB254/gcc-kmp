package com.gcc.app.ui.map

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Builds a GeoJSON FeatureCollection containing a sector ("flashlight cone")
 * polygon anchored at the user's location and opening toward [headingDegrees],
 * used by the map's user-location indicator to show facing direction.
 *
 * The cone is sized in real meters so it reads naturally at the close zooms
 * where scan/route screens run their maps, and shrinks away at wide zooms
 * like the platform map apps' heading indicator.
 *
 * @param headingDegrees compass heading, 0 = north, 90 = east
 * @param radiusMeters   how far the cone reaches
 * @param spreadDegrees  total angular width of the cone
 */
fun headingConeGeoJson(
    lat: Double,
    lng: Double,
    headingDegrees: Float,
    radiusMeters: Double = 22.0,
    spreadDegrees: Double = 55.0,
    arcSteps: Int = 10,
): String {
    val metersPerDegLat = 111_000.0
    val metersPerDegLng = 111_320.0 * cos(lat * PI / 180.0)

    val start = headingDegrees - spreadDegrees / 2.0
    val step = spreadDegrees / arcSteps

    val ring = buildString {
        append("[$lng,$lat]")
        for (i in 0..arcSteps) {
            val bearingRad = (start + step * i) * PI / 180.0
            // Bearing: 0 = north (+lat), 90 = east (+lng)
            val dLat = radiusMeters * cos(bearingRad) / metersPerDegLat
            val dLng = radiusMeters * sin(bearingRad) / metersPerDegLng
            append(",[${lng + dLng},${lat + dLat}]")
        }
        append(",[$lng,$lat]")
    }

    return """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[$ring]]},"properties":{}}]}"""
}
