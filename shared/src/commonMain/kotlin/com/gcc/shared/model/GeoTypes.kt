package com.gcc.shared.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

typealias Point = List<Double> // [lng, lat] GeoJSON format

/** Deserializes from both `{"lng":..,"lat":..}` objects and `[lng, lat]` arrays. */
object CoordinatesSerializer : KSerializer<Coordinates> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Coordinates")

    override fun deserialize(decoder: Decoder): Coordinates {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        return when (element) {
            is JsonArray -> {
                val lng = element[0].jsonPrimitive.double
                val lat = element[1].jsonPrimitive.double
                Coordinates(lng, lat)
            }
            is JsonObject -> {
                val lng = element["lng"]?.jsonPrimitive?.double ?: 0.0
                val lat = element["lat"]?.jsonPrimitive?.double ?: 0.0
                Coordinates(lng, lat)
            }
            else -> Coordinates(0.0, 0.0)
        }
    }

    override fun serialize(encoder: Encoder, value: Coordinates) {
        val obj = buildJsonObject {
            put("lng", value.lng)
            put("lat", value.lat)
        }
        (encoder as JsonEncoder).encodeJsonElement(obj)
    }
}

/**
 * A geographic point, **longitude-first** (GeoJSON order: `[lng, lat]`).
 *
 * ALWAYS construct with named args — `Coordinates(lat = …, lng = …)`. The
 * positional order is `(lng, lat)`, so a bare `Coordinates(a, b)` reads as
 * lat-first to most people and silently transposes the point. This has caused
 * a real "every pin off the map" bug (see WorldObservation.kt). See
 * `docs/CONVENTIONS.md` §1.
 */
@Serializable(with = CoordinatesSerializer::class)
data class Coordinates(val lng: Double, val lat: Double)

@Serializable
data class GeoJSONMultiPolygon(
    val type: String = "MultiPolygon",
    val coordinates: List<List<List<Point>>>,
)

@Serializable
data class GeoJSONPolygon(
    val type: String = "Polygon",
    val coordinates: List<List<Point>>
)

@Serializable
data class GeoJSONLineString(
    val type: String = "LineString",
    val coordinates: List<Point>
) {
    /**
     * Malformed points (fewer than 2 ordinates) are dropped rather than
     * indexed into — this runs unguarded inside Compose composition via
     * `Route.routePathCoordinates()`, where an IndexOutOfBoundsException
     * would take down the screen rather than surface as an error state.
     */
    fun toCoordinates(): List<Coordinates> =
        coordinates.mapNotNull { point ->
            val lng = point.getOrNull(0)
            val lat = point.getOrNull(1)
            if (lat != null && lng != null) Coordinates(lng = lng, lat = lat) else null
        }
}

@Serializable
data class GeoJSONPoint(
    val type: String = "Point",
    val coordinates: Point
)
