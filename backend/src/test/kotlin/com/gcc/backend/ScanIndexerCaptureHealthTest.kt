package com.gcc.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `videoCoveragePct` (splat-capture reliability spec, migration 0039): the
 * indexer's headline capture-health ratio, derived from the manifest's
 * `captureHealth.sessionSeconds` / `videoCoveredSeconds`. Must tolerate
 * missing inputs (older manifests, undecodable health JSON), a
 * zero/negative session length (clock skew), and a recorder bug that
 * over-reports coverage past 100%.
 */
class ScanIndexerCaptureHealthTest {

    @Test
    fun computesPercentageWhenBothInputsPresent() {
        assertEquals(50.0, ScanIndexer.videoCoveragePct(sessionSeconds = 100.0, videoCoveredSeconds = 50.0))
        assertEquals(100.0, ScanIndexer.videoCoveragePct(sessionSeconds = 60.0, videoCoveredSeconds = 60.0))
    }

    @Test
    fun nullWhenEitherInputMissing() {
        assertNull(ScanIndexer.videoCoveragePct(sessionSeconds = null, videoCoveredSeconds = 50.0))
        assertNull(ScanIndexer.videoCoveragePct(sessionSeconds = 100.0, videoCoveredSeconds = null))
        assertNull(ScanIndexer.videoCoveragePct(sessionSeconds = null, videoCoveredSeconds = null))
    }

    @Test
    fun nullWhenSessionSecondsIsZeroOrNegative() {
        assertNull(ScanIndexer.videoCoveragePct(sessionSeconds = 0.0, videoCoveredSeconds = 10.0))
        assertNull(ScanIndexer.videoCoveragePct(sessionSeconds = -5.0, videoCoveredSeconds = 10.0))
    }

    @Test
    fun clampsToHundredWhenARecorderBugOverReportsCoverage() {
        // videoCoveredSeconds > sessionSeconds should never happen, but a
        // recorder bug (e.g. double-counted overlapping chunks) must not
        // write an out-of-range percentage into scan_sessions.
        assertEquals(100.0, ScanIndexer.videoCoveragePct(sessionSeconds = 10.0, videoCoveredSeconds = 40.0))
    }

    @Test
    fun clampsToZeroForANegativeRatio() {
        assertEquals(0.0, ScanIndexer.videoCoveragePct(sessionSeconds = 10.0, videoCoveredSeconds = -5.0))
    }
}
