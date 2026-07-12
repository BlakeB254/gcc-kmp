package com.gcc.app.model

import com.gcc.shared.model.EnvScanManifest
import com.gcc.shared.serialization.GccJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Flight recorder (scan-session-replay PRD Phase 1): EnvScanManifest grew
 * `eventLogKey`/`eventLogSchema` — the R2 key of the session's gzipped JSONL
 * event stream (`…/events.jsonl.gz`) uploaded alongside the manifest. Same
 * additive/optional contract as `diagnosticsLog`: manifests recorded before
 * this field existed (and any queued legacy payload in the retry queue) must
 * still decode.
 */
class EnvScanManifestEventLogTest {

    /** A manifest payload written by a build that predates the event log. */
    private val legacyJson = """
        {"sessionId":"s1","deviceId":"d1","deviceModel":"Pixel","osVersion":"14",
         "startedAtMs":1000,"completedAtMs":2000,"path":[],"scannedCells":[],
         "scannedCellSizeMeters":3.0,"observedItems":[],"photoKeys":[]}
    """.trimIndent()

    @Test
    fun legacyManifestWithoutEventLogStillDecodes() {
        val decoded: EnvScanManifest = GccJson.decodeFromString(legacyJson)
        assertEquals("s1", decoded.sessionId)
        assertNull(decoded.eventLogKey)
        assertNull(decoded.eventLogSchema)
    }

    @Test
    fun eventLogKeyRoundTrips() {
        val manifest = GccJson.decodeFromString<EnvScanManifest>(legacyJson)
            .copy(eventLogKey = "raw/123:-45/s1/events.jsonl.gz", eventLogSchema = 1)
        val reDecoded: EnvScanManifest = GccJson.decodeFromString(GccJson.encodeToString(manifest))
        assertEquals("raw/123:-45/s1/events.jsonl.gz", reDecoded.eventLogKey)
        assertEquals(1, reDecoded.eventLogSchema)
    }
}
