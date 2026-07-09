package com.gcc.shared.geo

import com.gcc.shared.model.Point
import kotlin.math.*

object GeofenceVerification {
    private const val EARTH_RADIUS_METERS = 6371000.0

    /** Haversine distance between two [lng, lat] points in meters */
    fun distanceMeters(point1: Point, point2: Point): Double {
        val lat1 = point1[1] * PI / 180.0
        val lat2 = point2[1] * PI / 180.0
        val dLat = (point2[1] - point1[1]) * PI / 180.0
        val dLng = (point2[0] - point1[0]) * PI / 180.0
        // Float rounding can push this a hair outside [0, 1] (identical or
        // antipodal-ish points), which sends sqrt(1 - a) to NaN below —
        // clamp at the source so callers never see it.
        val a = (sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLng / 2).pow(2)).coerceIn(0.0, 1.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    /** Haversine distance between two lat/lng coordinate pairs in meters */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLng = (lng2 - lng1) * PI / 180.0
        // See the Point overload above for why this is clamped.
        val a = (
            sin(dLat / 2).pow(2) +
                cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) *
                sin(dLng / 2).pow(2)
            ).coerceIn(0.0, 1.0)
        return EARTH_RADIUS_METERS * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Check if a point is within the geofence radius of another point. */
    fun isWithinGeofence(userLocation: Point, targetLocation: Point, radiusMeters: Double): Boolean {
        return distanceMeters(userLocation, targetLocation) <= radiusMeters
    }
}
