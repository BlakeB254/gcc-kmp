package com.gcc.app.model

import com.gcc.shared.model.AdminScanSessionDetail
import com.gcc.shared.model.AdminScanSessionSummary
import com.gcc.shared.model.ApiResponse
import com.gcc.shared.serialization.GccJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.decodeFromString

/**
 * Wire-contract lock for the admin scan-session review surface (PRD Phase 2):
 * the iOS admin consumes the SAME endpoints as the site panel through the
 * standard API patterns (ApiResponse envelope + shared DTOs), so these
 * fixtures mirror what /api/admin/scan-sessions[/{id}] actually emits —
 * including detail-route extras (manifestR2Key, worldRegistration, …) the
 * summary DTO must ignore, and server-derived redFlags both clients render.
 */
class AdminScanSessionsWireTest {

    @Test
    fun listResponseEnvelopeDecodes() {
        val body = """
        {"data":[{"id":7,"sessionId":"env-1700000000000-abc123","deviceId":"d1","deviceModel":"iPhone17,1",
          "platform":"ios","osVersion":"19.0","lapKind":"baseline","routeId":12,"locationCellId":"465773:-718118",
          "startedAt":"2026-07-12T14:00:00.000Z","completedAt":"2026-07-12T14:20:00.000Z",
          "photoCount":2,"chunkCount":6,"observedItemCount":4,"scannedCellCount":120,"totalBytes":52428800,
          "geoRefEventCount":18,"eventLogR2Key":"raw/465773:-718118/env-1700000000000-abc123/events.jsonl.gz",
          "eventCount":312,"gateFlipCount":5,"tapCount":9,"confirmCount":4,"rejectCount":1,"raycastMissCount":2,
          "detectionCount":11,"uploadAttemptCount":6,"uploadFailureCount":1,"errorCount":2,
          "indexedAt":"2026-07-12T14:30:00.000Z","redFlags":["gate-flapped","upload-failures"]}],
         "success":true,"message":null}
        """.trimIndent()
        val decoded: ApiResponse<List<AdminScanSessionSummary>> = GccJson.decodeFromString(body)
        val row = decoded.data.single()
        assertEquals("env-1700000000000-abc123", row.sessionId)
        assertEquals(5, row.gateFlipCount)
        assertEquals(listOf("gate-flapped", "upload-failures"), row.redFlags)
    }

    @Test
    fun detailResponseDecodesAndSummaryIgnoresDetailOnlyColumns() {
        val body = """
        {"data":{
          "session":{"id":7,"sessionId":"env-1700000000000-abc123","deviceId":"d1","platform":"ios",
            "lapKind":"clean","photoCount":0,"chunkCount":3,"observedItemCount":1,"scannedCellCount":80,
            "manifestR2Key":"raw/465773:-718118/d1/env-1700000000000-abc123-manifest.json",
            "objectCaptureR2Key":null,"reconstructionVersion":null,"worldRegistration":{"method":"least-squares"},
            "registrationStatus":"world","hasObjectCapture":false,"createdAt":"2026-07-12T14:30:00.000Z",
            "redFlags":[]},
          "chunks":[{"id":1,"sessionId":"env-1700000000000-abc123","locationCellId":"465773:-718118",
            "artifactType":"event_log","r2Key":"raw/465773:-718118/env-1700000000000-abc123/events.jsonl.gz",
            "format":"gz","byteSize":48211,"capturedAt":null,"indexedAt":"2026-07-12T14:30:00.000Z",
            "url":"https://r2.example/presigned"}],
          "manifest":{"sessionId":"env-1700000000000-abc123","deviceId":"d1","deviceModel":"iPhone17,1",
            "osVersion":"19.0","startedAtMs":1700000000000,"completedAtMs":1700001200000,"path":[],
            "scannedCells":[],"scannedCellSizeMeters":3.0,"observedItems":[],"photoKeys":[],
            "eventLogKey":"raw/465773:-718118/env-1700000000000-abc123/events.jsonl.gz","eventLogSchema":1},
          "events":[{"t":0,"type":"caption","msg":"Sweep the camera slowly"},{"t":5000,"type":"gate","state":"OUTDOOR"}],
          "presignExpiresInSeconds":300},
         "success":true,"message":null}
        """.trimIndent()
        val decoded: ApiResponse<AdminScanSessionDetail> = GccJson.decodeFromString(body)
        val detail = decoded.data
        assertEquals("clean", detail.session.lapKind)
        assertEquals(1, detail.chunks.size)
        assertEquals("event_log", detail.chunks[0].artifactType)
        assertEquals("https://r2.example/presigned", detail.chunks[0].url)
        assertEquals(2, detail.events?.size)
        assertEquals("OUTDOOR", detail.events?.last()?.state)
        assertEquals(1, detail.manifest?.eventLogSchema)
        assertNull(detail.session.eventCount)
    }
}
