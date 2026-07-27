package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Build-164 identification rebuild: backdrop-natural softness, PARKED intent
 * prompts, tap resurrection, and TIMED_OUT re-proposal. These are the
 * behaviors that turn "geometry proposes, classification disposes" into
 * "geometry proposes, only a confident fixture label disposes" — the
 * 2026-07-25 field walk identified 1 of ~50 visible items because backdrop
 * labels (grass/plant) terminally excluded candidates and ignored prompts
 * died silently.
 */
class GroundCandidateRebuildTest {

    private fun packet(
        id: Long,
        x: Float = 0f,
        y: Float = 0f,
        z: Float = 0f,
        distanceM: Float = 1.0f,
    ) = GroundCandidatePacket(
        id = id, x = x, y = y, z = z,
        widthM = 0.2f, heightM = 0.2f, depthM = 0.2f,
        distanceM = distanceM,
        imageCx = 0.5, imageCy = 0.5, imageHalfW = 0.05, imageHalfH = 0.05,
    )

    /** Re-observe [id] [times] times at close range so closeFrames accrues. */
    private fun GroundCandidateTracker.observeClose(id: Long, times: Int) {
        repeat(times) { ingest(listOf(packet(id, distanceM = 1.0f))) }
    }

    // ── Backdrop-natural: alive but attention-gated ──

    @Test
    fun backdropClassificationKeepsCandidateAliveButQuiet() {
        val tracker = GroundCandidateTracker(nowMs = { 0L })
        tracker.ingest(listOf(packet(1L)))
        tracker.recordClassification(1L, exclusionLabel = null, trashType = null, backdropLabel = "grass")

        // Not excluded — but no reticle either: vegetation matches most of
        // any lot, and a quiet candidate must not draw attention on its own.
        assertTrue(tracker.activeScanning().isEmpty())
        // Classified: not re-offered to the classifier queue.
        assertTrue(tracker.candidatesAwaitingClassification().isEmpty())
    }

    @Test
    fun backdropCandidateSurfacesAfterSteadyCloseExamination() {
        val tracker = GroundCandidateTracker(nowMs = { 0L })
        tracker.ingest(listOf(packet(1L)))
        tracker.recordClassification(1L, null, null, backdropLabel = "grass")

        tracker.observeClose(1L, times = 9)
        assertTrue(tracker.activeScanning().isEmpty(), "9 close frames — still quiet")

        tracker.observeClose(1L, times = 1)
        assertEquals(listOf(1L), tracker.activeScanning().map { it.first }, "10th close frame surfaces it")
    }

    @Test
    fun backdropCandidateIsImmediatelyVisibleOnceTapped() {
        val tracker = GroundCandidateTracker(nowMs = { 0L })
        tracker.ingest(listOf(packet(1L)))
        tracker.recordClassification(1L, null, null, backdropLabel = "plant")
        assertTrue(tracker.activeScanning().isEmpty())

        assertEquals(1L, tracker.adoptTap(0f, 0f, 0f))
        assertEquals(listOf(1L), tracker.activeScanning().map { it.first })
    }

    @Test
    fun trashTypeClearsBackdropQuietness() {
        val tracker = GroundCandidateTracker(nowMs = { 0L })
        tracker.ingest(listOf(packet(1L)))
        tracker.recordClassification(1L, null, null, backdropLabel = "grass")
        assertTrue(tracker.activeScanning().isEmpty())

        // A later crop pass names the item (user walked closer, crop got
        // tighter): the candidate goes fully visible.
        tracker.recordClassification(1L, exclusionLabel = null, trashType = "bottle", backdropLabel = null)
        assertEquals(listOf(1L), tracker.activeScanning().map { it.first })
    }

