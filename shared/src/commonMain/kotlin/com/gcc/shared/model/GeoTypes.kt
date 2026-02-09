package com.gcc.shared.model

import kotlinx.serialization.Serializable

typealias Point = List<Double> // [lng, lat] GeoJSON format

@Serializable
data class Coordinates(val lng: Double, val lat: Double)

@Serializable
data class GeoJSONPolygon(
    val type: String = "Polygon",
    val coordinates: List<List<Point>>
)

@Serializable
data class GeoJSONLineString(
    val type: String = "LineString",
    val coordinates: List<Point>
)

@Serializable
data class GeoJSONPoint(
    val type: String = "Point",
    val coordinates: Point
)
