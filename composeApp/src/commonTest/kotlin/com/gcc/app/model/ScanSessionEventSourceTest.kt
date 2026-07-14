package com.gcc.app.model

import com.gcc.shared.model.ScanEventJson
import com.gcc.shared.model.ScanSessionEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * `detection` events grew `source` (geometry|dwell|tap|vision) and `outcome`
 * (accepted|excluded:<label>|timeout) so later ground-candidate classifiers
 * can be told apart in the flight-recorder stream. Same additive/optional
 * contract as the rest of [ScanSessionEvent]: legacy lines recorded before
 * these fields existed must still decode.
 */
class ScanSessionEventSourceTest {

    @Test
    fun sourceAndOutcomeRoundTripThroughScanEventJson() {
        val event = ScanSessionEvent(
            t = 1_000L,
            type = "detection",
            label = "bottle",
            source = "geometry",
            outcome = "accepted",
        )
        val line = ScanEventJson.encodeToString(event)
        val decoded = ScanEventJson.decodeFromString<ScanSessionEvent>(line)
        assertEquals("geometry", decoded.source)
        assertEquals("accepted", decoded.outcome)
    }

    @Test
    fun legacyDetectionJsonWithoutSourceOrOutcomeStillDecodes() {
        val legacyJson = """{"t":1000,"type":"detection","label":"bottle"}"""
        val decoded = ScanEventJson.decodeFromString<ScanSessionEvent>(legacyJson)
        assertEquals("bottle", decoded.label)
        assertNull(decoded.source)
        assertNull(decoded.outcome)
    }
}
