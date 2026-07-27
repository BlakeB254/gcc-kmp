package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun toiletKeywordRecognizedAsIndoor() {
        val vote = TrashDetectionRules.sceneVoteFrom(
            listOf(obs("toilet", 0.4f), obs("tile", 0.3f))
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

    @Test
    fun tireOnACarDoesNotAutoPromote() {
        // The exact field failure: a whole-frame classifier saw "tire" and "car".
        val observations = listOf(obs("tire", 0.6f), obs("car", 0.7f))
        assertNull(TrashDetectionRules.autoPromotionLabelFrom(observations))
    }

    @Test
    fun tireNearATruckOrBicycleDoesNotAutoPromote() {
        assertNull(TrashDetectionRules.autoPromotionLabelFrom(listOf(obs("tire", 0.6f), obs("truck", 0.5f))))
        assertNull(TrashDetectionRules.autoPromotionLabelFrom(listOf(obs("tire", 0.6f), obs("bicycle", 0.5f))))
        assertNull(TrashDetectionRules.autoPromotionLabelFrom(listOf(obs("wheel", 0.6f), obs("car", 0.5f))))
    }

    @Test
    fun detachedTireWithNoVehicleContextStillAutoPromotes() {
        val observations = listOf(obs("tire", 0.6f), obs("grass", 0.5f))
        assertEquals("Tire", TrashDetectionRules.autoPromotionLabelFrom(observations))
    }

    @Test
    fun vehicleContextDoesNotSuppressUnrelatedLitter() {
        // A bottle on the sidewalk next to a parked car is still a bottle.
        val observations = listOf(obs("bottle", 0.6f), obs("car", 0.7f))
        assertEquals("Bottle", TrashDetectionRules.autoPromotionLabelFrom(observations))
    }

    @Test
    fun weakVehicleEvidenceDoesNotSuppress() {
        // Below SCENE_CONFIDENCE (0.3) the "car" label is noise, not context.
        val observations = listOf(obs("tire", 0.6f), obs("car", 0.1f))
        assertEquals("Tire", TrashDetectionRules.autoPromotionLabelFrom(observations))
    }

    @Test
    fun autoPromotionStillHonoursTheConfidenceFloor() {
        assertNull(TrashDetectionRules.autoPromotionLabelFrom(listOf(obs("bottle", 0.2f))))
    }

    @Test
    fun hasVehicleContextIsIndependentlyUsable() {
        assertTrue(TrashDetectionRules.hasVehicleContext(listOf(obs("car", 0.7f))))
        assertTrue(!TrashDetectionRules.hasVehicleContext(listOf(obs("grass", 0.7f))))
    }

    @Test
    fun wholeFrameRoutingSignalIsUnchangedBySuppression() {
        // trashLabelFrom stays a weak routing signal: it still reports "Tire".
        // Only auto-promotion is gated. This pins that we did not change it.
        val observations = listOf(obs("tire", 0.6f), obs("car", 0.7f))
        assertEquals("Tire", TrashDetectionRules.trashLabelFrom(observations))
    }

    // ── Saliency-crop fallback promotion ────────────────────────────

    @Test
    fun cropPromotionUsesCropObservations() {
        val crop = listOf(obs("water_bottle", 0.55f))
        val frame = listOf(obs("street", 0.7f), obs("asphalt", 0.5f))
        assertEquals("Bottle", TrashDetectionRules.cropPromotionLabelFrom(crop, frame))
    }

    @Test
    fun cropPromotionSuppressesTireWhenOnlyTheFrameShowsTheVehicle() {
        // The crop is tight on the tire — the car it is mounted on is only
        // visible at frame level. Context must merge across both lists.
        val crop = listOf(obs("tire", 0.6f))
        val frame = listOf(obs("car", 0.7f), obs("driveway", 0.5f))
        assertNull(TrashDetectionRules.cropPromotionLabelFrom(crop, frame))
    }

    @Test
    fun cropPromotionAllowsDetachedTire() {
        val crop = listOf(obs("tire", 0.6f))
        val frame = listOf(obs("grass", 0.7f))
        assertEquals("Tire", TrashDetectionRules.cropPromotionLabelFrom(crop, frame))
    }

    @Test
    fun cropPromotionHonoursConfidenceFloor() {
        assertNull(TrashDetectionRules.cropPromotionLabelFrom(listOf(obs("bottle", 0.2f)), emptyList()))
    }

    @Test
    fun cropPromotionIgnoresFrameOnlyTrashLabels() {
        // Promotion must come from what is inside the crop; frame labels are
        // suppression context only, never a label source.
        val crop = listOf(obs("grass", 0.8f))
        val frame = listOf(obs("bottle", 0.6f))
        assertNull(TrashDetectionRules.cropPromotionLabelFrom(crop, frame))
    }

    // ── Field-proven litter vocabulary (TestFlight 157/158 rounds) ──

    @Test
    fun fieldProvenLitterTokensReachTheLiveReticlePath() {
        val expectations = mapOf(
            "napkin" to "Napkin", "tissue" to "Tissue", "foil" to "Foil",
            "styrofoam" to "Styrofoam", "straw" to "Straw", "lid" to "Lid",
            "jug" to "Jug", "paper" to "Paper", "box" to "Box",
            "glass" to "Glass",
        )
        for ((id, expected) in expectations) {
            assertEquals(
                expected,
                TrashDetectionRules.trashLabelFrom(listOf(obs(id, 0.4f))),
                "token: $id",
            )
        }
    }

    @Test
    fun compoundFixtureIdentifiersDoNotMatchNewTokens() {
        // "mailbox" must not token-match "box"; tokensOf splits on '_'/' '/','
        // only, so the whole word "mailbox" stays unmatched.
        assertNull(TrashDetectionRules.trashLabelFrom(listOf(obs("mailbox", 0.8f))))
    }
}
