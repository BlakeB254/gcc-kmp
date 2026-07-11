package com.gcc.app.model

import com.gcc.shared.model.ArDetectedMarkerRequest
import com.gcc.shared.serialization.GccJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class ArDetectedMarkerWireCompatTest {

    /** A payload written by a build that predates `provenance`. */
    private val legacyJson = """
        {"lat":41.86,"lng":-87.72,"label":"tire","capturedAt":"2026-07-10T12:00:00Z"}
    """.trimIndent()

    @Test
    fun legacyPayloadWithoutProvenanceStillDecodes() {
        val decoded: ArDetectedMarkerRequest = GccJson.decodeFromString(legacyJson)
        assertEquals("tire", decoded.label)
        assertEquals("auto_detected", decoded.provenance, "legacy markers are automatic by definition")
    }

    @Test
    fun provenanceRoundTrips() {
        val marker = GccJson.decodeFromString<ArDetectedMarkerRequest>(legacyJson)
            .copy(provenance = "user_confirmed")
        val reDecoded: ArDetectedMarkerRequest = GccJson.decodeFromString(GccJson.encodeToString(marker))
        assertEquals("user_confirmed", reDecoded.provenance)
    }
}
