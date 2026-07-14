package com.gcc.app.model

import com.gcc.shared.model.GeoRefEvent
import com.gcc.shared.serialization.GccJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GeoRefEventHeadingTest {
    @Test
    fun headingRoundTrips() {
        val event = GeoRefEvent(
            atMs = 1L, lat = 41.85, lng = -87.70, accuracyMeters = 5.0,
            arX = 1f, arY = 2f, arZ = 3f, arTimestampSeconds = 10.5,
            headingDegrees = 271.5, headingAccuracyDegrees = 15.0,
        )
        val decoded = GccJson.decodeFromString(
            GeoRefEvent.serializer(), GccJson.encodeToString(GeoRefEvent.serializer(), event))
        assertEquals(271.5, decoded.headingDegrees)
        assertEquals(15.0, decoded.headingAccuracyDegrees)
    }

    @Test
    fun legacyEventWithoutHeadingStillDecodes() {
        val decoded = GccJson.decodeFromString(
            GeoRefEvent.serializer(),
            """{"atMs":1,"lat":41.0,"lng":-87.0,"arX":0.0,"arY":0.0,"arZ":0.0}""")
        assertNull(decoded.headingDegrees)
        assertNull(decoded.headingAccuracyDegrees)
    }
}
