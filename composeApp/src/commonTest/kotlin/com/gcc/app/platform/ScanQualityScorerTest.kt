package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScanQualityScorerTest {

    @Test
    fun meetsDeepScanQualityRequiresGoodTier() {
        // NORMAL tracking, good GPS, decent confidence → GOOD.
        assertTrue(
            ScanQualityScorer.meetsDeepScanQuality(
                ScanQualitySignal(TrackingState.NORMAL, gpsAccuracyMeters = 5.0, detectionConfidence = 0.8f),
            ),
        )
        // Unsteady tracking with unremarkable GPS/confidence stays below 0.7.
        // (LIMITED=0.5*0.5=0.25 + unknown GPS 0.6*0.3=0.18 + unknown conf
        // 0.7*0.2=0.14 = 0.57 < 0.70.) Note LIMITED alone is NOT enough to
        // drop a scan below GOOD — tracking is only weight 0.5 — so a truly
        // strong scan (maxed GPS+confidence) can still be GOOD at LIMITED.
        assertFalse(
            ScanQualityScorer.meetsDeepScanQuality(ScanQualitySignal(TrackingState.LIMITED)),
        )
        // AR tracking lost tanks it outright.
        assertFalse(
            ScanQualityScorer.meetsDeepScanQuality(
                ScanQualitySignal(TrackingState.NOT_AVAILABLE, gpsAccuracyMeters = 5.0, detectionConfidence = 0.9f),
            ),
        )
    }
}
