package com.gcc.app.ui.map

import com.gcc.shared.model.WorldObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObservationFootprintsTest {

    private val lat0 = 41.8600
    private val lng0 = -87.7200

    private fun observation(
        domain: String? = "litter",
        widthMeters: Double? = 10.0,
        yawDegrees: Double? = null,
    ) = WorldObservation(
        id = 1,
        lat = lat0,
        lng = lng0,
        itemType = "debris_pile",
        domain = domain,
        state = "dirty",
        sourceType = "route_scan",
        widthMeters = widthMeters,
        yawDegrees = yawDegrees,
    )

    /** Pulls every "[lng,lat]" pair out of a built GeoJSON ring, in order. */
    private fun ringCoordinates(json: String): List<Pair<Double, Double>> {
        val regex = Regex("""\[(-?[0-9.]+),(-?[0-9.]+)]""")
        return regex.findAll(json)
            .map { m -> m.groupValues[1].toDouble() to m.groupValues[2].toDouble() }
            .toList()
    }

    @Test
    fun vegetationCircleRingCloses() {
        val json = buildObservationFootprintsGeoJson(
            listOf(observation(domain = "vegetation")),
            centerLat = lat0,
        )
        val coords = ringCoordinates(json)
        assertEquals(coords.first(), coords.last())
        // 12-gon: 12 distinct vertices + 1 closing duplicate.
        assertEquals(13, coords.size)
    }

    @Test
    fun orientedRectRingCloses() {
        val json = buildObservationFootprintsGeoJson(
            listOf(observation(domain = "litter")),
            centerLat = lat0,
        )
        val coords = ringCoordinates(json)
        assertEquals(coords.first(), coords.last())
        // 4 corners + 1 closing duplicate.
        assertEquals(5, coords.size)
    }

    @Test
    fun orientedRectBoundsSwapLatLngExtentBetweenYaw0And90() {
        val yaw0Json = buildObservationFootprintsGeoJson(
            listOf(observation(domain = "litter", widthMeters = 10.0, yawDegrees = 0.0)),
            centerLat = lat0,
        )
        val yaw90Json = buildObservationFootprintsGeoJson(
            listOf(observation(domain = "litter", widthMeters = 10.0, yawDegrees = 90.0)),
            centerLat = lat0,
        )
        val c0 = ringCoordinates(yaw0Json)
        val c90 = ringCoordinates(yaw90Json)

        val lngExtent0 = c0.maxOf { it.first } - c0.minOf { it.first }
        val latExtent0 = c0.maxOf { it.second } - c0.minOf { it.second }
        val lngExtent90 = c90.maxOf { it.first } - c90.minOf { it.first }
        val latExtent90 = c90.maxOf { it.second } - c90.minOf { it.second }

        // yaw 0: the long (width) axis runs east-west -> lng extent is bigger.
        assertTrue(lngExtent0 > latExtent0, "yaw0 expected lng>lat, got lng=$lngExtent0 lat=$latExtent0")
        // yaw 90: the long axis swings to north-south -> lat extent is bigger.
        assertTrue(latExtent90 > lngExtent90, "yaw90 expected lat>lng, got lng=$lngExtent90 lat=$latExtent90")
    }

    @Test
    fun nullWidthObservationIsSkipped() {
        val json = buildObservationFootprintsGeoJson(
            listOf(observation(widthMeters = null)),
            centerLat = lat0,
        )
        assertEquals("""{"type":"FeatureCollection","features":[]}""", json)
    }

    @Test
    fun nonFiniteWidthObservationIsSkipped() {
        val json = buildObservationFootprintsGeoJson(
            listOf(observation(widthMeters = Double.NaN)),
            centerLat = lat0,
        )
        assertEquals("""{"type":"FeatureCollection","features":[]}""", json)
    }

    @Test
    fun featurePropertiesCarryItemTypeDomainAndState() {
        val json = buildObservationFootprintsGeoJson(
            listOf(observation(domain = "construction")),
            centerLat = lat0,
        )
        assertTrue(json.contains(""""itemType":"debris_pile""""))
        assertTrue(json.contains(""""domain":"construction""""))
        assertTrue(json.contains(""""state":"dirty""""))
    }
}
