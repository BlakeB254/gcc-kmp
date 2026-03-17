package com.gcc.app.ui.map

import com.gcc.shared.model.Coordinates
import kotlin.math.*

/**
 * Buffer a polyline into a polygon corridor.
 * Returns a closed polygon around the line at the given buffer distance.
 *
 * The result is a list of coordinates forming the left side of the buffer
 * followed by the right side reversed, creating a closed corridor shape.
 */
fun bufferLineToPolygon(line: List<Coordinates>, bufferMeters: Double = 30.0): List<Coordinates> {
    if (line.size < 2) return emptyList()

    val leftSide = mutableListOf<Coordinates>()
    val rightSide = mutableListOf<Coordinates>()

    for (i in 0 until line.size - 1) {
        val p1 = line[i]
        val p2 = line[i + 1]

        val bearing = bearingBetween(p1, p2)
        val leftBearing = (bearing - 90.0 + 360.0) % 360.0
        val rightBearing = (bearing + 90.0) % 360.0

        leftSide.add(offsetPoint(p1, leftBearing, bufferMeters))
        rightSide.add(offsetPoint(p1, rightBearing, bufferMeters))

        if (i == line.size - 2) {
            leftSide.add(offsetPoint(p2, leftBearing, bufferMeters))
            rightSide.add(offsetPoint(p2, rightBearing, bufferMeters))
        }
    }

    return leftSide + rightSide.reversed()
}

/**
 * Compute the initial bearing (forward azimuth) from [from] to [to] in degrees.
 */
private fun bearingBetween(from: Coordinates, to: Coordinates): Double {
    val lat1 = from.lat * PI / 180.0
    val lat2 = to.lat * PI / 180.0
    val dLng = (to.lng - from.lng) * PI / 180.0
    val y = sin(dLng) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
    return (atan2(y, x) * 180.0 / PI + 360.0) % 360.0
}

/**
 * Offset a point by [distanceMeters] along [bearingDeg] on the Earth's surface.
 * Uses the Vincenty direct formula (spherical approximation).
 */
private fun offsetPoint(origin: Coordinates, bearingDeg: Double, distanceMeters: Double): Coordinates {
    val R = 6371000.0 // Earth radius in meters
    val bearing = bearingDeg * PI / 180.0
    val lat1 = origin.lat * PI / 180.0
    val lng1 = origin.lng * PI / 180.0
    val lat2 = asin(
        sin(lat1) * cos(distanceMeters / R) +
            cos(lat1) * sin(distanceMeters / R) * cos(bearing)
    )
    val lng2 = lng1 + atan2(
        sin(bearing) * sin(distanceMeters / R) * cos(lat1),
        cos(distanceMeters / R) - sin(lat1) * sin(lat2)
    )
    return Coordinates(lng = lng2 * 180.0 / PI, lat = lat2 * 180.0 / PI)
}
