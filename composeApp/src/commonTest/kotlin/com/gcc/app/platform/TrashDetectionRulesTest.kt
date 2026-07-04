package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrashDetectionRulesTest {

    @Test
    fun indoorHitWithNoOutdoorHitIsIndoor() {
        val obs = listOf(ClassificationObservation("kitchen", 0.5f))
        assertEquals(SceneVote.INDOOR, TrashDetectionRules.sceneVoteFrom(obs))
    }

    @Test
    fun outdoorHitsAtLeastIndoorHitsIsOutdoor() {
        val obs = listOf(
            ClassificationObservation("street", 0.5f),
            ClassificationObservation("sky", 0.4f),
        )
        assertEquals(SceneVote.OUTDOOR, TrashDetectionRules.sceneVoteFrom(obs))
    }

    @Test
    fun lowConfidenceObservationsAreIgnored() {
        val obs = listOf(ClassificationObservation("kitchen", 0.1f))
        assertEquals(SceneVote.NONE, TrashDetectionRules.sceneVoteFrom(obs))
    }

    @Test
    fun trashKeywordAboveThresholdMatches() {
        // tokensOf splits "plastic_bottle" into ["plastic", "bottle"]; firstNotNullOfOrNull
        // matches the first token present in TRASH_TOKENS, so "plastic" wins over "bottle".
        // This is the verbatim pre-existing iOS matching order — preserved, not changed, here.
        val obs = listOf(ClassificationObservation("plastic_bottle", 0.4f))
        assertEquals("Plastic", TrashDetectionRules.trashLabelFrom(obs))
    }

    @Test
    fun trashKeywordBelowThresholdDoesNotMatch() {
        val obs = listOf(ClassificationObservation("bottle", 0.2f))
        assertNull(TrashDetectionRules.trashLabelFrom(obs))
    }

    @Test
    fun noTrashKeywordDoesNotMatch() {
        val obs = listOf(ClassificationObservation("cloud", 0.9f))
        assertNull(TrashDetectionRules.trashLabelFrom(obs))
    }
}
