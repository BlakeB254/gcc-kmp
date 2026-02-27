package com.gcc.app.ui.screens

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A single polygon with an id, name, and one or more rings of (lat, lng) pairs.
 * rings[0] is the outer ring; rings[1+] are holes (if any).
 */
data class SplashPolygon(
    val id: Int,
    val name: String,
    val rings: List<List<Pair<Double, Double>>>, // (lat, lng) pairs
)

/**
 * Holds parsed GeoJSON data for the splash screen map phases.
 */
data class SplashGeoData(
    val chicagoAreas: List<SplashPolygon>,
    val usStates: List<SplashPolygon>,
) {
    /** Community area #29 — North Lawndale, the focal point. */
    val northLawndale: SplashPolygon? get() = chicagoAreas.find { it.id == 29 }
}

private val json = Json { ignoreUnknownKeys = true }

/**
 * Parses chicago_communities_real.json (GeoJSON FeatureCollection) into [SplashPolygon] list.
 *
 * Each feature has properties.num (Int) and properties.name (String).
 * Geometry is MultiPolygon or Polygon with coordinates in [lng, lat] order.
 * For MultiPolygon, we take only the first polygon's rings (skips tiny disconnected pieces).
 */
fun parseChicagoGeoJson(jsonString: String): List<SplashPolygon> {
    val root = json.parseToJsonElement(jsonString).jsonObject
    val features = root["features"]!!.jsonArray

    return features.mapNotNull { featureElement ->
        val feature = featureElement.jsonObject
        val props = feature["properties"]!!.jsonObject
        val num = props["num"]!!.jsonPrimitive.int
        val name = props["name"]!!.jsonPrimitive.content

        val geometry = feature["geometry"]!!.jsonObject
        val type = geometry["type"]!!.jsonPrimitive.content
        val coordinates = geometry["coordinates"]!!.jsonArray

        val rings = extractFirstPolygonRings(type, coordinates)
        if (rings.isEmpty()) return@mapNotNull null

        SplashPolygon(id = num, name = name, rings = rings)
    }
}

/**
 * Parses us_states.json (GeoJSON FeatureCollection) into [SplashPolygon] list.
 *
 * Each feature has properties.name (String).
 * Geometry is Polygon or MultiPolygon with coordinates in [lng, lat] order.
 * For MultiPolygon, we flatten all polygons' outer rings into separate rings
 * (so each state's rings list includes all its disconnected landmasses).
 */
fun parseUsStatesGeoJson(jsonString: String): List<SplashPolygon> {
    val root = json.parseToJsonElement(jsonString).jsonObject
    val features = root["features"]!!.jsonArray

    return features.mapIndexedNotNull { index, featureElement ->
        val feature = featureElement.jsonObject
        val props = feature["properties"]!!.jsonObject
        val name = props["name"]!!.jsonPrimitive.content

        val geometry = feature["geometry"]!!.jsonObject
        val type = geometry["type"]!!.jsonPrimitive.content
        val coordinates = geometry["coordinates"]!!.jsonArray

        val rings = extractAllPolygonRings(type, coordinates)
        if (rings.isEmpty()) return@mapIndexedNotNull null

        SplashPolygon(id = index, name = name, rings = rings)
    }
}

/**
 * For Chicago: extracts rings from the FIRST polygon only.
 * - Polygon: coordinates = [ ring, ring, ... ]
 * - MultiPolygon: coordinates = [ [ ring, ring, ... ], ... ] — take [0]
 */
private fun extractFirstPolygonRings(
    type: String,
    coordinates: JsonArray,
): List<List<Pair<Double, Double>>> {
    val polygonCoords: JsonArray = when (type) {
        "Polygon" -> coordinates
        "MultiPolygon" -> coordinates[0].jsonArray // first polygon only
        else -> return emptyList()
    }
    return polygonCoords.map { ringElement ->
        parseRing(ringElement.jsonArray)
    }
}

/**
 * For US states: extracts ALL polygon rings (flattened) so we draw every landmass.
 * - Polygon: coordinates = [ ring, ring, ... ]
 * - MultiPolygon: coordinates = [ [ ring, ... ], [ ring, ... ], ... ]
 */
private fun extractAllPolygonRings(
    type: String,
    coordinates: JsonArray,
): List<List<Pair<Double, Double>>> {
    return when (type) {
        "Polygon" -> {
            coordinates.map { ringElement -> parseRing(ringElement.jsonArray) }
        }
        "MultiPolygon" -> {
            coordinates.flatMap { polygonElement ->
                polygonElement.jsonArray.map { ringElement ->
                    parseRing(ringElement.jsonArray)
                }
            }
        }
        else -> emptyList()
    }
}

/**
 * Converts a GeoJSON ring (list of [lng, lat] arrays) to Kotlin (lat, lng) pairs.
 */
private fun parseRing(ring: JsonArray): List<Pair<Double, Double>> {
    return ring.map { pointElement ->
        val point = pointElement.jsonArray
        val lng = point[0].jsonPrimitive.double
        val lat = point[1].jsonPrimitive.double
        lat to lng // convert [lng, lat] → (lat, lng)
    }
}
