package com.gcc.app.platform

import com.gcc.shared.model.CaptureUploadUrlRequest
import com.gcc.shared.model.EnvScanManifest
import com.gcc.shared.model.MapCoverageCell
import com.gcc.shared.model.RetractCoverageRequest
import com.gcc.shared.model.ScanCoverageEventRequest
import com.gcc.shared.serialization.GccJson
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun seenQualityRoundTripsOnEventAndMapCell() {
        // Pillar 7: 'seen' is a new wire value old servers/clients never
        // produced — new clients/servers must round-trip it cleanly.
        val event = ScanCoverageEventRequest(
            deviceId = "d", platform = "ios", lat = 41.0, lng = -87.0,
            distanceMeters = 1.0, scanMode = "route", deviceTier = "basic",
            capturedAt = "2026-07-06T15:00:00Z", quality = "seen",
        )
        val decodedEvent = GccJson.decodeFromString<ScanCoverageEventRequest>(
            GccJson.encodeToString(event),
        )
        assertEquals("seen", decodedEvent.quality)

        val cell = MapCoverageCell(
            locationCellId = "c", lat = 41.0, lng = -87.0, state = "fresh",
            lastScannedAt = "2026-07-06T15:00:00Z", totalDistanceMeters = 5.0, quality = "seen",
        )
        val decodedCell = GccJson.decodeFromString<MapCoverageCell>(GccJson.encodeToString(cell))
        assertEquals("seen", decodedCell.quality)
    }

    @Test
    fun legacyMapCellJsonDefaultsPriorityToZero() {
        // Deployed builds/cached responses predate the P5 rescan-priority
        // field entirely — no `priority` key on the wire at all.
        val json = """{"locationCellId":"c","lat":41.0,"lng":-87.0,"state":"fresh",
            "lastScannedAt":"2026-07-06T15:00:00Z","totalDistanceMeters":5.0,"quality":"close"}"""
        val cell = GccJson.decodeFromString<MapCoverageCell>(json)
        assertEquals(0.0, cell.priority)
    }

    @Test
    fun retractRequestRoundTrips() {
        val request = RetractCoverageRequest("d", "2026-07-06T15:00:00Z", "2026-07-06T15:00:10Z")
        val json = GccJson.encodeToString<RetractCoverageRequest>(request)
        val decoded = GccJson.decodeFromString<RetractCoverageRequest>(json)
        assertEquals(request, decoded)
    }

    @Test
    fun legacyManifestJsonDefaultsCaptureFieldsToNull() {
        // Deployed builds/persisted manifests predate Pillar-1 background
        // capture — no captureChunks/captureSchema on the wire at all.
        val json = """{"sessionId":"s","deviceId":"d","deviceModel":"m","osVersion":"18.0",
            "startedAtMs":1,"completedAtMs":2,"path":[],"scannedCells":[],
            "scannedCellSizeMeters":10.0,"observedItems":[],"photoKeys":[]}"""
        val manifest = GccJson.decodeFromString<EnvScanManifest>(json)
        assertNull(manifest.captureChunks)
        assertNull(manifest.captureSchema)
    }

    @Test
    fun manifestCaptureFieldsRoundTrip() {
        val manifest = EnvScanManifest(
            sessionId = "s",
            deviceId = "d",
            deviceModel = "m",
            osVersion = "18.0",
            startedAtMs = 1,
            completedAtMs = 2,
            path = emptyList(),
            scannedCells = emptyList(),
            scannedCellSizeMeters = 10.0,
            observedItems = emptyList(),
            photoKeys = emptyList(),
            captureChunks = listOf("raw/1:2/s/capture/chunk-0.mp4"),
            captureSchema = 1,
        )
        val json = GccJson.encodeToString<EnvScanManifest>(manifest)
        val decoded = GccJson.decodeFromString<EnvScanManifest>(json)
        assertEquals(manifest, decoded)
    }

    @Test
    fun legacyCaptureUploadUrlRequestJsonDefaultsSessionFieldsToNull() {
        // Deployed builds send only the legacy locationCellId/deviceId/
        // capturedAt/ext quartet — no sessionId/artifactPath.
        val json = """{"locationCellId":"1:2","deviceId":"d","capturedAt":"2026-07-06T15:00:00Z","ext":"jpg"}"""
        val request = GccJson.decodeFromString<CaptureUploadUrlRequest>(json)
        assertNull(request.sessionId)
        assertNull(request.artifactPath)
    }

    @Test
    fun captureUploadUrlRequestSessionFieldsRoundTrip() {
        val request = CaptureUploadUrlRequest(
            locationCellId = "1:2",
            deviceId = "d",
            capturedAt = "2026-07-06T15:00:00Z",
            ext = "mp4",
            sessionId = "s",
            artifactPath = "capture/chunk-0.mp4",
        )
        val json = GccJson.encodeToString<CaptureUploadUrlRequest>(request)
        val decoded = GccJson.decodeFromString<CaptureUploadUrlRequest>(json)
        assertEquals(request, decoded)
    }
}
