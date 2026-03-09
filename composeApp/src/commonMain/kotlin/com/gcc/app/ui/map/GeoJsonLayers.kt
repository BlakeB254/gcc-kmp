package com.gcc.app.ui.map

import com.gcc.shared.model.CommunityReport
import com.gcc.shared.model.CommunityReportStatus
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
 *
 * Routes are closed loops — the first coordinate is appended to the end if
 * the path isn't already closed, forming a complete circuit.
 */
fun buildRoutesGeoJson(routeCoordinates: List<List<Coordinates>>): String {
    if (routeCoordinates.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""
    val features = routeCoordinates.mapIndexed { index, coords ->
        val closed = closeLoop(coords)
        val lineCoords = closed.joinToString(",") { "[${it.lng},${it.lat}]" }
        """{"type":"Feature","properties":{"routeIndex":$index},"geometry":{"type":"LineString","coordinates":[$lineCoords]}}"""
    }.joinToString(",")
    return """{"type":"FeatureCollection","features":[$features]}"""
}

/**
 * Builds a GeoJSON FeatureCollection of Point features for route checkpoint markers.
 *
 * Each point carries a "role" property: "start", "end", or "waypoint" so that
 * MapLibre layers can filter and style them differently.
 */
fun buildCheckpointMarkersGeoJson(routeCoordinates: List<List<Coordinates>>): String {
    if (routeCoordinates.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""
    val features = routeCoordinates.flatMapIndexed { routeIndex, coords ->
        coords.mapIndexed { pointIndex, coord ->
            val role = when (pointIndex) {
                0 -> "start"
                coords.lastIndex -> "end"
                else -> "waypoint"
            }
            """{"type":"Feature","properties":{"routeIndex":$routeIndex,"pointIndex":$pointIndex,"role":"$role"},"geometry":{"type":"Point","coordinates":[${coord.lng},${coord.lat}]}}"""
        }
    }.joinToString(",")
    return """{"type":"FeatureCollection","features":[$features]}"""
}

/**
 * Closes a coordinate list into a loop by appending the first point if needed.
 */
private fun closeLoop(coords: List<Coordinates>): List<Coordinates> {
    if (coords.size < 2) return coords
    val first = coords.first()
    val last = coords.last()
    return if (first.lat == last.lat && first.lng == last.lng) coords
    else coords + first
}

/**
 * Builds a GeoJSON FeatureCollection of Polygon features for route area fills.
 *
 * Uses the same closed-loop coordinates as [buildRoutesGeoJson] but wraps them
 * in Polygon geometry so MapLibre can render a semi-transparent fill at overview
 * zoom levels where thin LineStrings become invisible.
 */
fun buildRoutePolygonsGeoJson(routeCoordinates: List<List<Coordinates>>): String {
    if (routeCoordinates.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""
    val features = routeCoordinates.mapIndexed { index, coords ->
        val closed = closeLoop(coords)
        val ring = closed.joinToString(",") { "[${it.lng},${it.lat}]" }
        """{"type":"Feature","properties":{"routeIndex":$index},"geometry":{"type":"Polygon","coordinates":[[$ring]]}}"""
    }.joinToString(",")
    return """{"type":"FeatureCollection","features":[$features]}"""
}

/**
 * Builds a GeoJSON FeatureCollection of Point features for bounty locations.
 * Only includes public/open bounties (secret bounties are never shown on map).
 */
fun buildBountyMarkersGeoJson(bounties: List<com.gcc.shared.model.Bounty>): String {
    if (bounties.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""
    val features = bounties
        .filter { it.status == com.gcc.shared.model.BountyStatus.OPEN }
        .map { b ->
            val dollars = b.amount / 100
            val cents = b.amount % 100
            val amountStr = if (cents == 0) "\$${dollars}" else "\$${dollars}.${cents.toString().padStart(2, '0')}"
            """{"type":"Feature","properties":{"id":${b.id},"amount":${b.amount},"amountLabel":"$amountStr","communityArea":"${b.communityArea ?: ""}","radiusMeters":${b.radiusMeters}},"geometry":{"type":"Point","coordinates":[${b.locationLng},${b.locationLat}]}}"""
        }.joinToString(",")
    return """{"type":"FeatureCollection","features":[$features]}"""
}

/**
 * Builds a GeoJSON FeatureCollection of Point features for spot report markers.
 * Only includes pending/verified spots (not resolved ones).
 */
fun buildSpotMarkersGeoJson(spots: List<CommunityReport>): String {
    if (spots.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""
    val features = spots
        .filter { it.status != CommunityReportStatus.RESOLVED }
        .map { s ->
            """{"type":"Feature","properties":{"id":${s.id},"type":"${s.type}","points":${s.observerRewardPoints},"hasBounty":${s.bountyId != null},"radiusMeters":${s.affectedRadiusMeters}},"geometry":{"type":"Point","coordinates":[${s.coordinates.lng},${s.coordinates.lat}]}}"""
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

/**
 * GeoJSON FeatureCollection of community area centroids for label placement.
 * Each point has properties: name, number, isServiceArea.
 */
fun buildCommunityAreaLabelsGeoJson(): String {
    val features = chicagoCommunityAreas.map { area ->
        val centroidLat = (area.bounds.north + area.bounds.south) / 2.0
        val centroidLng = (area.bounds.east + area.bounds.west) / 2.0
        val name = area.displayName.replace("\n", " ")
        """{"type":"Feature","properties":{"name":"$name","number":${area.number},"isServiceArea":${area.isServiceArea}},"geometry":{"type":"Point","coordinates":[$centroidLng,$centroidLat]}}"""
    }.joinToString(",")
    return """{"type":"FeatureCollection","features":[$features]}"""
}

/**
 * GeoJSON FeatureCollection of community area centroids enriched with bounty aggregates.
 * Each point has properties: name, number, isServiceArea, bountyCount, bountyTotal, subtitle.
 * The subtitle is a pre-formatted string like "3 bounties · $150" for display on map labels.
 */
fun buildExplorerLabelsGeoJson(bounties: List<com.gcc.shared.model.Bounty>): String {
    // Aggregate open bounties by community area name
    val areaAggregates = bounties
        .filter { it.status == com.gcc.shared.model.BountyStatus.OPEN }
        .groupBy { it.communityArea ?: "" }

    val features = chicagoCommunityAreas.map { area ->
        val centroidLat = (area.bounds.north + area.bounds.south) / 2.0
        val centroidLng = (area.bounds.east + area.bounds.west) / 2.0
        val name = area.displayName.replace("\n", " ")

        // Match on display name (space-separated) since bounty.communityArea uses that format
        val areaBounties = areaAggregates[name] ?: emptyList()
        val bountyCount = areaBounties.size
        val bountyTotalCents = areaBounties.sumOf { it.amount }
        val bountyDollars = bountyTotalCents / 100

        val subtitle = if (bountyCount > 0) {
            "$bountyCount ${if (bountyCount == 1) "bounty" else "bounties"} · $$bountyDollars"
        } else {
            ""
        }

        // explorerLabel combines name + subtitle on two lines for a single SymbolLayer
        val explorerLabel = if (subtitle.isNotEmpty()) "$name\\n$subtitle" else name

        """{"type":"Feature","properties":{"name":"$name","number":${area.number},"isServiceArea":${area.isServiceArea},"bountyCount":$bountyCount,"bountyTotal":$bountyDollars,"subtitle":"$subtitle","explorerLabel":"$explorerLabel"},"geometry":{"type":"Point","coordinates":[$centroidLng,$centroidLat]}}"""
    }.joinToString(",")
    return """{"type":"FeatureCollection","features":[$features]}"""
}
