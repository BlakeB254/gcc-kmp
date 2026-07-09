package com.gcc.shared.geo

import com.gcc.shared.model.Block
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.GeoJSONMultiPolygon
import com.gcc.shared.model.GeoJSONPolygon
import com.gcc.shared.model.Route

fun GeoJSONPolygon.outerRingCoordinates(): List<Coordinates> =
    coordinates.firstOrNull()
        .orEmpty()
        .mapNotNull { point ->
            val lng = point.getOrNull(0)
            val lat = point.getOrNull(1)
            if (lat != null && lng != null) Coordinates(lng = lng, lat = lat) else null
        }
        .withoutClosingDuplicate()

fun GeoJSONMultiPolygon?.innerRingCoordinates(): List<List<Coordinates>> =
    this?.coordinates.orEmpty().mapNotNull { polygon ->
        polygon.firstOrNull()
            ?.mapNotNull { point ->
                val lng = point.getOrNull(0)
                val lat = point.getOrNull(1)
                if (lat != null && lng != null) Coordinates(lng = lng, lat = lat) else null
            }
            ?.withoutClosingDuplicate()
            ?.takeIf { it.size >= 3 }
    }

fun Route.routePathCoordinates(): List<Coordinates> =
    blockBoundary
        ?.outerRingCoordinates()
        ?.takeIf { it.size >= 3 }
        ?: geometry
            ?.toCoordinates()
            .orEmpty()
            .withoutClosingDuplicate()

fun Route.routeStartCoordinates(): Coordinates? =
    routePathCoordinates().firstOrNull()

fun Route.routeCenterCoordinates(): Coordinates? {
    val points = routePathCoordinates()
    if (points.isEmpty()) return null
    return Coordinates(
        lng = points.map { it.lng }.average(),
        lat = points.map { it.lat }.average(),
    )
}

fun Route.hasScannableGeometry(): Boolean {
    val boundaryPoints = blockBoundary?.outerRingCoordinates().orEmpty()
    if (boundaryPoints.size >= 3) return true
    return geometry?.toCoordinates().orEmpty().withoutClosingDuplicate().size >= 2
}

fun Block.blockPathCoordinates(): List<Coordinates> =
    boundary?.outerRingCoordinates().orEmpty()

fun List<Coordinates>.withoutClosingDuplicate(): List<Coordinates> {
    if (size < 2) return this
    val first = first()
    val last = last()
    val closesLoop = GeofenceVerification.distanceMeters(
        first.lat,
        first.lng,
        last.lat,
        last.lng,
    ) < 2.0
    return if (closesLoop) dropLast(1) else this
}
