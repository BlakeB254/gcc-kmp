package com.gcc.app.ui.map

import com.gcc.shared.model.AffectedArea
import com.gcc.shared.model.CommunityReport
import com.gcc.shared.model.CommunityReportStatus
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.WorldObservation
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.sin

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
    val features = routeCoordinates.mapIndexedNotNull { index, coords ->
        if (coords.size < 2) return@mapIndexedNotNull null // LineString needs >= 2 positions
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
    val features = routeCoordinates.mapIndexedNotNull { index, coords ->
        if (coords.size < 3) return@mapIndexedNotNull null // Polygon ring needs >= 4 positions (3 unique + close)
        val closed = closeLoop(coords)
        val ring = closed.joinToString(",") { "[${it.lng},${it.lat}]" }
        """{"type":"Feature","properties":{"routeIndex":$index},"geometry":{"type":"Polygon","coordinates":[[$ring]]}}"""
    }.joinToString(",")
    return """{"type":"FeatureCollection","features":[$features]}"""
}

/**
 * Inner route boundaries (double-lined routes, route-v2 design): each route
 * loop insets toward its centroid by [setbackMeters] — the public-corridor
 * cutoff line that respects private property. Radial inset is an
 * approximation that is visually parallel for the (rectangular) block loops
 * we assign; a server-authored/admin-edited `innerBoundary` will override
 * this once the route-v2 backend lands.
 */
