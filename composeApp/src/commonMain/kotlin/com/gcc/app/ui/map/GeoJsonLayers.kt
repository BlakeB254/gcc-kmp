package com.gcc.app.ui.map

import com.gcc.shared.model.Coordinates

/**
 * Builds a GeoJSON FeatureCollection string for community area polygons.
 *
 * Each community area with polygon data becomes a Feature with a Polygon geometry.
 * Areas without polygon data use their bounding box as a 4-vertex polygon.
 */
fun buildCommunityAreasGeoJson(areas: List<CommunityArea> = chicagoCommunityAreas): String {
    val features = areas.joinToString(",\n") { area ->
        val coords = if (area.polygon.isNotEmpty()) {
            area.polygon
        } else {
            // Fallback to bounding box rectangle
            listOf(
                area.bounds.north to area.bounds.west,
                area.bounds.north to area.bounds.east,
                area.bounds.south to area.bounds.east,
                area.bounds.south to area.bounds.west,
            )
        }
        // GeoJSON uses [lng, lat] order; close the ring
        val ring = coords.joinToString(",") { (lat, lng) -> "[$lng,$lat]" }
        val closed = if (coords.first() != coords.last()) "$ring,[${coords.first().second},${coords.first().first}]" else ring
        """{"type":"Feature","properties":{"name":"${area.displayName.replace("\n", " ")}","number":${area.number},"isServiceArea":${area.isServiceArea}},"geometry":{"type":"Polygon","coordinates":[[$closed]]}}"""
    }
    return """{"type":"FeatureCollection","features":[$features]}"""
}

/**
 * Builds a GeoJSON FeatureCollection of LineString features for route polylines.
 */
fun buildRoutesGeoJson(routeCoordinates: List<List<Coordinates>>): String {
    if (routeCoordinates.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""
    val features = routeCoordinates.mapIndexed { index, coords ->
        val lineCoords = coords.joinToString(",") { "[${it.lng},${it.lat}]" }
        """{"type":"Feature","properties":{"routeIndex":$index},"geometry":{"type":"LineString","coordinates":[$lineCoords]}}"""
    }.joinToString(",")
    return """{"type":"FeatureCollection","features":[$features]}"""
}

/**
 * Builds a GeoJSON FeatureCollection of Point features for route checkpoint markers.
 */
fun buildCheckpointMarkersGeoJson(routeCoordinates: List<List<Coordinates>>): String {
    if (routeCoordinates.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""
    val features = routeCoordinates.flatMapIndexed { routeIndex, coords ->
        coords.mapIndexed { pointIndex, coord ->
            """{"type":"Feature","properties":{"routeIndex":$routeIndex,"pointIndex":$pointIndex},"geometry":{"type":"Point","coordinates":[${coord.lng},${coord.lat}]}}"""
        }
    }.joinToString(",")
    return """{"type":"FeatureCollection","features":[$features]}"""
}

/**
 * Builds a GeoJSON FeatureCollection of Point features for bounty locations.
 */
fun buildBountyMarkersGeoJson(locations: List<Coordinates>): String {
    if (locations.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""
    val features = locations.mapIndexed { index, coord ->
        """{"type":"Feature","properties":{"bountyIndex":$index},"geometry":{"type":"Point","coordinates":[${coord.lng},${coord.lat}]}}"""
    }.joinToString(",")
    return """{"type":"FeatureCollection","features":[$features]}"""
}

/**
 * Builds a GeoJSON FeatureCollection with a single Polygon feature for the
 * simplified Illinois state boundary from [illinoisOutline].
 */
fun buildStateBoundaryGeoJson(): String {
    val ring = illinoisOutline.joinToString(",") { (lat, lng) -> "[$lng,$lat]" }
    // Close the ring
    val first = illinoisOutline.first()
    val closed = "$ring,[${first.second},${first.first}]"
    return """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"name":"Illinois"},"geometry":{"type":"Polygon","coordinates":[[$closed]]}}]}"""
}

/**
 * Builds a GeoJSON FeatureCollection with a single Polygon feature for the
 * simplified Chicago city boundary from [chicagoCityOutline].
 */
fun buildCityBoundaryGeoJson(): String {
    val ring = chicagoCityOutline.joinToString(",") { (lat, lng) -> "[$lng,$lat]" }
    // Close the ring
    val first = chicagoCityOutline.first()
    val closed = "$ring,[${first.second},${first.first}]"
    return """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"name":"Chicago"},"geometry":{"type":"Polygon","coordinates":[[$closed]]}}]}"""
}

/**
 * Builds a GeoJSON FeatureCollection containing only community areas where
 * [CommunityArea.isServiceArea] is true. Used for the green service-area fill layer.
 */
fun buildServiceAreaGeoJson(): String {
    return buildCommunityAreasGeoJson(chicagoCommunityAreas.filter { it.isServiceArea })
}
