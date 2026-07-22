package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [litterPhotoPrivacyVerdict] — the pure decision half of the litter-photo
 * privacy screen (no people / animals / home interiors on the public map;
 * Blake, 2026-07-22). The Vision plumbing is iOS-only; this seam is where
 * the block/allow policy lives.
 */
class LitterPhotoPrivacyScreenTest {

    private fun obs(id: String, c: Float) = ClassificationObservation(id, c)

    @Test
    fun cleanOutdoorPhotoWithVisibleTrashIsAllowed() {
        val verdict = litterPhotoPrivacyVerdict(
            humanConfidence = null,
            animalConfidence = null,
            sceneLabels = listOf(obs("street", 0.8f), obs("sidewalk", 0.6f), obs("bottle", 0.4f)),
        )
        assertTrue(verdict.allowed)
    }

    @Test
    fun photoWithoutVisibleTrashIsBlocked() {
        // Blake's dog-walk case: a frame of plain ground (or the dog) with no
        // trash anywhere must never become a production litter image.
        val verdict = litterPhotoPrivacyVerdict(
            humanConfidence = null,
            animalConfidence = null,
            sceneLabels = listOf(obs("street", 0.8f), obs("grass", 0.7f)),
        )
        assertFalse(verdict.allowed)
        assertEquals("no-visible-trash", verdict.reason)
    }

    @Test
    fun trashPresenceFloorIsAWhisperNotFilingGrade() {
        // 0.05 presence vs 0.30 filing: whole-frame Vision rarely names
        // litter confidently even when it's plainly in frame.
        val labels = listOf(obs("street", 0.8f), obs("wrapper", 0.06f))
        assertTrue(litterPhotoPrivacyVerdict(null, null, labels).allowed)
        val below = listOf(obs("street", 0.8f), obs("wrapper", 0.04f))
        assertEquals("no-visible-trash", litterPhotoPrivacyVerdict(null, null, below).reason)
    }

    @Test
    fun personBlocksEvenAtLowConfidence() {
        val verdict = litterPhotoPrivacyVerdict(0.25f, null, emptyList())
        assertFalse(verdict.allowed)
        assertEquals("person", verdict.reason)
    }

    @Test
    fun animalBlocks() {
        val verdict = litterPhotoPrivacyVerdict(null, 0.6f, emptyList())
        assertFalse(verdict.allowed)
        assertEquals("animal", verdict.reason)
    }

    @Test
    fun indoorSceneBlocks() {
        // kitchen/ceiling dominate → sceneVoteFrom reads INDOOR → home
        // interior never publishes.
        val verdict = litterPhotoPrivacyVerdict(
            humanConfidence = null,
            animalConfidence = null,
            sceneLabels = listOf(obs("kitchen", 0.7f), obs("ceiling", 0.5f)),
        )
        assertFalse(verdict.allowed)
        assertEquals("indoor", verdict.reason)
    }

    @Test
    fun subFloorDetectionsAllow() {
        // Below the deliberately-low 0.2 floor a whisper of a detection does
        // not censor the photo (trash token present so the positive gate
        // passes).
        val verdict = litterPhotoPrivacyVerdict(0.1f, 0.15f, listOf(obs("street", 0.9f), obs("bottle", 0.5f)))
        assertTrue(verdict.allowed)
    }

    @Test
    fun personOutranksScene() {
        // Ordering: person > animal > indoor — the reason names the worst hit.
        val verdict = litterPhotoPrivacyVerdict(0.9f, 0.9f, listOf(obs("kitchen", 0.9f)))
        assertEquals("person", verdict.reason)
    }
}
