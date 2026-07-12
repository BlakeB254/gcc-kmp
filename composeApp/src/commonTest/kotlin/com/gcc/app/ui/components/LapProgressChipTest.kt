package com.gcc.app.ui.components

import com.gcc.shared.model.HotspotStatus
import com.gcc.shared.model.ScanHotspot
import com.gcc.shared.model.ScanPhase
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LapProgressChipTest {

    @Test
    fun labelsForEachPhase() {
        assertEquals("Lap 1 of 2 · Document", lapProgressLabel(ScanPhase.SCOUTING))
        assertEquals("Lap 1 done · Review", lapProgressLabel(ScanPhase.SCOUT_REVIEW))
        assertEquals("Lap 2 of 2 · Clean", lapProgressLabel(ScanPhase.CLEANING))
        assertNull(lapProgressLabel(ScanPhase.SUBMITTING))
        assertNull(lapProgressLabel(ScanPhase.COMPLETE))
    }

    private fun hotspot(id: Int, status: HotspotStatus) = ScanHotspot(
        id = id,
        scanId = 1,
        lat = 41.86,
        lng = -87.70,
        beforePhotoUrl = "photo-$id",
        detectedAt = Instant.fromEpochMilliseconds(0),
        status = status,
    )

    @Test
    fun noWarningWhenSomethingWasCleaned() {
        val hotspots = listOf(
            hotspot(1, HotspotStatus.CLEANED),
            hotspot(2, HotspotStatus.SKIPPED),
        )
        assertNull(cleanLapCompletionWarning(hotspots))
    }

    @Test
    fun warnsWhenLapOneFoundNothing() {
        val warning = cleanLapCompletionWarning(emptyList())
        assertTrue(warning != null && "no problem areas" in warning)
    }

    @Test
    fun warnsWhenEverythingWasSkipped() {
        val warning = cleanLapCompletionWarning(
            listOf(hotspot(1, HotspotStatus.SKIPPED), hotspot(2, HotspotStatus.SKIPPED)),
        )
        assertTrue(warning != null && "2 problem area(s)" in warning)
    }
}

class LapClockTest {

    @Test
    fun clockFormatsMinutesSeconds() {
        assertEquals("00:00", formatLapClock(0))
        assertEquals("00:59", formatLapClock(59))
        assertEquals("12:34", formatLapClock(754))
        assertEquals("1:00:01", formatLapClock(3601))
    }

    @Test
    fun negativeClampedToZero() {
        assertEquals("00:00", formatLapClock(-5))
    }

    @Test
    fun labelCarriesLapClock() {
        assertEquals(
            "Lap 1 of 2 · Document · 05:00",
            lapProgressLabel(ScanPhase.SCOUTING, lapElapsedSeconds = 300),
        )
        // Review is an interstitial, not a timed lap — no clock.
        assertEquals(
            "Lap 1 done · Review",
            lapProgressLabel(ScanPhase.SCOUT_REVIEW, lapElapsedSeconds = 300),
        )
    }
}
