package com.gcc.app.ui.map

import com.gcc.shared.model.WorldObservation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
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
        diagnosticStatus: String? = null,
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
        diagnosticStatus = diagnosticStatus,
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
    fun orientedRectWidthAxisMatchesCompassBearingAtYaw45() {
        // Compass yaw is bearing (clockwise from north), not a CCW math
        // angle — the width axis at yaw must land on bearing yaw+90 (135
        // here), never the mirrored 90-yaw (45) a plain CCW rotation of
        // already-degree-scaled deltas would produce.
        val json = buildObservationFootprintsGeoJson(
            listOf(observation(domain = "litter", widthMeters = 10.0, yawDegrees = 45.0)),
            centerLat = lat0,
        )
        val coords = ringCoordinates(json)
        // Ring order is [(-halfW,-halfD), (halfW,-halfD), (halfW,halfD), (-halfW,halfD), close].
        // Corner 0 -> corner 2 is the rectangle's diagonal; its local (pre-
        // rotation) offset is exactly (width, depth), so its bearing sits
        // atan(depth/width) = ~21.8 deg off the pure width-axis bearing —
        // expected ~113.2 deg here, not the ~90 deg-away 23.2 deg the mirror
        // bug would produce.
        val metersPerDegreeLat = 111_320.0
        val metersPerDegreeLng = metersPerDegreeLat * cos(lat0 * PI / 180.0)
        val (lng0, lat0v) = coords[0]
        val (lng2, lat2) = coords[2]
        val dLngMeters = (lng2 - lng0) * metersPerDegreeLng
        val dLatMeters = (lat2 - lat0v) * metersPerDegreeLat
        val bearing = (Math.toDegrees(atan2(dLngMeters, dLatMeters)) + 360.0) % 360.0

        val expectedBearing = 113.2
        assertTrue(
            abs(bearing - expectedBearing) < 5.0,
            "expected diagonal bearing near $expectedBearing (width axis ~135), got $bearing",
        )
    }

    @Test
    fun nullWidthObservationFallsBackToTypeDefaultInsteadOfBeingSkipped() {
        // Pillar 8 Task 2, Part B.1: unmeasured observations no longer get
        // dropped — they fall back to TrashItemCatalog's typical width for
        // the observation's itemType (here "debris_pile", which has no
        // dedicated catalog entry and falls back further to "other").
        val json = buildObservationFootprintsGeoJson(
            listOf(observation(widthMeters = null)),
            centerLat = lat0,
        )
        assertTrue(json.contains(""""id":1"""))
        val coords = ringCoordinates(json)
        assertEquals(5, coords.size) // still a closed 4-corner rect (non-vegetation domain)
    }

    @Test
    fun nonFiniteWidthObservationFallsBackToTypeDefaultInsteadOfBeingSkipped() {
        val json = buildObservationFootprintsGeoJson(
            listOf(observation(widthMeters = Double.NaN)),
            centerLat = lat0,
        )
        assertTrue(json.contains(""""id":1"""))
        assertEquals(5, ringCoordinates(json).size)
    }

    @Test
    fun typeDefaultWidthIsFlooredToTheVisibilityMinimum() {
        // "other"'s typicalWidthMeters (0.15m, see TrashItemCatalog) is far
        // below the ~6.29m visibility floor computed for the z15.5
        // full-opacity zoom — the rendered rectangle's width axis must
        // reflect the floor, not the raw 0.15m.
        val json = buildObservationFootprintsGeoJson(
            listOf(observation(domain = "litter", widthMeters = null, yawDegrees = 0.0)),
            centerLat = lat0,
        )
        val coords = ringCoordinates(json)
        val lngExtent = coords.maxOf { it.first } - coords.minOf { it.first }
        val metersPerDegreeLng = 111_320.0 * cos(lat0 * PI / 180.0)
        val widthMeters = lngExtent * metersPerDegreeLng
        assertTrue(widthMeters > 5.0, "expected floored width > 5.0m, got $widthMeters")
    }

    @Test
    fun measuredWidthAboveTheFloorIsUnaffectedByIt() {
        // The existing orientedRectRingCloses/bounds tests already use a
        // real (non-null) widthMeters=10.0, well above the ~6.29m floor —
        // this just pins that the floor is a MINIMUM, not a rescale, once a
        // real measurement clears it.
        val json = buildObservationFootprintsGeoJson(
            listOf(observation(domain = "litter", widthMeters = 10.0, yawDegrees = 0.0)),
            centerLat = lat0,
        )
        val coords = ringCoordinates(json)
        val lngExtent = coords.maxOf { it.first } - coords.minOf { it.first }
        val metersPerDegreeLng = 111_320.0 * cos(lat0 * PI / 180.0)
        val widthMeters = lngExtent * metersPerDegreeLng
        assertTrue(abs(widthMeters - 10.0) < 0.01, "expected ~10.0m unaffected by floor, got $widthMeters")
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

    // ── diagnosticStatus -> "diagnosed" property (Pillar 8 Task 4) ───────

    @Test
    fun diagnosticStatusObservedEmitsDiagnosedFalse() {
        val json = buildObservationFootprintsGeoJson(
            listOf(observation(diagnosticStatus = "observed")),
            centerLat = lat0,
        )
        assertTrue(json.contains(""""diagnosed":false"""))
    }

    @Test
    fun diagnosticStatusDiagnosedEmitsDiagnosedTrue() {
        val json = buildObservationFootprintsGeoJson(
            listOf(observation(diagnosticStatus = "diagnosed")),
            centerLat = lat0,
        )
        assertTrue(json.contains(""""diagnosed":true"""))
    }

    @Test
    fun nullDiagnosticStatusEmitsDiagnosedTrueNotObserved() {
        // Legacy rows / servers that predate this field must render solid
        // (diagnosed), not hollow (observed) — see WorldObservation.
        // diagnosticStatus's KDoc for the full rationale (observed-only is
        // a "needs completion" priority signal; null must not spam it).
        val json = buildObservationFootprintsGeoJson(
            listOf(observation(diagnosticStatus = null)),
            centerLat = lat0,
        )
        assertTrue(json.contains(""""diagnosed":true"""))
    }
}
