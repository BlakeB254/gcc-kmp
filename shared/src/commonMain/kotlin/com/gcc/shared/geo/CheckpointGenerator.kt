package com.gcc.shared.geo

import com.gcc.shared.model.*

/**
 * Generates checkpoint waypoints from a route's GeoJSON geometry when the
 * server hasn't provided explicit checkpoints. Uses the geometry vertices
 * (corner points of the block route) as natural checkpoint locations.
 */
object CheckpointGenerator {

    private const val MIN_DISTANCE_BETWEEN_CHECKPOINTS_M = 10.0

    /**
     * Generate checkpoints from a route's geometry coordinates.
     * Returns the original checkpoints if they already exist, or
     * synthesized checkpoints from geometry vertices.
     */
    fun generateIfMissing(route: Route): List<RouteCheckpoint> {
        if (route.checkpoints.isNotEmpty()) return route.checkpoints

        val geometry = route.geometry ?: return emptyList()
        val allCoords = geometry.toCoordinates()
        if (allCoords.size < 2) return emptyList()

        val uniquePoints = mutableListOf<Coordinates>()
        for (coord in allCoords) {
            val lastPoint = uniquePoints.lastOrNull()
            if (lastPoint == null ||
                GeofenceVerification.distanceMeters(
                    lastPoint.lat, lastPoint.lng,
                    coord.lat, coord.lng,
                ) >= MIN_DISTANCE_BETWEEN_CHECKPOINTS_M
            ) {
                uniquePoints.add(coord)
            }
        }

        if (uniquePoints.size < 2) return emptyList()

        // Check if it's a loop (first ≈ last point)
        val isLoop = GeofenceVerification.distanceMeters(
            uniquePoints.first().lat, uniquePoints.first().lng,
            uniquePoints.last().lat, uniquePoints.last().lng,
        ) < MIN_DISTANCE_BETWEEN_CHECKPOINTS_M

        // If it's a loop, remove the duplicate closing point
        val points = if (isLoop && uniquePoints.size > 2) {
            uniquePoints.dropLast(1)
        } else {
            uniquePoints
        }

        return points.mapIndexed { index, coord ->
            val type = when {
                index == 0 -> CheckpointType.ROUTE_START
                index == points.size - 1 && !isLoop -> CheckpointType.ROUTE_END
                else -> CheckpointType.PHOTO_POINT
            }

            RouteCheckpoint(
                id = null,
                name = checkpointName(index, type),
                sequenceOrder = index,
                coordinates = coord,
                photoRequired = true,
                geofenceRadius = 20.0, // slightly wider for generated checkpoints
                type = type,
            )
        }
    }

    private fun checkpointName(index: Int, type: CheckpointType): String {
        return when (type) {
            CheckpointType.ROUTE_START -> "Start"
            CheckpointType.ROUTE_END -> "Finish"
            else -> "Checkpoint ${index + 1}"
        }
    }
}
