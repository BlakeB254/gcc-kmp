package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TapCandidateStateTest {

    private val idle = TapCandidateState()

    private fun tappedAndLocated(distance: Float = 2.0f): TapCandidateState =
        idle.reduce(TapEvent.Tapped(0.5f, 0.5f))
            .reduce(TapEvent.RaycastHit(41.86, -87.72, distance))

    @Test
    fun tapMovesToLocating() {
        val state = idle.reduce(TapEvent.Tapped(0.25f, 0.75f))
        assertEquals(TapPhase.LOCATING, state.phase)
        assertEquals(0.25f, state.normalizedX)
        assertEquals(0.75f, state.normalizedY)
    }

    @Test
    fun raycastHitMovesToChecking() {
        val state = tappedAndLocated()
        assertEquals(TapPhase.CHECKING, state.phase)
        assertEquals(41.86, state.lat)
        assertEquals(2.0f, state.distanceMeters)
    }

    @Test
    fun raycastMissAsksTheUserToRetry() {
        val state = idle.reduce(TapEvent.Tapped(0.5f, 0.5f)).reduce(TapEvent.RaycastMissed)
        assertEquals(TapPhase.NO_RAYCAST, state.phase)
        assertEquals("Hold steady and tap the item again", tapPillCopy(state)?.title)
    }

    @Test
    fun noGeoRefTellsTheTruthInsteadOfBlamingSteadiness() {
        // The surface WAS hit — the session just has no AR↔GPS pairing yet.
        // "Hold steady" would be a lie; no amount of steadiness helps.
        val state = idle.reduce(TapEvent.Tapped(0.5f, 0.5f)).reduce(TapEvent.NoGeoRef)
        assertEquals(TapPhase.GPS_SYNCING, state.phase)
        assertEquals("GPS is syncing — try again in a few seconds", tapPillCopy(state)?.title)
    }

    @Test
    fun noGeoRefOutsideLocatingIsIgnored() {
        val state = tappedAndLocated().reduce(TapEvent.NoGeoRef)
        assertEquals(TapPhase.CHECKING, state.phase)
    }

    @Test
    fun unknownClassificationStillCreatesAConfirmableCandidate() {
        // Option A: a tap is authoritative even when the model names nothing.
        val state = tappedAndLocated().reduce(TapEvent.Classified(label = null, confidence = null))
        assertEquals(TapPhase.AWAITING_CONFIRMATION, state.phase)
        assertEquals(UNKNOWN_LITTER_LABEL, state.label)
        assertTrue(!state.isIdentified)
    }

    @Test
    fun closeConfidentClassificationReadsAsIdentified() {
        val state = tappedAndLocated(distance = 1.5f).reduce(TapEvent.Classified("Bottle", 0.8f))
        assertEquals(TapPhase.AWAITING_CONFIRMATION, state.phase)
        assertTrue(state.isIdentified)
        assertEquals("Bottle — is this litter?", tapPillCopy(state)?.title)
    }

    @Test
    fun distantCandidateAsksTheUserToMoveCloser() {
        val state = tappedAndLocated(distance = 12.0f).reduce(TapEvent.Classified("Bottle", 0.9f))
        assertTrue(!state.isIdentified, "12m is beyond TAP_IDENTIFY_MAX_METERS")
        assertEquals("Move closer to identify — is this litter?", tapPillCopy(state)?.title)
    }

    @Test
    fun lowConfidenceCandidateAsksTheUserToMoveCloser() {
        val state = tappedAndLocated(distance = 1.0f).reduce(TapEvent.Classified("Bottle", 0.2f))
        assertTrue(!state.isIdentified)
    }

    @Test
    fun awaitingConfirmationOffersBothActions() {
        val copy = tapPillCopy(tappedAndLocated().reduce(TapEvent.Classified("Can", 0.9f)))
        assertEquals("Confirm litter", copy?.confirmLabel)
        assertEquals("Not litter", copy?.rejectLabel)
    }

    @Test
    fun aSecondTapSupersedesTheFirst() {
        val state = tappedAndLocated().reduce(TapEvent.Tapped(0.1f, 0.2f))
        assertEquals(TapPhase.LOCATING, state.phase)
        assertEquals(0.1f, state.normalizedX)
        assertNull(state.lat, "the superseded candidate's raycast must be discarded")
    }

    @Test
    fun outOfOrderEventsAreIgnored() {
        assertEquals(idle, idle.reduce(TapEvent.RaycastHit(1.0, 2.0, 3.0f)))
        assertEquals(idle, idle.reduce(TapEvent.Classified("Bottle", 0.9f)))
        // A classification arriving while still LOCATING must not skip CHECKING.
        val locating = idle.reduce(TapEvent.Tapped(0.5f, 0.5f))
        assertEquals(locating, locating.reduce(TapEvent.Classified("Bottle", 0.9f)))
    }

    @Test
    fun confirmRejectAndDismissAllReturnToIdle() {
        val pending = tappedAndLocated().reduce(TapEvent.Classified("Can", 0.9f))
        assertEquals(idle, pending.reduce(TapEvent.Confirmed))
        assertEquals(idle, pending.reduce(TapEvent.Rejected))
        assertEquals(idle, pending.reduce(TapEvent.Dismissed))
    }

    @Test
    fun idleShowsNoPill() {
        assertNull(tapPillCopy(idle))
    }

    // ── Forced evaluation (Blake 2026-07-28): a tap ALWAYS evaluates, even
    // when it can't place — the classification lands in the miss states and
    // the pill reports what the camera saw.

    @Test
    fun classificationLandsInNoRaycastState() {
        val missed = idle.reduce(TapEvent.Tapped(0.5f, 0.5f)).reduce(TapEvent.RaycastMissed)
        val evaluated = missed.reduce(TapEvent.Classified("Bottle", 0.8f))
        assertEquals(TapPhase.NO_RAYCAST, evaluated.phase)
        assertEquals("Bottle", evaluated.label)
        assertEquals(0.8f, evaluated.confidence)
    }

    @Test
    fun classificationLandsInGpsSyncingState() {
        val syncing = idle.reduce(TapEvent.Tapped(0.5f, 0.5f)).reduce(TapEvent.NoGeoRef)
        val evaluated = syncing.reduce(TapEvent.Classified("Can", 0.7f))
        assertEquals(TapPhase.GPS_SYNCING, evaluated.phase)
        assertEquals("Can", evaluated.label)
    }

    @Test
    fun noRaycastPillReportsTheEvaluationWhenNamed() {
        val evaluated = idle.reduce(TapEvent.Tapped(0.5f, 0.5f))
            .reduce(TapEvent.RaycastMissed)
            .reduce(TapEvent.Classified("Bottle", 0.8f))
        val pill = tapPillCopy(evaluated)!!
        assertTrue(pill.title.contains("Bottle"), pill.title)
    }

    @Test
    fun noRaycastPillKeepsGuidanceWhenNothingNamed() {
        val evaluated = idle.reduce(TapEvent.Tapped(0.5f, 0.5f))
            .reduce(TapEvent.RaycastMissed)
            .reduce(TapEvent.Classified(null, null))
        val pill = tapPillCopy(evaluated)!!
        assertEquals("Hold steady and tap the item again", pill.title)
    }

    @Test
    fun gpsSyncingPillReportsTheEvaluationWhenNamed() {
        val evaluated = idle.reduce(TapEvent.Tapped(0.5f, 0.5f))
            .reduce(TapEvent.NoGeoRef)
            .reduce(TapEvent.Classified("Wrapper", 0.6f))
        val pill = tapPillCopy(evaluated)!!
        assertTrue(pill.title.contains("Wrapper"), pill.title)
        assertTrue(pill.title.contains("GPS"), pill.title)
    }
}