    @Test
    fun quietBackdropCandidateNeverPrompts() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(1L)))
        tracker.recordClassification(1L, null, null, backdropLabel = "grass")
        // Full progress via orbit-free steady path is impossible while quiet
        // (quiet ends at 10 close frames), so drive observations only: the
        // candidate can reach high observation counts but must never reach
        // AWAITING_INTENT while quiet.
        repeat(8) { tracker.ingest(listOf(packet(1L, distanceM = 3.0f))) }
        assertTrue(tracker.dismissedUnknown().isEmpty())
        assertTrue(tracker.awaitingIntent().isEmpty())
    }

    // ── PARKED: ignored prompts stay tappable ──

    @Test
    fun ignoredIntentPromptParksAndTapResurrects() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(1L)))
        tracker.recordClassification(1L, null, null) // appearance-unknown, no backdrop
        tracker.observeClose(1L, times = 10) // steady full progress

        assertTrue(tracker.dismissedUnknown().isEmpty(), "stage 1: moves to AWAITING_INTENT, returns nothing")
        assertEquals(listOf(1L), tracker.awaitingIntent())

        now += (INTENT_PROMPT_TIMEOUT_SECONDS * 1000).toLong() + 1
        assertEquals(listOf(1L), tracker.dismissedUnknown(), "stage 2: parks, id returned once for reticle clear")
        assertTrue(tracker.awaitingIntent().isEmpty())
        assertTrue(tracker.activeScanning().isEmpty())

        // Walk back and tap: the candidate resurrects with user intent and
        // completes as unknown litter.
        assertEquals(1L, tracker.adoptTap(0f, 0f, 0f))
        val completed = tracker.completedCandidates()
        assertEquals(1, completed.size)
        assertEquals(UNKNOWN_LITTER_LABEL, completed[0].itemType)
        assertTrue(completed[0].userIntent)
    }

    @Test
    fun parkedCandidateKeepsGeometryFresh() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(1L, x = 0f)))
        tracker.recordClassification(1L, null, null)
        tracker.observeClose(1L, times = 10)
        tracker.dismissedUnknown()
        now += (INTENT_PROMPT_TIMEOUT_SECONDS * 1000).toLong() + 1
        tracker.dismissedUnknown() // parked

        // Geometry refresh while parked: adoptTap must match the NEW position.
        tracker.ingest(listOf(packet(1L, x = 30f)))
        assertNull(tracker.adoptTap(0f, 0f, 0f), "old position no longer adoptable")
        assertEquals(1L, tracker.adoptTap(30f, 0f, 0f))
    }

    @Test
    fun explicitNotLitterAnswerStaysTerminal() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(1L)))
        tracker.recordClassification(1L, null, null)
        tracker.observeClose(1L, times = 10)
        tracker.dismissedUnknown()
        assertTrue(tracker.rejectIntent(1L))

        // The user SAID it isn't litter: no resurrection, ever.
        assertNull(tracker.adoptTap(0f, 0f, 0f))
        tracker.ingest(listOf(packet(1L)))
        assertTrue(tracker.candidatesAwaitingClassification().isEmpty())
    }

    // ── Tap adoption radius ──

    @Test
    fun tapAdoptsWithinWidenedRadius() {
        val tracker = GroundCandidateTracker(nowMs = { 0L })
        tracker.ingest(listOf(packet(1L, x = 0f)))
        assertEquals(1L, tracker.adoptTap(1.4f, 0f, 0f))
    }

    @Test
    fun tapBeyondRadiusAdoptsNothing() {
        val tracker = GroundCandidateTracker(nowMs = { 0L })
        tracker.ingest(listOf(packet(1L, x = 0f)))
        assertNull(tracker.adoptTap(1.6f, 0f, 0f))
    }

    // ── Long-session map bounding ──

    @Test
    fun terminalGhostsAreSweptButUserDecisionsAndFilingsSurvive() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        // One explicit "not litter" answer — must never be swept (a sweep
        // would let the item re-prompt).
        tracker.ingest(listOf(packet(9_999L)))
        tracker.recordClassification(9_999L, null, null)
        tracker.observeClose(9_999L, times = 10)
        tracker.dismissedUnknown() // -> AWAITING_INTENT
        assertTrue(tracker.rejectIntent(9_999L))

        // Flood the tracker with candidates that all time out.
        for (id in 1L..500L) tracker.ingest(listOf(packet(id, x = id.toFloat())))
        now += 21_000
        tracker.timedOut()

        // Keep ingesting fresh ids past the cap — the sweep must bound the
        // map by evicting timed-out ghosts...
        for (id in 1_000L..1_100L) tracker.ingest(listOf(packet(id, x = id.toFloat())))

        // ...which makes an old timed-out id behave exactly like a brand-new
        // proposal on re-observation (same as resurrection), while the
        // explicit rejection stays terminal.
        tracker.ingest(listOf(packet(9_999L)))
        assertTrue(tracker.candidatesAwaitingClassification().none { it.id == 9_999L })
    }

    // ── TIMED_OUT resurrection ──

    @Test
    fun timedOutCandidateRestartsFreshOnReObservation() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(1L)))
        tracker.recordClassification(1L, null, trashType = "bottle")
        now += 21_000
        assertEquals(listOf(1L), tracker.timedOut())

        // Walk back: same spatial-hash id re-observed → fresh PROPOSED, so
        // it re-enters the classification queue (old evidence is stale).
        tracker.ingest(listOf(packet(1L)))
        assertEquals(listOf(1L), tracker.candidatesAwaitingClassification().map { it.id })
        val adopted = tracker.adoptTap(0f, 0f, 0f)
        assertNotNull(adopted)
    }
}