fun buildRouteInnerBoundariesGeoJson(
    routeCoordinates: List<List<Coordinates>>,
    overrideRings: List<List<Coordinates>> = emptyList(),
    setbackMeters: Double = 8.0,
    /** When false, loops with no authored rings get NOTHING instead of the
     *  synthetic 8m-inset corridor — the editor must never render a ghost
     *  ring the admin can't select or edit. */
    fallbackInset: Boolean = true,
): String {
    if (routeCoordinates.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""
    val features = routeCoordinates.mapIndexedNotNull { index, coords ->
        if (coords.size < 3) return@mapIndexedNotNull null
        val rings = innerRingsForLoop(coords, overrideRings, setbackMeters, fallbackInset)
        if (rings.isEmpty()) return@mapIndexedNotNull null
        rings.joinToString(",") { ring ->
            val ringJson = ring.joinToString(",") { "[${it.lng},${it.lat}]" }
            """{"type":"Feature","properties":{"routeIndex":$index,"kind":"inner"},"geometry":{"type":"Polygon","coordinates":[[$ringJson]]}}"""
        }
    }.joinToString(",")
    return """{"type":"FeatureCollection","features":[$features]}"""
}

/**
 * Inner rings for one route loop: server/admin-authored rings whose centroid
 * falls inside the loop (MULTIPLE islands supported — unfenced vacant lots
 * between them stay scannable), falling back to the parallel inset when the
 * route has no authored boundary yet.
 */
internal fun innerRingsForLoop(
    loop: List<Coordinates>,
    overrideRings: List<List<Coordinates>>,
    setbackMeters: Double,
    fallbackInset: Boolean = true,
): List<List<Coordinates>> {
    val loopPairs = loop.map { it.lat to it.lng }
    val matched = overrideRings.filter { ring ->
        if (ring.size < 3) return@filter false
        val cLat = ring.map { it.lat }.average()
        val cLng = ring.map { it.lng }.average()
        com.gcc.shared.geo.pointInPolygon(cLat, cLng, loopPairs)
    }.map { closeLoop(it) }
    if (matched.isNotEmpty()) return matched
    if (!fallbackInset) return emptyList()
    return listOfNotNull(insetRingByEdgeOffset(closeLoop(loop), setbackMeters))
}

/**
 * The required-scan corridor: outer block ring with the inner boundary as a
 * hole. The band between the two lines IS the assignment — inside the inner
 * ring (private property) and outside the outer ring are excluded.
 */
fun buildRouteCorridorGeoJson(
    routeCoordinates: List<List<Coordinates>>,
    overrideRings: List<List<Coordinates>> = emptyList(),
    setbackMeters: Double = 8.0,
): String {
    if (routeCoordinates.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""
    val features = routeCoordinates.mapIndexedNotNull { index, coords ->
        if (coords.size < 3) return@mapIndexedNotNull null
        val outer = closeLoop(coords)
        val holes = innerRingsForLoop(coords, overrideRings, setbackMeters)
        if (holes.isEmpty()) return@mapIndexedNotNull null
        val outerJson = outer.joinToString(",") { "[${it.lng},${it.lat}]" }
        val holesJson = holes.joinToString(",") { hole ->
            "[" + hole.joinToString(",") { "[${it.lng},${it.lat}]" } + "]"
        }
        """{"type":"Feature","properties":{"routeIndex":$index},"geometry":{"type":"Polygon","coordinates":[[$outerJson],$holesJson]}}"""
    }.joinToString(",")
    return """{"type":"FeatureCollection","features":[$features]}"""
}

/**
 * True parallel inset: every edge moves inward by [insetMeters] along its
 * normal and vertices are rebuilt from adjacent offset-edge intersections —
 * a uniformly concentric inner ring on any convex block shape (the radial
 * centroid-shrink it replaces hugged the outer line on elongated blocks).
 * Returns null when the loop is too small/degenerate to inset cleanly.
 */
internal fun insetRingByEdgeOffset(ring: List<Coordinates>, insetMeters: Double): List<Coordinates>? {
    // Drop the closing duplicate + consecutive near-duplicates.
    val open = ring.dropLast(1).let { pts ->
        pts.filterIndexed { i, p ->
            val prev = pts[(i - 1 + pts.size) % pts.size]
            kotlin.math.abs(p.lat - prev.lat) > 1e-9 || kotlin.math.abs(p.lng - prev.lng) > 1e-9
        }
    }
    if (open.size < 3) return null

    val cLat = open.map { it.lat }.average()
    val lngScale = kotlin.math.cos(cLat * kotlin.math.PI / 180.0)
    // Meter-space points relative to the first vertex.
    val pts = open.map { v ->
        Pair(
            (v.lng - open[0].lng) * 111_320.0 * lngScale, // east
            (v.lat - open[0].lat) * 111_320.0,            // north
        )
    }

    // Signed area → winding; interior is left of each edge when CCW.
    var area2 = 0.0
    for (i in pts.indices) {
        val (x1, y1) = pts[i]
        val (x2, y2) = pts[(i + 1) % pts.size]
        area2 += x1 * y2 - x2 * y1
    }
    if (kotlin.math.abs(area2) < 1.0) return null
    val inwardSign = if (area2 > 0) 1.0 else -1.0

    // Offset each edge inward: point on line + direction.
    data class Line(val px: Double, val py: Double, val dx: Double, val dy: Double)
    val lines = pts.indices.map { i ->
        val (x1, y1) = pts[i]
        val (x2, y2) = pts[(i + 1) % pts.size]
        val dx = x2 - x1
        val dy = y2 - y1
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len < 0.5) return null
        // Left normal of (dx,dy) is (-dy,dx); inward for CCW.
        val nx = -dy / len * inwardSign
        val ny = dx / len * inwardSign
        Line(x1 + nx * insetMeters, y1 + ny * insetMeters, dx, dy)
    }

    // New vertex i = intersection of offset edge (i-1) and offset edge i.
    val inner = pts.indices.map { i ->
        val a = lines[(i - 1 + lines.size) % lines.size]
        val b = lines[i]
        val cross = a.dx * b.dy - a.dy * b.dx
        if (kotlin.math.abs(cross) < 1e-9) {
            // Collinear edges: just use b's offset start point.
            Pair(b.px, b.py)
        } else {
            val t = ((b.px - a.px) * b.dy - (b.py - a.py) * b.dx) / cross
            Pair(a.px + a.dx * t, a.py + a.dy * t)
        }
    }

    // Validity: every inner vertex must sit at least [insetMeters] inside
    // EVERY original edge's half-plane. Over-inset loops (e.g. a 10m square
    // with an 8m setback) produce mirrored-but-still-wound rings that pure
    // area/winding checks miss — the half-plane test catches them exactly.
    for ((vx, vy) in inner) {
        for (i in pts.indices) {
            val (x1, y1) = pts[i]
            val (x2, y2) = pts[(i + 1) % pts.size]
            val dx = x2 - x1
            val dy = y2 - y1
            val len = kotlin.math.sqrt(dx * dx + dy * dy)
            val inwardDist = ((vx - x1) * (-dy) + (vy - y1) * dx) / len * inwardSign
            if (inwardDist < insetMeters - 0.05) return null
        }
    }

    val result = inner.map { (east, north) ->
        Coordinates(
            lat = open[0].lat + north / 111_320.0,
            lng = open[0].lng + east / (111_320.0 * lngScale),
        )
    }
    return result + result.first() // close the loop
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
            val areaType = when (s.affectedArea) {
                is AffectedArea.Rectangle -> "rectangle"
                else -> "circle"
            }
            val rectWidth = (s.affectedArea as? AffectedArea.Rectangle)?.widthMeters ?: 0.0
            val rectHeight = (s.affectedArea as? AffectedArea.Rectangle)?.heightMeters ?: 0.0
            val rectRotation = (s.affectedArea as? AffectedArea.Rectangle)?.rotationDeg ?: 0.0
            val tagsStr = s.tags.joinToString(",") { "\"${it.name.lowercase()}\"" }
            val heatWeight = spotHeatWeight(s)
            val densityWeight = spotDensityWeight(s)
            // itemType is emitted only when this pin carries a canonical litter
            // type (typed scan observations). Its presence/absence is the map's
            // switch between per-type glyphs and the plain marker dot.
            val itemTypeProp = s.itemType?.let { """"itemType":"${it.lowercase()}",""" } ?: ""
            """{"type":"Feature","properties":{"id":${s.id},$itemTypeProp"type":"${s.type}","points":${s.observerRewardPoints},"heatWeight":$heatWeight,"densityWeight":$densityWeight,"hasBounty":${s.bountyId != null},"radiusMeters":${s.affectedRadiusMeters},"areaType":"$areaType","rectWidth":$rectWidth,"rectHeight":$rectHeight,"rectRotation":$rectRotation,"needsVerification":${s.needsVerification},"tags":[$tagsStr]},"geometry":{"type":"Point","coordinates":[${s.coordinates.lng},${s.coordinates.lat}]}}"""
        }.joinToString(",")
    return """{"type":"FeatureCollection","features":[$features]}"""
}

