package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 16 follow-up: every EnvScanManifest construction site (SpotViewModel,
 * OrphanSessionRecovery, RouteScanViewModel) attaches the diagnostics tail via
 * [DiagnosticsLog.tailForManifest]. These tests pin the helper's contract on an
 * explicit entries list (the global ring buffer is process-wide singleton
 * state, so the seam keeps the tests deterministic).
 */
class DiagnosticsLogTailTest {

    private fun entry(atMs: Long, message: String) = DiagnosticsLog.Entry(
        atMs = atMs,
        level = DiagnosticsLog.Level.INFO,
        tag = "test",
        message = message,
    )

    @Test
    fun emptyLogYieldsNullSoTheManifestFieldStaysWireNull() {
        assertNull(DiagnosticsLog.tailForManifest(emptyList(), max = 300))
    }

    @Test
    fun tailIsCappedToTheNewestEntriesAndReadsOldestFirst() {
        // The ring buffer is newest-first; the shipped tail must be the most
        // recent `max` entries, reversed to chronological order (newest last).
        val newestFirst = listOf(entry(3000, "c"), entry(2000, "b"), entry(1000, "a"))
        val tail = DiagnosticsLog.tailForManifest(newestFirst, max = 2)
        assertNotNull(tail)
        assertEquals(2, tail.size)
        assertTrue(tail[0].endsWith("[test] b"), "older of the two newest comes first: $tail")
        assertTrue(tail[1].endsWith("[test] c"), "newest entry ships last: $tail")
    }
}
