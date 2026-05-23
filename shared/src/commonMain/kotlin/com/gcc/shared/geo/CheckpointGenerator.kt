package com.gcc.shared.geo

import com.gcc.shared.model.*

/**
 * Generates 10 checkpoint waypoints from a route's GeoJSON geometry when the
 * server hasn't provided explicit checkpoints. Rectangular block routes get
 * points distributed around every side instead of only start/end points.
 */
object CheckpointGenerator {

    private const val MIN_DISTANCE_BETWEEN_CHECKPOINTS_M = 10.0
    private const val TARGET_CHECKPOINTS = 10

    fun generateIfMissing(route: Route): List<RouteCheckpoint> {
        if (route.checkpoints.size >= TARGET_CHECKPOINTS) return route.checkpoints

        val geometry = route.geometry ?: return emptyList()
        val allCoords = geometry.toCoordinates()
        if (allCoords.size < 2) return emptyList()

        // Deduplicate near-identical consecutive points
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

        // Remove duplicate closing point if loop
        val isLoop = GeofenceVerification.distanceMeters(
            uniquePoints.first().lat, uniquePoints.first().lng,
            uniquePoints.last().lat, uniquePoints.last().lng,
        ) < MIN_DISTANCE_BETWEEN_CHECKPOINTS_M

        val corners = if (isLoop && uniquePoints.size > 2) {
            uniquePoints.dropLast(1)
        } else {
            uniquePoints
        }

        val checkpointCoords = interpolateToTarget(corners, isLoop)

        return checkpointCoords.mapIndexed { index, coord ->
            val type = when {
                index == 0 -> CheckpointType.ROUTE_START
                index == checkpointCoords.size - 1 -> CheckpointType.ROUTE_END
                else -> CheckpointType.PHOTO_POINT
            }

            RouteCheckpoint(
                id = null,
                name = checkpointName(index, checkpointCoords.size, type),
                sequenceOrder = index,
                coordinates = coord,
                photoRequired = true,
                geofenceRadius = 20.0,
                type = type,
            )
        }
    }

    /**
     * Given corner points of a route, add midpoints along each segment
     * to reach approximately TARGET_CHECKPOINTS total.
     */
    private fun interpolateToTarget(corners: List<Coordinates>, isLoop: Boolean): List<Coordinates> {
        val segments = if (isLoop) corners.size else corners.size - 1
        if (segments == 0) return corners

        // Calculate how many midpoints per segment to reach target
        val midpointsNeeded = TARGET_CHECKPOINTS - corners.size
        if (midpointsNeeded <= 0) return corners

        // Measure each segment length to distribute midpoints proportionally
        val segmentLengths = (0 until segments).map { i ->
            val from = corners[i]
            val to = corners[(i + 1) % corners.size]
            GeofenceVerification.distanceMeters(from.lat, from.lng, to.lat, to.lng)
        }
        val totalLength = segmentLengths.sum()
        if (totalLength < 1.0) return corners

        // Allocate midpoints per segment proportional to length
        val midpointsPerSegment = segmentLengths.map { len ->
            val share = (len / totalLength) * midpointsNeeded
            share.toInt().coerceAtLeast(if (len > 30.0) 1 else 0)
        }.toMutableList()

        // Distribute remaining midpoints to longest segments
        var remaining = midpointsNeeded - midpointsPerSegment.sum()
        while (remaining > 0) {
            val longestIdx = segmentLengths.indices.maxByOrNull {
                segmentLengths[it] / (midpointsPerSegment[it] + 1)
            } ?: break
            midpointsPerSegment[longestIdx]++
            remaining--
        }

        // Build the final point list
        val result = mutableListOf<Coordinates>()
        for (i in 0 until segments) {
            val from = corners[i]
            val to = corners[(i + 1) % corners.size]
            val count = midpointsPerSegment[i]

            result.add(from)
            for (m in 1..count) {
                val fraction = m.toDouble() / (count + 1)
                result.add(
                    Coordinates(
                        lat = from.lat + (to.lat - from.lat) * fraction,
                        lng = from.lng + (to.lng - from.lng) * fraction,
                    )
                )
            }
        }

        // For non-loop routes, add the final corner
        if (!isLoop) {
            result.add(corners.last())
        }

        return result
    }

    private fun checkpointName(index: Int, total: Int, type: CheckpointType): String {
        return when (type) {
            CheckpointType.ROUTE_START -> "Start"
            CheckpointType.ROUTE_END -> "Finish"
            else -> "Checkpoint ${index + 1} of $total"
        }
    }
}
