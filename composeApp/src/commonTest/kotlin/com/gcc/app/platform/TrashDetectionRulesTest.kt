package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrashDetectionRulesTest {

    private fun obs(id: String, c: Float) = ClassificationObservation(id, c)

    @Test
    fun windowViewStillVotesIndoor() {
        // Several indoor labels + one outdoor label through the window: the
        // v3 rule (any outdoor hit kills INDOOR) was the "misses entirely" bug.
        val vote = TrashDetectionRules.sceneVoteFrom(
            listOf(obs("living_room", 0.6f), obs("sofa", 0.4f), obs("tree", 0.5f))
        )
        assertEquals(SceneVote.INDOOR, vote)
    }

    @Test
    fun streetSceneVotesOutdoor() {
        val vote = TrashDetectionRules.sceneVoteFrom(
            listOf(obs("street", 0.7f), obs("sidewalk", 0.5f), obs("car", 0.4f))
        )
        assertEquals(SceneVote.OUTDOOR, vote)
    }

    @Test
    fun weakSignalsVoteNone() {
        val vote = TrashDetectionRules.sceneVoteFrom(
            listOf(obs("lamp", 0.35f), obs("mug", 0.1f))
        )
        assertEquals(SceneVote.NONE, vote) // indoorScore 0.35 < 0.5 floor
    }

    @Test
    fun balancedSceneVotesOutdoorNotIndoor() {
        // porch: equal evidence must NOT read indoor (aggressive ≠ reckless).
        val vote = TrashDetectionRules.sceneVoteFrom(
            listOf(obs("ceiling", 0.5f), obs("yard", 0.5f))
        )
        assertEquals(SceneVote.OUTDOOR, vote) // outdoor >= indoor wins ties
    }

    @Test
    fun subConfidenceObservationsIgnored() {
        val vote = TrashDetectionRules.sceneVoteFrom(
            listOf(obs("kitchen", 0.29f), obs("bedroom", 0.29f))
        )
        assertEquals(SceneVote.NONE, vote)
    }

    @Test
    fun residenceInteriorKeywordsRecognized() {
        val vote = TrashDetectionRules.sceneVoteFrom(
            listOf(obs("bathtub", 0.4f), obs("mirror", 0.3f))
        )
        assertEquals(SceneVote.INDOOR, vote)
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
