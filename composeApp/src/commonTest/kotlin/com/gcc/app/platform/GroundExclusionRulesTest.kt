package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [GroundExclusionRules] gates the [GroundCandidateTracker] lifecycle: a
 * confident fixture match silently excludes a candidate, a trash match
 * names its item type, and neither being confident enough leaves both null
 * so the caller falls back to "Unknown litter".
 */
class GroundExclusionRulesTest {

    private fun obs(id: String, c: Float) = ClassificationObservation(id, c)

    @Test
    fun confidentBenchMatchExcludes() {
        assertEquals("bench", GroundExclusionRules.exclusionLabelFrom(listOf(obs("bench", 0.6f))))
    }

    @Test
    fun weakBenchMatchDoesNotExclude() {
        assertNull(GroundExclusionRules.exclusionLabelFrom(listOf(obs("bench", 0.3f))))
    }

    @Test
    fun confidentBottleMatchNamesTrashType() {
        assertEquals("bottle", GroundExclusionRules.trashTypeFrom(listOf(obs("bottle", 0.5f))))
    }

    @Test
    fun noMatchingLabelReturnsNullForBoth() {
        assertNull(GroundExclusionRules.exclusionLabelFrom(listOf(obs("cloud", 0.9f))))
        assertNull(GroundExclusionRules.trashTypeFrom(listOf(obs("cloud", 0.9f))))
    }

    @Test
    fun exclusionVocabularyCoversFixtureFamilies() {
        val cases = listOf(
            "park_bench" to "bench",
            "utility_pole" to "pole",
            "street_light" to "streetlight",
            "table_lamp" to "lamp",
            "stop_sign" to "sign",
            "fire_hydrant" to "hydrant",
            "mailbox" to "mailbox",
            "bollard" to "bollard",
            "planter_box" to "planter",
            "potted_plant" to "plant",
            "bush" to "bush",
            "oak_tree" to "tree",
            "grass" to "grass",
            "curb" to "curb",
            "step" to "step",
            "parked_car" to "car",
            "tire" to "tire",
            "wheel" to "wheel",
            "animal" to "animal",
        )
        for ((identifier, expected) in cases) {
            assertEquals(expected, GroundExclusionRules.exclusionLabelFrom(listOf(obs(identifier, 0.9f))), identifier)
        }
    }

    @Test
    fun trashVocabularyCoversCommonLitter() {
        val cases = listOf(
            "bottle" to "bottle", "cup" to "cup", "can" to "can", "bag" to "bag",
            "paper" to "paper", "wrapper" to "wrapper", "box" to "box", "carton" to "carton",
            "glass" to "glass",
        )
        for ((identifier, expected) in cases) {
            assertEquals(expected, GroundExclusionRules.trashTypeFrom(listOf(obs(identifier, 0.9f))), identifier)
        }
    }

    @Test
    fun glassMatchesAtAnyConfidence() {
        // trashTypeFrom is deliberately not confidence-gated (see its kdoc) —
        // glass shards/bottles are litter and must reach the weight
        // estimator's solid-glass density bucket even on a weak guess.
        assertEquals("glass", GroundExclusionRules.trashTypeFrom(listOf(obs("glass", 0.05f))))
        assertEquals("glass", GroundExclusionRules.trashTypeFrom(listOf(obs("broken_glass", 0.05f))))
    }

    @Test
    fun exclusionConfidenceFloorIsExactlyFortyFivePercent() {
        assertEquals(0.45f, GroundExclusionRules.EXCLUSION_CONFIDENCE)
        assertEquals("bench", GroundExclusionRules.exclusionLabelFrom(listOf(obs("bench", 0.45f))))
        assertNull(GroundExclusionRules.exclusionLabelFrom(listOf(obs("bench", 0.44f))))
    }

    // ── classify(): the one combined decision (TestFlight 158 field round) ──

    @Test
    fun positiveTrashMatchSuppressesBackgroundExclusion() {
        // A bottle lying on grass classifies as BOTH — the crop always
        // includes ground context. Exclusion-first silently killed exactly
        // the candidates the detector exists for.
        val labels = listOf(obs("grass", 0.9f), obs("bottle", 0.3f))
        val decision = GroundExclusionRules.classify(labels)
        assertNull(decision.exclusionLabel)
        assertEquals("bottle", decision.trashType)
    }

    @Test
    fun fixtureWithoutAnyTrashSignalStillExcludes() {
        val decision = GroundExclusionRules.classify(listOf(obs("bench", 0.9f)))
        assertEquals("bench", decision.exclusionLabel)
        assertNull(decision.trashType)
    }

    @Test
    fun noMatchEitherWayLeavesBothNull() {
        val decision = GroundExclusionRules.classify(listOf(obs("rock", 0.9f)))
        assertNull(decision.exclusionLabel)
        assertNull(decision.trashType)
    }

    @Test
    fun expandedTrashVocabularyCoversSoftAndGenericLitter() {
        val cases = listOf(
            "trash", "garbage", "litter", "waste", "debris", "napkin",
            "tissue", "foil", "styrofoam", "straw", "lid", "jug",
        )
        for (identifier in cases) {
            assertEquals(identifier, GroundExclusionRules.trashTypeFrom(listOf(obs(identifier, 0.2f))), identifier)
        }
    }
}
