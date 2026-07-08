package com.gcc.shared.model

import kotlinx.serialization.Serializable

/**
 * One building footprint from `GET /api/map/buildings`. [polygon] is a GeoJSON
 * linear ring — each vertex is [lng, lat] (Coordinates is (lng,lat) repo-wide).
 */
@Serializable
data class BuildingPolygon(
    val polygon: List<List<Double>>,
    val buildingType: String? = null,
    val source: String = "overpass",
)

@Serializable
data class BuildingFootprintsResponse(
    val buildings: List<BuildingPolygon>,
    val success: Boolean,
)
