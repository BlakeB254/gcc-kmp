package com.gcc.shared.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanCoverageTest {

    @Test
    fun submitRequestSerializesWithAllRequiredFields() {
        val request = ScanCoverageSubmitRequest(
            events = listOf(
                ScanCoverageEventRequest(
                    deviceId = "test-device",
                    platform = "ios",
                    lat = 41.87,
                    lng = -87.68,
                    distanceMeters = 5.0,
                    scanMode = "route",
                    deviceTier = "pro_scanner",
                    capturedAt = "2026-07-02T12:00:00Z",
                )
            )
        )
        val json = Json.encodeToString(ScanCoverageSubmitRequest.serializer(), request)
        assertTrue(json.contains("\"deviceId\":\"test-device\""))
        assertTrue(json.contains("\"platform\":\"ios\""))
        assertTrue(json.contains("\"scanMode\":\"route\""))
        // workerId omitted (null) should not appear as a required field failure —
        // just confirm the whole thing round-trips.
        val decoded = Json.decodeFromString(ScanCoverageSubmitRequest.serializer(), json)
        assertEquals(request, decoded)
    }

    @Test
    fun submitResponseDecodesFromLiveEndpointShape() {
        // Exact shape confirmed against the live POST /api/scans/coverage endpoint
        // this session (site/src/app/api/scans/coverage/route.ts) — not guessed.
        val raw = """{"inserted":1,"success":true}"""
        val decoded = Json.decodeFromString(ScanCoverageSubmitResponse.serializer(), raw)
        assertEquals(1, decoded.inserted)
        assertEquals(true, decoded.success)
    }

    @Test
    fun mapCoverageResponseDecodesFromLiveEndpointShape() {
        // Exact shape confirmed against the live GET /api/map/coverage endpoint
        // this session (site/src/app/api/map/coverage/route.ts) — not guessed.
        val raw = """{"cells":[{"locationCellId":"464756:-721607","lat":41.87,"lng":-87.68,"state":"fresh","lastScannedAt":"2026-07-02T12:00:00Z","totalDistanceMeters":5.0}],"success":true}"""
        val decoded = Json.decodeFromString(MapCoverageResponse.serializer(), raw)
        assertEquals(1, decoded.cells.size)
        assertEquals("fresh", decoded.cells[0].state)
    }
}
