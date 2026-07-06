package com.gcc.app.platform

import com.gcc.shared.model.MapCoverageCell
import com.gcc.shared.model.RetractCoverageRequest
import com.gcc.shared.model.ScanCoverageEventRequest
import com.gcc.shared.serialization.GccJson
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals

class CoverageWireCompatTest {

    @Test
    fun legacyEventJsonDefaultsToGrazed() {
        // Deployed builds send NO quality field — they credit via the blind
        // 15m cone, which is exactly what "grazed" means (spec §3).
        val json = """{"deviceId":"d","platform":"ios","lat":41.0,"lng":-87.0,
            "distanceMeters":1.0,"scanMode":"spot","deviceTier":"basic",
            "capturedAt":"2026-07-06T15:00:00Z"}"""
        val event = GccJson.decodeFromString<ScanCoverageEventRequest>(json)
        assertEquals("grazed", event.quality)
    }

    @Test
    fun legacyMapCellJsonDefaultsToGrazed() {
        val json = """{"locationCellId":"c","lat":41.0,"lng":-87.0,"state":"fresh",
            "lastScannedAt":"2026-07-06T15:00:00Z","totalDistanceMeters":5.0}"""
        val cell = GccJson.decodeFromString<MapCoverageCell>(json)
        assertEquals("grazed", cell.quality)
    }

    @Test
    fun retractRequestRoundTrips() {
        val request = RetractCoverageRequest("d", "2026-07-06T15:00:00Z", "2026-07-06T15:00:10Z")
        val json = GccJson.encodeToString<RetractCoverageRequest>(request)
        val decoded = GccJson.decodeFromString<RetractCoverageRequest>(json)
        assertEquals(request, decoded)
    }
}
