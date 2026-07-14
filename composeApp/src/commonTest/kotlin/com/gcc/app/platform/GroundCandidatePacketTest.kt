package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [parseGroundCandidatePacket] is the Kotlin-side half of the
 * GroundAnomalyDetector packet contract (see `GroundAnomalyDetector.swift`
 * and `ArNativeSurface.drainGroundCandidates`) — pure string parsing, no
 * platform dependency, so it lives in commonMain and is fully covered here.
 */
class GroundCandidatePacketTest {

    @Test
    fun parsesAWellFormedPacket() {
        val packet = parseGroundCandidatePacket("42|1.0|0.1|2.0|0.2|0.15|0.2|2.3|0.5|0.6|0.05|0.04")
        assertEquals(
            GroundCandidatePacket(
                id = 42L,
                x = 1.0f,
                y = 0.1f,
                z = 2.0f,
                widthM = 0.2f,
                heightM = 0.15f,
                depthM = 0.2f,
                distanceM = 2.3f,
                imageCx = 0.5,
                imageCy = 0.6,
                imageHalfW = 0.05,
                imageHalfH = 0.04,
            ),
            packet,
        )
    }

    @Test
    fun wrongFieldCountReturnsNull() {
        // 11 fields — missing imageHalfH.
        assertNull(parseGroundCandidatePacket("42|1.0|0.1|2.0|0.2|0.15|0.2|2.3|0.5|0.6|0.05"))
    }

    @Test
    fun nonNumericFieldReturnsNull() {
        assertNull(parseGroundCandidatePacket("42|x|0.1|2.0|0.2|0.15|0.2|2.3|0.5|0.6|0.05|0.04"))
    }

    @Test
    fun nonNumericIdReturnsNull() {
        assertNull(parseGroundCandidatePacket("abc|1.0|0.1|2.0|0.2|0.15|0.2|2.3|0.5|0.6|0.05|0.04"))
    }

    @Test
    fun emptyStringReturnsNull() {
        assertNull(parseGroundCandidatePacket(""))
    }
}
