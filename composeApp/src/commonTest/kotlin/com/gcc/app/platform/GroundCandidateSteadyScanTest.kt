package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Field report (TestFlight 162): a worker standing over a clear piece of
 * trash, camera held on it, watched the scan reticle stall forever — the old
 * progress formula REQUIRED approaching from 3 of 6 bearing sectors, capping
 * a single-bearing scan at ~0.73. Two relaxations under test:
 *
 *  1. Steady-scan substitution: sustained close observation (10 close frames
 *     under 2m) substitutes for the orbit term, so stand-and-scan completes.
 *  2. Tap-adopted completion: explicit user intent lowers the completion bar
 *     to 0.6 — a tapped item files without demanding the full autonomous
 *     evidence bar.
 */
class GroundCandidateSteadyScanTest {

    private fun packet(
        id: Long,
        distanceM: Float = 1.0f,
    ) = GroundCandidatePacket(
        id = id, x = 0f, y = 0f, z = 0f,
        widthM = 0.2f, heightM = 0.2f, depthM = 0.2f,
        distanceM = distanceM,
        imageCx = 0.5, imageCy = 0.5, imageHalfW = 0.05, imageHalfH = 0.05,
    )

    @Test
    fun steadyCloseScanningCompletesWithoutOrbiting() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(1L)))
        tracker.recordClassification(1L, exclusionLabel = null, trashType = "bottle")

        // 10 close re-observations from ONE spot — no recordDwell calls at
        // all, so zero approach-bearing sectors accrue.
        repeat(10) {
            now += 500
            tracker.ingest(listOf(packet(1L, distanceM = 1.0f)))
        }

        val completed = tracker.completedCandidates()
        assertEquals(listOf(1L), completed.map { it.id })
        assertEquals("bottle", completed.first().itemType)
    }

    @Test
    fun singleBearingScanStillCompletesViaOrbitPathToo() {
        // The original fast path (3 sectors + 6 close frames) must keep
        // working exactly as before.
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(1L)))
        tracker.recordClassification(1L, exclusionLabel = null, trashType = "can")
        repeat(6) {
            now += 100
            tracker.ingest(listOf(packet(1L)))
        }
        tracker.recordDwell(floatArrayOf(0f, 0f, 3f), floatArrayOf(0f, 0f, -1f), now)
        tracker.recordDwell(floatArrayOf(3f, 0f, 0f), floatArrayOf(0f, 0f, -1f), now)
        tracker.recordDwell(floatArrayOf(0f, 0f, -3f), floatArrayOf(0f, 0f, -1f), now)
        assertEquals(listOf(1L), tracker.completedCandidates().map { it.id })
    }

    @Test
    fun fewCloseFramesFromOneBearingStillDoesNotComplete() {
        // 4 close frames, no orbit: closeTerm saturates but neither the
        // orbit nor the steady substitution is earned — geometry alone from
        // a glance must still not file.
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(1L)))
        tracker.recordClassification(1L, exclusionLabel = null, trashType = "bottle")
        repeat(4) {
            now += 100
            tracker.ingest(listOf(packet(1L)))
        }
        assertTrue(tracker.completedCandidates().isEmpty())
    }

    @Test
    fun tapAdoptedCandidateCompletesAtReducedThreshold() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(1L)))
        tracker.recordClassification(1L, exclusionLabel = null, trashType = "bottle")
        // A handful of close frames — well short of the full autonomous bar
        // (progress ≈ 0.4·0 + 0.4·1 + 0.2·(5/6) ≈ 0.57 pre-tap terms).
        repeat(4) {
            now += 100
            tracker.ingest(listOf(packet(1L)))
        }
        assertTrue(tracker.completedCandidates().isEmpty(), "not tapped yet — must hold")

        tracker.adoptTap(0f, 0f, 0f)
        now += 100
        tracker.ingest(listOf(packet(1L))) // one more observation nudges past 0.6

        val completed = tracker.completedCandidates()
        assertEquals(listOf(1L), completed.map { it.id })
        assertTrue(completed.first().userIntent, "tap adoption must mark user intent")
    }
}
