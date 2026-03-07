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
        val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    /** Haversine distance between two lat/lng coordinate pairs in meters */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLng = (lng2 - lng1) * PI / 180.0
        val a = sin(dLat / 2).pow(2) +
            cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) *
            sin(dLng / 2).pow(2)
        return EARTH_RADIUS_METERS * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Check if a point is within the geofence radius of a checkpoint */
    fun isWithinGeofence(userLocation: Point, checkpointLocation: Point, radiusMeters: Double): Boolean {
        return distanceMeters(userLocation, checkpointLocation) <= radiusMeters
    }
}