fun buildSpotDensityHeatEdgeGeoJson(spots: List<CommunityReport>): String =
    buildSpotDensitySurfaceGeoJson(spots, minDensityScore = 0.08)

fun buildSpotDensityHeatLowGeoJson(spots: List<CommunityReport>): String =
    buildSpotDensitySurfaceGeoJson(spots, minDensityScore = 0.52)

fun buildSpotDensityHeatMediumGeoJson(spots: List<CommunityReport>): String =
    buildSpotDensitySurfaceGeoJson(spots, minDensityScore = 1.35)

fun buildSpotDensityHeatHighGeoJson(spots: List<CommunityReport>): String =
    buildSpotDensitySurfaceGeoJson(spots, minDensityScore = 2.7)

fun buildSpotDensityHeatExtremeGeoJson(spots: List<CommunityReport>): String =
    buildSpotDensitySurfaceGeoJson(spots, minDensityScore = 5.0)

private fun buildSpotDensitySurfaceGeoJson(
    spots: List<CommunityReport>,
    minDensityScore: Double,
): String {
    val activeSpots = spots.filter { it.status != CommunityReportStatus.RESOLVED }
    if (activeSpots.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""

    val features = spotDensityClusters(activeSpots)
        .flatMap { cluster -> spotDensitySurfaceFeatures(cluster, minDensityScore) }
        .joinToString(",")
    return """{"type":"FeatureCollection","features":[$features]}"""
}

private const val SPOT_DENSITY_SURFACE_KERNEL_METERS = 280.0
private const val SPOT_DENSITY_SURFACE_BASE_SPACING_METERS = 44.0
private const val SPOT_DENSITY_SURFACE_CLUSTER_LINK_METERS = SPOT_DENSITY_SURFACE_KERNEL_METERS * 1.75
private const val SPOT_DENSITY_SURFACE_CLUSTER_MAX_SAMPLES = 1600.0

private fun spotDensityClusters(activeSpots: List<CommunityReport>): List<List<CommunityReport>> {
    val visited = BooleanArray(activeSpots.size)
    val clusters = mutableListOf<List<CommunityReport>>()

    activeSpots.indices.forEach { start ->
        if (visited[start]) return@forEach

        val clusterIndices = mutableListOf<Int>()
        val queue = ArrayDeque<Int>()
        visited[start] = true
        queue.add(start)

        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            clusterIndices.add(index)

            activeSpots.indices.forEach { candidate ->
                if (!visited[candidate] &&
                    distanceMeters(activeSpots[index].coordinates, activeSpots[candidate].coordinates) <=
                    SPOT_DENSITY_SURFACE_CLUSTER_LINK_METERS
                ) {
                    visited[candidate] = true
                    queue.add(candidate)
                }
            }
        }

        clusters.add(clusterIndices.map { activeSpots[it] })
    }

    return clusters
}

private fun spotDensitySurfaceFeatures(
    cluster: List<CommunityReport>,
    minDensityScore: Double,
): List<String> {
    val centerLat = cluster.map { it.coordinates.lat }.average()
    val minLat = cluster.minOf { it.coordinates.lat }
    val maxLat = cluster.maxOf { it.coordinates.lat }
    val minLng = cluster.minOf { it.coordinates.lng }
    val maxLng = cluster.maxOf { it.coordinates.lng }
    val metersPerDegreeLat = 111_320.0
    val metersPerDegreeLng = (111_320.0 * cos(centerLat * PI / 180.0)).coerceAtLeast(1.0)
    val paddingLat = SPOT_DENSITY_SURFACE_KERNEL_METERS / metersPerDegreeLat
    val paddingLng = SPOT_DENSITY_SURFACE_KERNEL_METERS / metersPerDegreeLng

    val widthMeters = ((maxLng - minLng).coerceAtLeast(0.0) + paddingLng * 2.0) * metersPerDegreeLng
    val heightMeters = ((maxLat - minLat).coerceAtLeast(0.0) + paddingLat * 2.0) * metersPerDegreeLat
    val estimatedSamples = (widthMeters / SPOT_DENSITY_SURFACE_BASE_SPACING_METERS).coerceAtLeast(1.0) *
        (heightMeters / SPOT_DENSITY_SURFACE_BASE_SPACING_METERS).coerceAtLeast(1.0)
    val spacingMeters = if (estimatedSamples <= SPOT_DENSITY_SURFACE_CLUSTER_MAX_SAMPLES) {
        SPOT_DENSITY_SURFACE_BASE_SPACING_METERS
    } else {
        SPOT_DENSITY_SURFACE_BASE_SPACING_METERS *
            sqrt(estimatedSamples / SPOT_DENSITY_SURFACE_CLUSTER_MAX_SAMPLES)
    }
    val latStep = spacingMeters / metersPerDegreeLat
    val lngStep = spacingMeters / metersPerDegreeLng
    val startLat = minLat - paddingLat
    val endLat = maxLat + paddingLat
    val startLng = minLng - paddingLng
    val endLng = maxLng + paddingLng
    val features = mutableListOf<String>()

    var lat = startLat
    var row = 0
    while (lat <= endLat) {
        val rowOffset = if (row % 2 == 0) 0.0 else lngStep * 0.5
        var lng = startLng + rowOffset
        while (lng <= endLng) {
            val score = surfaceDensityScore(Coordinates(lng = lng, lat = lat), cluster)
            if (score >= minDensityScore) {
                features.add(
                    """{"type":"Feature","properties":{"densityScore":$score},"geometry":{"type":"Point","coordinates":[$lng,$lat]}}"""
                )
            }
            lng += lngStep
        }
        lat += latStep
        row += 1
    }

    return features
}

