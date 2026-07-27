package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [GroundCandidateTracker] is the pure decision layer of the litter
 * detector: it owns PROPOSED -> SCANNING -> COMPLETED / EXCLUDED /
 * TIMED_OUT lifecycle for [GroundCandidatePacket]s. Every test drives an
 * injected `nowMs` via a mutable `now` var -- no real clocks, no sleeps.
 */
class GroundCandidateTrackerTest {

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

    @Test
    fun ingestCreatesAPendingProposedCandidate() {
        var now = 100L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(1L)))

        val awaiting = tracker.candidatesAwaitingClassification()
        assertEquals(listOf(1L), awaiting.map { it.id })
        assertTrue(tracker.activeScanning().isEmpty())
        assertTrue(tracker.completedCandidates().isEmpty())
    }

    @Test
    fun reObservationRefreshesPositionAndDoesNotDuplicate() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(1L, x = 0f)))
        now = 500
        tracker.ingest(listOf(packet(1L, x = 3f)))

        val awaiting = tracker.candidatesAwaitingClassification()
        assertEquals(1, awaiting.size) // upsert, not a second candidate
        assertEquals(3f, awaiting[0].x)
    }

    @Test
    fun atMostTwoCandidatesAwaitClassificationPerTick() {
        val tracker = GroundCandidateTracker(nowMs = { 0L })
        tracker.ingest(listOf(packet(1L), packet(2L), packet(3L)))
        assertEquals(2, tracker.candidatesAwaitingClassification().size)
    }

    @Test
    fun exclusionLabelSilentlyExcludesAndNeverResurfaces() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(2L, x = 5f, z = 5f)))
        tracker.recordClassification(2L, exclusionLabel = "bench", trashType = null)

        assertTrue(tracker.candidatesAwaitingClassification().none { it.id == 2L })
        assertTrue(tracker.activeScanning().none { it.first == 2L })
        assertTrue(tracker.completedCandidates().none { it.id == 2L })

        // Even a dead-on, long dwell must not resurrect an EXCLUDED candidate --
        // the dwell gaze search only ever looks at PROPOSED candidates.
        val camera = floatArrayOf(5f, 0f, 5f)
        val forward = floatArrayOf(0f, 0f, -1f)
        now = 0
        tracker.recordDwell(camera, forward, now)
        now = 5_000
        tracker.recordDwell(camera, forward, now)
        assertTrue(tracker.activeScanning().none { it.first == 2L })
        assertTrue(tracker.completedCandidates().none { it.id == 2L })
    }

    @Test
    fun secondClassificationOnScanningCandidateCanStillRetroExclude() {
        val tracker = GroundCandidateTracker(nowMs = { 0L })
        tracker.ingest(listOf(packet(3L)))
        tracker.recordClassification(3L, exclusionLabel = null, trashType = "bottle")
        assertTrue(tracker.activeScanning().any { it.first == 3L })

        // Classification runs async and can still name a fixture after the
        // candidate is already SCANNING -- retro-exclude, silently.
        tracker.recordClassification(3L, exclusionLabel = "bench", trashType = null)
        assertTrue(tracker.activeScanning().none { it.first == 3L })
        assertTrue(tracker.completedCandidates().none { it.id == 3L })
    }

    @Test
    fun classificationAfterRealDwellPromotionRetroExcludes() {
        // The genuine dwell-skip path: the candidate reaches SCANNING via
        // recordDwell alone (no recordClassification promoted it), and only
        // THEN does the async classification arrive naming a fixture.
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(3L, x = 0f, y = 0f, z = -1f)))
        val camera = floatArrayOf(0f, 0f, 0f)
        val forward = floatArrayOf(0f, 0f, -1f)

        tracker.recordDwell(camera, forward, now)
        now = 1_900
        tracker.recordDwell(camera, forward, now)
        assertTrue(tracker.activeScanning().any { it.first == 3L }) // promoted by dwell
        // Dwell-promoted and never classified -- it must still be offered up
        // for vetting, otherwise it never gets exclusion-checked at all (the
        // bug this test guards against: a dwelled-on pole base filing as
        // "Unknown litter" instead of being retro-excluded below).
        assertEquals(listOf(3L), tracker.candidatesAwaitingClassification().map { it.id })

        tracker.recordClassification(3L, exclusionLabel = "bench", trashType = null)
        assertTrue(tracker.activeScanning().none { it.first == 3L })
        assertTrue(tracker.completedCandidates().none { it.id == 3L })
        assertTrue(tracker.candidatesAwaitingClassification().none { it.id == 3L }) // terminal now

        // Forever: another long dead-on dwell and more queries change nothing.
        now = 10_000
        tracker.recordDwell(camera, forward, now)
        now = 15_000
        tracker.recordDwell(camera, forward, now)
        assertTrue(tracker.activeScanning().none { it.first == 3L })
        assertTrue(tracker.completedCandidates().none { it.id == 3L })
        assertTrue(tracker.candidatesAwaitingClassification().none { it.id == 3L })
    }

    @Test
    fun dwellPromotedCandidateStillAwaitsClassificationUntilClassified() {
        // Same dwell-promotion setup as classificationAfterRealDwellPromotionRetroExcludes,
        // but this time classification names a real trash type instead of
        // excluding -- candidatesAwaitingClassification must stop offering
        // the id up the instant it's classified, exclusion or not.
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(3L, x = 0f, y = 0f, z = -1f)))
        val camera = floatArrayOf(0f, 0f, 0f)
        val forward = floatArrayOf(0f, 0f, -1f)

        tracker.recordDwell(camera, forward, now)
        now = 1_900
        tracker.recordDwell(camera, forward, now)
        assertTrue(tracker.activeScanning().any { it.first == 3L })
        assertEquals(listOf(3L), tracker.candidatesAwaitingClassification().map { it.id })

        tracker.recordClassification(3L, exclusionLabel = null, trashType = "bottle")
        assertTrue(tracker.candidatesAwaitingClassification().none { it.id == 3L })
        assertTrue(tracker.activeScanning().any { it.first == 3L }) // still SCANNING, just typed now
    }

    @Test
    fun classifyingDwellPromotedCandidateTypesItWithoutResettingProgress() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(3L, x = 0f, y = 0f, z = 0f, distanceM = 5f)))
        val camera = floatArrayOf(0f, 0f, 1f)
        val forward = floatArrayOf(0f, 0f, -1f)

        // Dwell-promote (no classification involved yet).
        tracker.recordDwell(camera, forward, now)
        now = 1_900
        tracker.recordDwell(camera, forward, now)
        assertTrue(tracker.activeScanning().any { it.first == 3L })
        val progressAfterPromotion = tracker.progress(3L)

        // Classification arrives late, naming a real trash type -- must type
        // the candidate without disturbing anything accrued so far (no reset
        // of angle buckets / close frames / observations / state).
        tracker.recordClassification(3L, exclusionLabel = null, trashType = "bottle")
        assertEquals(progressAfterPromotion, tracker.progress(3L))
        assertTrue(tracker.activeScanning().any { it.first == 3L })

        // Drive it the rest of the way to completion and confirm the type stuck.
        repeat(6) {
            now += 100
            tracker.ingest(listOf(packet(3L, x = 0f, y = 0f, z = 0f, distanceM = 1.0f)))
        }
        tracker.recordDwell(floatArrayOf(0f, 0f, 3f), floatArrayOf(0f, 0f, -1f), now)
        tracker.recordDwell(floatArrayOf(3f, 0f, 0f), floatArrayOf(0f, 0f, -1f), now)
        tracker.recordDwell(floatArrayOf(0f, 0f, -3f), floatArrayOf(0f, 0f, -1f), now)

        val completed = tracker.completedCandidates()
        assertEquals(1, completed.size)
        assertEquals("bottle", completed[0].itemType)
    }

    @Test
    fun classificationPromotedCandidateNeverReappearsInAwaitingClassification() {
        // The ordinary (non-dwell) path: recordClassification promotes a
        // PROPOSED candidate straight to SCANNING with a type. The new
        // classified flag must keep it out of the SCANNING-unclassified
        // half of the query too, or it would look indistinguishable from a
        // genuinely-unvetted dwell promotion and get re-offered forever.
        val tracker = GroundCandidateTracker(nowMs = { 0L })
        tracker.ingest(listOf(packet(4L)))
        assertEquals(listOf(4L), tracker.candidatesAwaitingClassification().map { it.id })

        tracker.recordClassification(4L, exclusionLabel = null, trashType = "bottle")
        assertTrue(tracker.activeScanning().any { it.first == 4L })
        assertTrue(tracker.candidatesAwaitingClassification().none { it.id == 4L })
    }

    @Test
    fun awaitingClassificationPrioritizesProposedOverDwellPromotedScanning() {
        // Ordering/cap contract: PROPOSED candidates (the common case) are
        // served before unclassified SCANNING ones, still capped at
        // MAX_CLASSIFICATION_REQUESTS_PER_TICK (2) total per call.
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(1L, x = 0f, y = 0f, z = -1f)))
        val camera = floatArrayOf(0f, 0f, 0f)
        val forward = floatArrayOf(0f, 0f, -1f)
        tracker.recordDwell(camera, forward, now)
        now = 1_900
        tracker.recordDwell(camera, forward, now)
        assertTrue(tracker.activeScanning().any { it.first == 1L }) // dwell-promoted, unclassified

        tracker.ingest(listOf(packet(2L, x = 10f), packet(3L, x = 20f))) // still PROPOSED

        val awaiting = tracker.candidatesAwaitingClassification()
        assertEquals(listOf(2L, 3L), awaiting.map { it.id }) // PROPOSED first, FIFO, capped at 2
    }

    @Test
    fun reIngestingAnExcludedIdDoesNotResurrectIt() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(2L)))
        tracker.recordClassification(2L, exclusionLabel = "bench", trashType = null)

        // Swift keeps draining the same physical protrusion every frame -- the
        // id comes back through ingest, but EXCLUDED is terminal: the upsert
        // must drop it, not recreate a PROPOSED candidate.
        now = 1_000
        tracker.ingest(listOf(packet(2L, distanceM = 1.0f)))

        assertTrue(tracker.candidatesAwaitingClassification().none { it.id == 2L })
        assertTrue(tracker.activeScanning().none { it.first == 2L })
        assertTrue(tracker.completedCandidates().none { it.id == 2L })
    }

    @Test
    fun dwellUnderPromoteThresholdDoesNotPromote() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(1L, x = 0f, y = 0f, z = -1f)))
        val camera = floatArrayOf(0f, 0f, 0f)
        val forward = floatArrayOf(0f, 0f, -1f)

        tracker.recordDwell(camera, forward, now)
        now = 1_700
        tracker.recordDwell(camera, forward, now)

        assertTrue(tracker.activeScanning().isEmpty())
        assertEquals(listOf(1L), tracker.candidatesAwaitingClassification().map { it.id })
    }

    @Test
    fun dwellAtOrOverPromoteThresholdPromotesToScanning() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(1L, x = 0f, y = 0f, z = -1f)))
        val camera = floatArrayOf(0f, 0f, 0f)
        val forward = floatArrayOf(0f, 0f, -1f)

        tracker.recordDwell(camera, forward, now)
        now = 1_900
        tracker.recordDwell(camera, forward, now)

        assertEquals(listOf(1L), tracker.activeScanning().map { it.first })
        // Dwell skips the classification-WAIT (straight to SCANNING without
        // sitting in the PROPOSED queue), but it must NOT skip classification
        // itself -- the candidate still needs retro-vetting, or a dwelled-on
        // pole/bench base would file as "Unknown litter" instead of getting
        // excluded. See classificationAfterRealDwellPromotionRetroExcludes.
        assertEquals(listOf(1L), tracker.candidatesAwaitingClassification().map { it.id })
    }

    @Test
    fun dwellResetsWhenGazeMovesToADifferentCandidate() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(1L, x = 0f, z = -1f), packet(2L, x = 5f, z = -1f)))
        val forward = floatArrayOf(0f, 0f, -1f)

        tracker.recordDwell(floatArrayOf(0f, 0f, 0f), forward, now) // gazing at 1L
        now = 1_000
        tracker.recordDwell(floatArrayOf(5f, 0f, 0f), forward, now) // gaze moved to 2L, resets 1L's accumulator
        now = 1_900
        tracker.recordDwell(floatArrayOf(5f, 0f, 0f), forward, now) // only 0.9s on 2L since it restarted

        assertTrue(tracker.activeScanning().isEmpty())
    }

    @Test
    fun candidateOutsideDwellGatesNeverPromotes() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        // 3m away -- past DWELL_MAX_METERS (2.0).
        tracker.ingest(listOf(packet(1L, x = 0f, z = -3f)))
        val camera = floatArrayOf(0f, 0f, 0f)
        val forward = floatArrayOf(0f, 0f, -1f)

        tracker.recordDwell(camera, forward, now)
        now = 5_000
        tracker.recordDwell(camera, forward, now)

        assertTrue(tracker.activeScanning().isEmpty())
    }

    @Test
    fun adoptTapPromotesNearestCandidateWithinOneMeter() {
        val tracker = GroundCandidateTracker(nowMs = { 0L })
        tracker.ingest(listOf(packet(9L, x = 2f, z = 2f, distanceM = 3f)))

        val adopted = tracker.adoptTap(2f, 0f, 2f)
        assertEquals(9L, adopted)
        assertTrue(tracker.activeScanning().any { it.first == 9L })
    }

    @Test
    fun adoptTapOutsideRangeReturnsNull() {
        val tracker = GroundCandidateTracker(nowMs = { 0L })
        tracker.ingest(listOf(packet(9L, x = 2f, z = 2f)))
        assertNull(tracker.adoptTap(50f, 0f, 50f))
    }

    @Test
    fun progressComposesAngleBucketsCloseFramesAndObservations() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        // Candidate sits at the origin; classify immediately so it's SCANNING
        // (angle-bucket accrual in recordDwell only applies to SCANNING
        // candidates -- see the constraint comment on GroundCandidateTracker).
        tracker.ingest(listOf(packet(1L, x = 0f, y = 0f, z = 0f, distanceM = 5f)))
        tracker.recordClassification(1L, exclusionLabel = null, trashType = "bottle")

        // 6 close re-observations (distanceM < 2.0): closeFrames caps its
        // term at 4, observations (initial + 6) caps its term at 6.
        repeat(6) {
            now += 100
            tracker.ingest(listOf(packet(1L, x = 0f, y = 0f, z = 0f, distanceM = 1.0f)))
        }

        // 3 distinct 60-degree bearing sectors around the candidate, all
        // within the 6m angle-bucket radius.
        tracker.recordDwell(floatArrayOf(0f, 0f, 3f), floatArrayOf(0f, 0f, -1f), now) // bearing 0deg
        tracker.recordDwell(floatArrayOf(3f, 0f, 0f), floatArrayOf(0f, 0f, -1f), now) // bearing 90deg
        tracker.recordDwell(floatArrayOf(0f, 0f, -3f), floatArrayOf(0f, 0f, -1f), now) // bearing 180deg

        val progress = tracker.progress(1L)
        assertTrue(progress >= 0.999f, "expected progress ~1.0, was $progress")
    }

    @Test
    fun completedCandidatesEmitExactlyOnceThenDisappearEverywhere() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(1L, x = 0f, y = 0f, z = 0f, distanceM = 5f)))
        tracker.recordClassification(1L, exclusionLabel = null, trashType = "bottle")
        repeat(6) {
            now += 100
            tracker.ingest(listOf(packet(1L, x = 0f, y = 0f, z = 0f, distanceM = 1.0f)))
        }
        tracker.recordDwell(floatArrayOf(0f, 0f, 3f), floatArrayOf(0f, 0f, -1f), now)
        tracker.recordDwell(floatArrayOf(3f, 0f, 0f), floatArrayOf(0f, 0f, -1f), now)
        tracker.recordDwell(floatArrayOf(0f, 0f, -3f), floatArrayOf(0f, 0f, -1f), now)

        val first = tracker.completedCandidates()
        assertEquals(1, first.size)
        assertEquals(1L, first[0].id)
        assertEquals("bottle", first[0].itemType)

        val second = tracker.completedCandidates()
        assertTrue(second.isEmpty())
        assertTrue(tracker.activeScanning().none { it.first == 1L })
        assertTrue(tracker.candidatesAwaitingClassification().none { it.id == 1L })
    }

    @Test
    fun scanningCandidateTimesOutAfterTwentySecondsOfSilence() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(1L)))
        tracker.recordClassification(1L, exclusionLabel = null, trashType = "bottle")

        now = 19_999
        assertTrue(tracker.timedOut().isEmpty())

        now = 20_000
        assertEquals(listOf(1L), tracker.timedOut())

        // Emit-once, and gone from every other query too.
        now = 25_000
        assertTrue(tracker.timedOut().isEmpty())
        assertTrue(tracker.activeScanning().none { it.first == 1L })
    }

    @Test
    fun reObservationBeforeTimeoutKeepsCandidateAlive() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(1L)))
        tracker.recordClassification(1L, exclusionLabel = null, trashType = "bottle")

        now = 15_000
        tracker.ingest(listOf(packet(1L))) // refreshes lastSeenMs
        now = 30_000
        assertTrue(tracker.timedOut().isEmpty()) // only 15s since last observation
    }

    /** Drive [id] (already SCANNING at the origin) to progress 1.0: six
     *  close re-observations + three distinct approach bearings. */
    private fun driveToFullProgress(tracker: GroundCandidateTracker, id: Long, startMs: Long): Long {
        var now = startMs
        repeat(6) {
            now += 100
            tracker.ingest(listOf(packet(id, x = 0f, y = 0f, z = 0f, distanceM = 1.0f)))
        }
        tracker.recordDwell(floatArrayOf(0f, 0f, 3f), floatArrayOf(0f, 0f, -1f), now)
        tracker.recordDwell(floatArrayOf(3f, 0f, 0f), floatArrayOf(0f, 0f, -1f), now)
        tracker.recordDwell(floatArrayOf(0f, 0f, -3f), floatArrayOf(0f, 0f, -1f), now)
        return now
    }

    // ── Unknown-appearance gating (TestFlight 157 phantom detections) ──
    // Geometry alone must never file: a candidate whose classification named
    // neither a fixture (exclusion) nor a trash type is a ground bump, not
    // litter — unless the USER tapped it (intent overrides).

    @Test
    fun unknownClassifiedCandidateAwaitsIntentThenDismissesOnTimeout() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(1L, x = 0f, y = 0f, z = 0f, distanceM = 5f)))
        // Classification ran and matched nothing either way.
        tracker.recordClassification(1L, exclusionLabel = null, trashType = null)
        now = driveToFullProgress(tracker, 1L, now)

        assertTrue(tracker.completedCandidates().isEmpty(), "unknown appearance must not auto-file")
        // Stage 1: not silently dismissed — parked awaiting the user's answer,
        // reticle still rendered as the prompt's visual anchor.
        assertTrue(tracker.dismissedUnknown().isEmpty())
        assertEquals(listOf(1L), tracker.awaitingIntent())
        assertTrue(tracker.activeScanning().any { it.first == 1L })

        // Stage 2: the user ignored the prompt — parked after the timeout
        // (id returned once so the caller clears the reticle/prompt; the
        // candidate itself stays tap-adoptable, see GroundCandidateRebuildTest).
        now += (INTENT_PROMPT_TIMEOUT_SECONDS * 1000).toLong() + 1
        assertEquals(listOf(1L), tracker.dismissedUnknown())
        // Emit-once, gone from every visible query.
        assertTrue(tracker.dismissedUnknown().isEmpty())
        assertTrue(tracker.awaitingIntent().isEmpty())
        assertTrue(tracker.activeScanning().none { it.first == 1L })
        assertTrue(tracker.completedCandidates().isEmpty())
    }

    @Test
    fun parkedIdStaysQuietOnReObservation() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(1L, x = 0f, y = 0f, z = 0f, distanceM = 5f)))
        tracker.recordClassification(1L, exclusionLabel = null, trashType = null)
        now = driveToFullProgress(tracker, 1L, now)
        assertTrue(tracker.dismissedUnknown().isEmpty()) // parked AWAITING_INTENT
        now += (INTENT_PROMPT_TIMEOUT_SECONDS * 1000).toLong() + 1
        assertEquals(listOf(1L), tracker.dismissedUnknown())

        now += 1_000
        tracker.ingest(listOf(packet(1L, x = 0f, y = 0f, z = 0f, distanceM = 1.0f)))
        assertTrue(tracker.candidatesAwaitingClassification().none { it.id == 1L })
        assertTrue(tracker.activeScanning().none { it.first == 1L })
        assertTrue(tracker.dismissedUnknown().isEmpty())
    }

    @Test
    fun confirmIntentCompletesAsUnknownLitterWithUserIntent() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(1L, x = 0f, y = 0f, z = 0f, distanceM = 5f)))
        tracker.recordClassification(1L, exclusionLabel = null, trashType = null)
        now = driveToFullProgress(tracker, 1L, now)
        assertTrue(tracker.dismissedUnknown().isEmpty())
        assertEquals(listOf(1L), tracker.awaitingIntent())

        assertTrue(tracker.confirmIntent(1L))
        val completed = tracker.completedCandidates()
        assertEquals(listOf(1L), completed.map { it.id })
        assertEquals(UNKNOWN_LITTER_LABEL, completed[0].itemType)
        assertTrue(completed[0].userIntent, "prompt confirm is explicit user intent")
        assertTrue(tracker.awaitingIntent().isEmpty())
        // Answered — the timeout reaper must never re-emit it.
        now += (INTENT_PROMPT_TIMEOUT_SECONDS * 1000).toLong() + 1
        assertTrue(tracker.dismissedUnknown().isEmpty())
    }

    @Test
    fun rejectIntentDismissesImmediatelyWithoutReEmission() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(1L, x = 0f, y = 0f, z = 0f, distanceM = 5f)))
        tracker.recordClassification(1L, exclusionLabel = null, trashType = null)
        now = driveToFullProgress(tracker, 1L, now)
        assertTrue(tracker.dismissedUnknown().isEmpty())

        assertTrue(tracker.rejectIntent(1L))
        assertTrue(tracker.awaitingIntent().isEmpty())
        assertTrue(tracker.activeScanning().none { it.first == 1L })
        // The caller already cleared the reticle at reject time — the sweep
        // must not hand the id back a second time.
        now += (INTENT_PROMPT_TIMEOUT_SECONDS * 1000).toLong() + 1
        assertTrue(tracker.dismissedUnknown().isEmpty())
        assertTrue(tracker.completedCandidates().isEmpty())
    }

    @Test
    fun worldTapOnAwaitingIntentCandidateAdoptsAndCompletes() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(1L, x = 0f, y = 0f, z = 0f, distanceM = 5f)))
        tracker.recordClassification(1L, exclusionLabel = null, trashType = null)
        now = driveToFullProgress(tracker, 1L, now)
        assertTrue(tracker.dismissedUnknown().isEmpty())
        assertEquals(listOf(1L), tracker.awaitingIntent())

        assertEquals(1L, tracker.adoptTap(0f, 0f, 0f))
        val completed = tracker.completedCandidates()
        assertEquals(listOf(1L), completed.map { it.id })
        assertTrue(completed[0].userIntent)
    }

    @Test
    fun autonomousTypedCompletionCarriesNoUserIntent() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(1L, x = 0f, y = 0f, z = 0f, distanceM = 5f)))
        tracker.recordClassification(1L, exclusionLabel = null, trashType = "bottle")
        now = driveToFullProgress(tracker, 1L, now)

        val completed = tracker.completedCandidates()
        assertEquals(listOf(1L), completed.map { it.id })
        assertEquals(false, completed[0].userIntent)
    }

    @Test
    fun unclassifiedCandidateAtFullProgressIsHeldUntilClassified() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        // Dwell-promote straight to SCANNING — classification hasn't run yet.
        tracker.ingest(listOf(packet(1L, x = 0f, y = 0f, z = -1f)))
        tracker.recordDwell(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, -1f), now)
        now = 1_900
        tracker.recordDwell(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, -1f), now)
        assertEquals(listOf(1L), tracker.activeScanning().map { it.first })
        // Re-observations move it back to the origin used by driveToFullProgress.
        now = driveToFullProgress(tracker, 1L, now)

        // Full progress but unvetted: neither completed nor dismissed — held.
        assertTrue(tracker.completedCandidates().isEmpty())
        assertTrue(tracker.dismissedUnknown().isEmpty())
        assertEquals(listOf(1L), tracker.activeScanning().map { it.first })

        // Vetting arrives with a trash type: completes normally.
        tracker.recordClassification(1L, exclusionLabel = null, trashType = "bottle")
        val completed = tracker.completedCandidates()
        assertEquals(listOf(1L), completed.map { it.id })
        assertEquals("bottle", completed[0].itemType)
    }

    @Test
    fun tapAdoptedUnknownStillCompletesAsUnknownLitter() {
        var now = 0L
        val tracker = GroundCandidateTracker(nowMs = { now })
        tracker.ingest(listOf(packet(1L, x = 0f, y = 0f, z = 0f, distanceM = 5f)))
        // Intent overrides: the user tapped it.
        assertEquals(1L, tracker.adoptTap(0f, 0f, 0f))
        tracker.recordClassification(1L, exclusionLabel = null, trashType = null)
        now = driveToFullProgress(tracker, 1L, now)

        assertTrue(tracker.dismissedUnknown().isEmpty(), "tapped candidates are never unknown-dismissed")
        val completed = tracker.completedCandidates()
        assertEquals(listOf(1L), completed.map { it.id })
        assertEquals(UNKNOWN_LITTER_LABEL, completed[0].itemType)
    }
}