private fun surfaceDensityScore(point: Coordinates, cluster: List<CommunityReport>): Double {
    return cluster.fold(0.0) { total, spot ->
        val distance = distanceMeters(point, spot.coordinates)
        if (distance > SPOT_DENSITY_SURFACE_KERNEL_METERS) {
            total
        } else {
            val t = 1.0 - distance / SPOT_DENSITY_SURFACE_KERNEL_METERS
            val smoothKernel = t * t * (3.0 - 2.0 * t)
            total + smoothKernel * densityStatusScale(spot)
        }
    }
}

private fun densityStatusScale(spot: CommunityReport): Double = when (spot.status) {
    CommunityReportStatus.VERIFIED -> 1.08
    CommunityReportStatus.ACKNOWLEDGED -> 1.04
    CommunityReportStatus.PENDING -> 1.0
    else -> 0.9
}

private fun distanceMeters(a: Coordinates, b: Coordinates): Double {
    val metersPerDegreeLat = 111_320.0
    val avgLat = (a.lat + b.lat) / 2.0
    val metersPerDegreeLng = (111_320.0 * cos(avgLat * PI / 180.0)).coerceAtLeast(1.0)
    val dx = (a.lng - b.lng) * metersPerDegreeLng
    val dy = (a.lat - b.lat) * metersPerDegreeLat
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

fun buildOverviewHeatPointsGeoJson(
    spots: List<CommunityReport>,
    activityCoords: List<Coordinates>,
): String {
    val spotFeatures = spots
        .filter { it.status != CommunityReportStatus.RESOLVED }
        .map { spot ->
            heatPointFeature(
                lat = spot.coordinates.lat,
                lng = spot.coordinates.lng,
                heatWeight = spotHeatWeight(spot),
            )
        }
    val activityFeatures = activityCoords
        .distinctBy { "${it.lat}:${it.lng}" }
        .map { coord ->
            heatPointFeature(
                lat = coord.lat,
                lng = coord.lng,
                heatWeight = 0.75,
            )
        }
    return """{"type":"FeatureCollection","features":[${(spotFeatures + activityFeatures).joinToString(",")}]}"""
}

private fun heatPointFeature(lat: Double, lng: Double, heatWeight: Double): String {
    return """{"type":"Feature","properties":{"heatWeight":$heatWeight},"geometry":{"type":"Point","coordinates":[$lng,$lat]}}"""
}

fun buildSpotHeatFarOuterZoneGeoJson(
    spots: List<CommunityReport>,
    activityCoords: List<Coordinates> = emptyList(),
): String {
    return buildSpotHeatZoneGeoJson(
        spots = spots,
        activityCoords = activityCoords,
        radiusMultiplier = 180.0,
        minRadiusMeters = 10_000.0,
        maxRadiusMeters = 24_000.0,
    )
}

fun buildSpotHeatFarMidZoneGeoJson(
    spots: List<CommunityReport>,
    activityCoords: List<Coordinates> = emptyList(),
): String {
    return buildSpotHeatZoneGeoJson(
        spots = spots,
        activityCoords = activityCoords,
        radiusMultiplier = 90.0,
        minRadiusMeters = 5_000.0,
        maxRadiusMeters = 12_000.0,
    )
}

fun buildSpotHeatFarCoreZoneGeoJson(
    spots: List<CommunityReport>,
    activityCoords: List<Coordinates> = emptyList(),
): String {
    return buildSpotHeatZoneGeoJson(
        spots = spots,
        activityCoords = activityCoords,
        radiusMultiplier = 42.0,
        minRadiusMeters = 2_400.0,
        maxRadiusMeters = 6_000.0,
    )
}

fun buildSpotHeatOuterZoneGeoJson(
    spots: List<CommunityReport>,
    activityCoords: List<Coordinates> = emptyList(),
): String {
    return buildSpotHeatZoneGeoJson(
        spots = spots,
        activityCoords = activityCoords,
        radiusMultiplier = 4.0,
        minRadiusMeters = 160.0,
        maxRadiusMeters = 700.0,
    )
}

fun buildSpotHeatMidZoneGeoJson(
    spots: List<CommunityReport>,
    activityCoords: List<Coordinates> = emptyList(),
): String {
    return buildSpotHeatZoneGeoJson(
        spots = spots,
        activityCoords = activityCoords,
        radiusMultiplier = 2.1,
        minRadiusMeters = 85.0,
        maxRadiusMeters = 360.0,
    )
}

fun buildSpotHeatCoreZoneGeoJson(
    spots: List<CommunityReport>,
    activityCoords: List<Coordinates> = emptyList(),
): String {
    return buildSpotHeatZoneGeoJson(
        spots = spots,
        activityCoords = activityCoords,
        radiusMultiplier = 1.0,
        minRadiusMeters = 42.0,
        maxRadiusMeters = 170.0,
    )
}

private fun buildSpotHeatZoneGeoJson(
    spots: List<CommunityReport>,
    activityCoords: List<Coordinates>,
    radiusMultiplier: Double,
    minRadiusMeters: Double,
    maxRadiusMeters: Double,
): String {
    val activeSpots = spots.filter { it.status != CommunityReportStatus.RESOLVED }
    if (activeSpots.isEmpty() && activityCoords.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""

    val spotFeatures = activeSpots.map { spot ->
        val radiusMeters = spotHeatZoneRadiusMeters(
            spot = spot,
            radiusMultiplier = radiusMultiplier,
            minRadiusMeters = minRadiusMeters,
            maxRadiusMeters = maxRadiusMeters,
        )
        val ring = circlePolygonRing(
            lat = spot.coordinates.lat,
            lng = spot.coordinates.lng,
            radiusMeters = radiusMeters,
        )
        """{"type":"Feature","properties":{"id":${spot.id},"radiusMeters":$radiusMeters},"geometry":{"type":"Polygon","coordinates":[[$ring]]}}"""
    }
    val activityRadiusMeters = (minRadiusMeters * 0.72).coerceIn(40.0, maxRadiusMeters)
    val activityFeatures = activityCoords.distinctBy { "${it.lat}:${it.lng}" }.map { coord ->
        val ring = circlePolygonRing(
            lat = coord.lat,
            lng = coord.lng,
            radiusMeters = activityRadiusMeters,
        )
        """{"type":"Feature","properties":{"id":-1,"radiusMeters":$activityRadiusMeters},"geometry":{"type":"Polygon","coordinates":[[$ring]]}}"""
    }
    val features = (spotFeatures + activityFeatures).joinToString(",")

    return """{"type":"FeatureCollection","features":[$features]}"""
}

private fun spotHeatZoneRadiusMeters(
    spot: CommunityReport,
    radiusMultiplier: Double,
    minRadiusMeters: Double,
    maxRadiusMeters: Double,
): Double {
    val affectedRadius = spot.affectedRadiusMeters
        .takeIf { it.isFinite() && it > 0.0 }
        ?: 35.0
    val weightScale = (0.9 + (spotHeatWeight(spot) - 1.0) * 0.12).coerceIn(0.86, 1.16)
    return (affectedRadius.coerceIn(24.0, 180.0) * radiusMultiplier * weightScale)
        .coerceIn(minRadiusMeters, maxRadiusMeters)
}

private fun circlePolygonRing(
    lat: Double,
    lng: Double,
    radiusMeters: Double,
    segments: Int = 32,
): String {
    val metersPerDegreeLat = 111_320.0
    val metersPerDegreeLng = (111_320.0 * cos(lat * PI / 180.0)).coerceAtLeast(1.0)
    val points = (0 until segments).map { index ->
        val angle = 2.0 * PI * index / segments
        val dx = cos(angle) * radiusMeters / metersPerDegreeLng
        val dy = sin(angle) * radiusMeters / metersPerDegreeLat
        "[${lng + dx},${lat + dy}]"
    }
    return points.joinToString(",") + ",${points.first()}"
}

private fun spotHeatWeight(spot: CommunityReport): Double {
    val statusWeight = when (spot.status) {
        CommunityReportStatus.VERIFIED -> 1.45
        CommunityReportStatus.ACKNOWLEDGED -> 1.25
        CommunityReportStatus.PENDING -> 1.0
        else -> 0.8
    }
    val bountyBoost = if (spot.bountyId != null) 0.4 else 0.0
    val verificationBoost = if (spot.needsVerification) 0.25 else 0.0
    val rewardBoost = spot.observerRewardPoints.coerceIn(0, 25) / 100.0
    val radiusBoost = ((spot.affectedRadiusMeters - 20.0) / 180.0).coerceIn(0.0, 0.35)
    val severityBoost = when (spot.aiAnalysis?.severity) {
        "high" -> 0.3
        "medium" -> 0.15
        else -> 0.0
    }
    return (statusWeight + bountyBoost + verificationBoost + rewardBoost + radiusBoost + severityBoost)
        .coerceIn(0.65, 2.4)
}

private fun spotDensityWeight(spot: CommunityReport): Double {
    val statusWeight = when (spot.status) {
        CommunityReportStatus.VERIFIED -> 0.34
        CommunityReportStatus.ACKNOWLEDGED -> 0.3
        CommunityReportStatus.PENDING -> 0.26
        else -> 0.22
    }
    val bountyBoost = if (spot.bountyId != null) 0.05 else 0.0
    val verificationBoost = if (spot.needsVerification) 0.04 else 0.0
    val rewardBoost = spot.observerRewardPoints.coerceIn(0, 25) / 500.0
    val radiusBoost = ((spot.affectedRadiusMeters - 20.0) / 220.0).coerceIn(0.0, 0.06)
    val severityBoost = when (spot.aiAnalysis?.severity) {
        "high" -> 0.06
        "medium" -> 0.03
        else -> 0.0
    }
    return (statusWeight + bountyBoost + verificationBoost + rewardBoost + radiusBoost + severityBoost)
        .coerceIn(0.18, 0.48)
}

/** Spots that have progressed into a bounty, rendered as a distinct halo. */
fun buildBountiedSpotMarkersGeoJson(spots: List<CommunityReport>): String {
    val features = spots
        .filter { it.status != CommunityReportStatus.RESOLVED && it.bountyId != null }
        .map { s ->
            """{"type":"Feature","properties":{"id":${s.id},"bountyId":${s.bountyId}},"geometry":{"type":"Point","coordinates":[${s.coordinates.lng},${s.coordinates.lat}]}}"""
        }
        .joinToString(",")
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
 * Builds a world-sized polygon used as the clean green baseline beneath
 * overview heatmaps. Heatmap density is transparent at zero, so this layer
 * makes unaffected areas read as clean instead of blank.
 */
fun buildSpotCleanZoneBaseGeoJson(): String {
    return """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[[-180,-85],[180,-85],[180,85],[-180,85],[-180,-85]]]}}]}"""
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
/**
 * Builds a GeoJSON FeatureCollection of Polygon features for rectangle-type spot areas.
 * Computes 4 rotated corner coordinates from center, width/height in meters, and rotation.
 * Circle-type spots are excluded (rendered by CircleLayer instead).
 */
fun buildSpotRectanglePolygonsGeoJson(spots: List<CommunityReport>): String {
    val rectSpots = spots.filter {
        it.status != CommunityReportStatus.RESOLVED && it.affectedArea is AffectedArea.Rectangle
    }
    if (rectSpots.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""

    val features = rectSpots.map { s ->
        val rect = s.affectedArea as AffectedArea.Rectangle
        val lat = s.coordinates.lat
        val lng = s.coordinates.lng
        // Convert meters to degrees (approximate at this latitude)
        val metersPerDegreeLat = 111320.0
        val metersPerDegreeLng = 111320.0 * cos(lat * kotlin.math.PI / 180.0)
        val halfW = (rect.widthMeters / 2.0) / metersPerDegreeLng
        val halfH = (rect.heightMeters / 2.0) / metersPerDegreeLat
        val rad = rect.rotationDeg * kotlin.math.PI / 180.0
        val cosR = cos(rad)
        val sinR = sin(rad)

        // 4 corners (unrotated: ±halfW, ±halfH), then rotate around center
        val corners = listOf(
            -halfW to -halfH, halfW to -halfH,
            halfW to halfH, -halfW to halfH,
        ).map { (dx, dy) ->
            val rx = dx * cosR - dy * sinR
            val ry = dx * sinR + dy * cosR
            "[${lng + rx},${lat + ry}]"
        }
        // Close the ring
        val ring = corners.joinToString(",") + ",${corners.first()}"
        """{"type":"Feature","properties":{"id":${s.id},"needsVerification":${s.needsVerification}},"geometry":{"type":"Polygon","coordinates":[[$ring]]}}"""
    }.joinToString(",")
    return """{"type":"FeatureCollection","features":[$features]}"""
}

/**
 * Builds a GeoJSON FeatureCollection of true-scale, oriented footprint
 * polygons for world observations that carry a physical width (Pillar 3
 * Task 3 — see docs/superpowers/plans/2026-07-07-pillar3-footprints.md).
 *
 * Geometry approximation table (v1):
 *  - domain == "vegetation" → circle (12-gon) of diameter [WorldObservation.widthMeters]
 *  - everything else → oriented rectangle widthMeters × (widthMeters × 0.4),
 *    rotated by [WorldObservation.yawDegrees] (bearing of the observed face;
 *    defaults to 0 when unmeasured)
 *
 * Observations with a null/non-finite/non-positive widthMeters are skipped —
 * there is no measured footprint to draw. Feature properties carry itemType,
 * domain, and state so GccMap.ios can filter/color per domain and the tap
 * target can identify what was scanned.
 *
 * [centerLat] anchors the lng/lat meter-scale conversion for the oriented
 * rectangles (single lngScale for the whole collection, matching
 * [buildCoverageStateGeoJsons]'s pattern); the circle branch reuses
 * [circlePolygonRing], which scales per-feature off its own latitude.
 */
fun buildObservationFootprintsGeoJson(
    observations: List<WorldObservation>,
    centerLat: Double,
): String {
    if (observations.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""
    val metersPerDegreeLat = 111_320.0
    val metersPerDegreeLng = (metersPerDegreeLat * cos(centerLat * PI / 180.0)).coerceAtLeast(1.0)

    val features = observations.mapNotNull { obs ->
        val width = obs.widthMeters
        if (width == null || !width.isFinite() || width <= 0.0) return@mapNotNull null
        val yaw = obs.yawDegrees?.takeIf { it.isFinite() } ?: 0.0

        val ring = if (obs.domain == "vegetation") {
            circlePolygonRing(lat = obs.lat, lng = obs.lng, radiusMeters = width / 2.0, segments = 12)
        } else {
            footprintRectRing(
                lat = obs.lat,
                lng = obs.lng,
                widthMeters = width,
                depthMeters = width * 0.4,
                yawDegrees = yaw,
                metersPerDegreeLat = metersPerDegreeLat,
                metersPerDegreeLng = metersPerDegreeLng,
            )
        }

        val itemTypeProp = obs.itemType?.let { """"itemType":"$it",""" } ?: ""
        val domainProp = obs.domain?.let { """"domain":"$it",""" } ?: ""
        """{"type":"Feature","properties":{$itemTypeProp$domainProp"state":"${obs.state}"},"geometry":{"type":"Polygon","coordinates":[[$ring]]}}"""
    }.joinToString(",")

    return """{"type":"FeatureCollection","features":[$features]}"""
}

/**
 * Oriented rectangle ring for [buildObservationFootprintsGeoJson]'s non-
 * vegetation branch — same corner-rotation idiom as
 * [buildSpotRectanglePolygonsGeoJson] (rotate ±half-extents around center in
 * already-degree-converted space), parameterized on a caller-supplied
 * meters-per-degree pair instead of recomputing per feature.
 */
private fun footprintRectRing(
    lat: Double,
    lng: Double,
    widthMeters: Double,
    depthMeters: Double,
    yawDegrees: Double,
    metersPerDegreeLat: Double,
    metersPerDegreeLng: Double,
): String {
    val halfW = widthMeters / 2.0
    val halfD = depthMeters / 2.0
    val rad = yawDegrees * PI / 180.0
    val cosR = cos(rad)
    val sinR = sin(rad)

    // 4 corners (unrotated: ±halfW, ±halfD) in METER space, rotated with
    // compass (clockwise-from-north) handedness — yaw is a bearing, not a
    // CCW math angle, so a plain CCW rotation mirrors the shape and mixes
    // lat/lng-scaled axes before conversion, both wrong. Rotating in meters
    // first and converting per-axis afterward (lat: /metersPerDegreeLat,
    // lng: /metersPerDegreeLng) keeps the footprint's true aspect ratio;
    // yaw 0 keeps the (long) width axis east-west, yaw 90 swings it to
    // north-south, yaw 45 to bearing 135 (SE-NW).
    val corners = listOf(
        -halfW to -halfD, halfW to -halfD,
        halfW to halfD, -halfW to halfD,
    ).map { (dx, dy) ->
        val rx = dx * cosR + dy * sinR
        val ry = -dx * sinR + dy * cosR
        val dLng = rx / metersPerDegreeLng
        val dLat = ry / metersPerDegreeLat
        "[${lng + dLng},${lat + dLat}]"
    }
    return corners.joinToString(",") + ",${corners.first()}"
}

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


/** The five coverage-state tile collections — one visual system: green =
 *  scanned clean (full or grazed), red = scanned but litter on record,
 *  amber = stale (ready for a fresh scan), fog veil = never scanned at all. */
data class CoverageStateJsons(
    val clean: String,
    /** Fresh, no-spot cells whose best quality is "grazed" (sweep-only) — the
     *  lighter green "come back and detail-scan me" tile (spec §3). */
    val cleanGrazed: String,
    val dirty: String,
    val stale: String,
    val fog: String,
)

/**
 * Foundational coverage-state tiles for the map (product direction
 * 2026-07-03; visual system extended 2026-07-06): communicate scan state
 * per ~10m ledger cell at a glance.
 *  - clean: fresh, no-spot cells whose best-recorded quality is "close" → full green tint
 *  - cleanGrazed: fresh, no-spot cells whose best-recorded quality is NOT
 *    "close" (sweep-only) → lighter green, "come back and detail-scan me"
 *  - dirty: fresh cells WITH an active spot/litter on record → red tint
 *    (the tile-level "cleanliness readout"; the heat layers still grade
 *    density at overview zooms)
 *  - stale: cells last scanned >24h ago → amber, the "scan me again" call
 *  - fog: never-scanned veil over [fogBounds] with holes at covered cells
 */
fun buildCoverageStateGeoJsons(
    coverageCells: List<com.gcc.shared.model.MapCoverageCell>,
    activeSpots: List<com.gcc.shared.model.CommunityReport>,
    centerLat: Double,
    centerLng: Double,
    /**
     * Geographic bounds for the never-scanned fog (minLat, minLng, maxLat,
     * maxLng) — typically the route cluster's bbox + margin. Anchoring fog
     * to GEOGRAPHY (not a disc around the camera) is what stops it reading
     * as a floating circular patch at intermediate zooms (field report
     * 2026-07-03, user screenshots). Null → no fog layer at all.
     */
    fogBounds: DoubleArray? = null,
    cellSizeMeters: Double = 10.0,
): CoverageStateJsons {
    val empty = """{"type":"FeatureCollection","features":[]}"""
    val lngScale = kotlin.math.cos(centerLat * kotlin.math.PI / 180.0)
    val latStep = cellSizeMeters / 111_320.0
    val lngStep = cellSizeMeters / (111_320.0 * lngScale)

    fun cellQuad(lat: Double, lng: Double): String {
        val hLat = latStep / 2
        val hLng = lngStep / 2
        return "[[${lng - hLng},${lat - hLat}],[${lng + hLng},${lat - hLat}]," +
            "[${lng + hLng},${lat + hLat}],[${lng - hLng},${lat + hLat}],[${lng - hLng},${lat - hLat}]]"
    }

    // cellId rides in feature properties so the fill layers are tappable —
    // MapFullScreen resolves the id back to the full MapCoverageCell for the
    // last-scan info card.
    fun feature(cell: com.gcc.shared.model.MapCoverageCell): String =
        """{"type":"Feature","properties":{"cellId":"${cell.locationCellId}"},""" +
            """"geometry":{"type":"Polygon","coordinates":[${cellQuad(cell.lat, cell.lng)}]}}"""

    val spots = activeSpots.filter { it.status != com.gcc.shared.model.CommunityReportStatus.RESOLVED }
    fun hasSpotNear(lat: Double, lng: Double): Boolean = spots.any { spot ->
        val dLatM = (spot.coordinates.lat - lat) * 111_320.0
        val dLngM = (spot.coordinates.lng - lng) * 111_320.0 * lngScale
        dLatM * dLatM + dLngM * dLngM < 12.0 * 12.0
    }

    val fresh = coverageCells.filter { it.state == "fresh" }
    val freshNoSpot = fresh.filter { !hasSpotNear(it.lat, it.lng) }
    // Two-tier clean (spec §3): close-scanned reads full green; grazed reads a
    // lighter green — "touched, still needs a detail pass".
    val clean = freshNoSpot
        .filter { it.quality == "close" }
        .joinToString(",") { feature(it) }
    val cleanGrazed = freshNoSpot
        .filter { it.quality != "close" }
        .joinToString(",") { feature(it) }
    // Scanned recently but litter is still on record — previously these
    // cells were silently DROPPED (no tile at all); now they read red so a
    // scanned-but-dirty area is distinguishable from unscanned ground.
    val dirty = fresh
        .filter { hasSpotNear(it.lat, it.lng) }
        .joinToString(",") { feature(it) }
    val stale = coverageCells
        .filter { it.state == "stale" }
        .joinToString(",") { feature(it) }

    // Never-scanned fog: ONE smooth veil polygon over the bounded area with
    // holes punched where the ledger has coverage. Rendering discrete cell
    // quads produced a "broken grid lines" look at every attempt (three
    // field reports) — a single translucent surface with scan holes reads
    // as deliberate fog instead.
    val fogFeatures = StringBuilder()
    if (fogBounds != null && fogBounds.size == 4) {
        val minLat = fogBounds[0]
        val minLng = fogBounds[1]
        val maxLat = fogBounds[2]
        val maxLng = fogBounds[3]
        val outerRing = "[[$minLng,$minLat],[$maxLng,$minLat],[$maxLng,$maxLat],[$minLng,$maxLat],[$minLng,$minLat]]"
        val holeSize = cellSizeMeters * 1.2
        val hLat = holeSize / 2 / 111_320.0
        val hLng = holeSize / 2 / (111_320.0 * lngScale)
        val holes = coverageCells
            .filter { it.lat in minLat..maxLat && it.lng in minLng..maxLng }
            .take(1500)
            .joinToString(",") { cell ->
                "[[${cell.lng - hLng},${cell.lat - hLat}],[${cell.lng + hLng},${cell.lat - hLat}]," +
                    "[${cell.lng + hLng},${cell.lat + hLat}],[${cell.lng - hLng},${cell.lat + hLat}],[${cell.lng - hLng},${cell.lat - hLat}]]"
            }
        val rings = if (holes.isEmpty()) "[$outerRing]" else "[$outerRing,$holes]"
        fogFeatures.append(
            """{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":$rings}}"""
        )
    }

    fun wrap(features: String) =
        if (features.isEmpty()) empty else """{"type":"FeatureCollection","features":[$features]}"""
    return CoverageStateJsons(
        clean = wrap(clean),
        cleanGrazed = wrap(cleanGrazed),
        dirty = wrap(dirty),
        stale = wrap(stale),
        fog = wrap(fogFeatures.toString()),
    )
}


/**
 * Explorer-mode scan-state color coding: buckets every Chicago community
 * area by its scan state — "clean" | "dirty" | "stale" from analytics,
 * everything else defaults to "unscanned" (grey; the whole city today).
 * Keys are matched on normalized display names.
 */
fun buildExplorerAreaStateGeoJsons(
    areaStates: Map<String, String>,
): Map<String, String> {
    val normalized = areaStates.mapKeys { (k, _) -> k.trim().lowercase() }
    fun stateOf(area: CommunityArea): String =
        normalized[area.displayName.replace("\n", " ").trim().lowercase()] ?: "unscanned"
    return listOf("unscanned", "clean", "dirty", "stale").associateWith { state ->
        buildCommunityAreasGeoJson(chicagoCommunityAreas.filter { stateOf(it) == state })
    }
}
