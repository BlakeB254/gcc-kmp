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
            "curb" to "curb",
            "step" to "step",
            "parked_car" to "car",
            "animal" to "animal",
        )
        for ((identifier, expected) in cases) {
            assertEquals(expected, GroundExclusionRules.exclusionLabelFrom(listOf(obs(identifier, 0.9f))), identifier)
        }
    }

    // ── Backdrop split (build 164 rebuild): natural ground cover no longer
    // excludes — it goes soft via backdropLabelFrom, so litter whose crop
    // classifies as only its surroundings survives to dwell/tap/prompt ──

    @Test
    fun vegetationAndGroundCoverAreBackdropNotFixture() {
        val cases = listOf(
            "potted_plant" to "plant", "bush" to "bush", "oak_tree" to "tree",
            "grass" to "grass", "fallen_leaves" to "leaves", "soil" to "soil",
            "rock" to "rock", "gravel" to "gravel", "mulch" to "mulch",
        )
        for ((identifier, expected) in cases) {
            assertNull(GroundExclusionRules.exclusionLabelFrom(listOf(obs(identifier, 0.9f))), identifier)
            assertEquals(expected, GroundExclusionRules.backdropLabelFrom(listOf(obs(identifier, 0.9f))), identifier)
        }
    }

    @Test
    fun classifyReportsBackdropWhenOnlyGroundCoverMatches() {
        val decision = GroundExclusionRules.classify(listOf(obs("grass", 0.9f), obs("plant", 0.5f)))
        assertNull(decision.exclusionLabel)
        assertNull(decision.trashType)
        assertEquals("grass", decision.backdropLabel)
    }

    @Test
    fun trashTokenSuppressesBackdropToo() {
        val decision = GroundExclusionRules.classify(listOf(obs("grass", 0.9f), obs("wrapper", 0.1f)))
        assertNull(decision.exclusionLabel)
        assertNull(decision.backdropLabel)
        assertNull(decision.trashType) // 0.1 < typing floor — alive, untyped
    }

    @Test
    fun fixtureWinsOverBackdropWhenBothMatch() {
        val decision = GroundExclusionRules.classify(listOf(obs("bench", 0.8f), obs("grass", 0.9f)))
        assertEquals("bench", decision.exclusionLabel)
        assertNull(decision.backdropLabel)
    }

    @Test
    fun weakBackdropGuessDoesNotGoSoft() {
        assertNull(GroundExclusionRules.backdropLabelFrom(listOf(obs("grass", 0.44f))))
    }

    @Test
    fun trainedModelBackgroundVerdictGoesSoftNotTerminal() {
        // Stage-2 crop classifier negative class: quiet, never excluded.
        val decision = GroundExclusionRules.classify(listOf(obs("background", 0.9f)))
        assertNull(decision.exclusionLabel)
        assertEquals("background", decision.backdropLabel)
    }

    @Test
    fun trainedModelLitterVerdictOutranksItsOwnBackground() {
        // First-match ordering: the model emits its top class first; a
        // litter class wins over a lower-ranked background.
        val decision = GroundExclusionRules.classify(
            listOf(obs("plastic_bottle", 0.7f), obs("background", 0.3f)),
        )
        assertEquals("bottle", decision.trashType)
        assertNull(decision.backdropLabel)
    }

    // ── Tire/wheel are vehicle-context-gated (2026-07-25 field video: two
    // dumped tires in a vacant lot, zero identifications — a tire is only a
    // fixture part when a vehicle is actually in the labels) ──

    @Test
    fun loneTireIsNotExcluded() {
        val decision = GroundExclusionRules.classify(listOf(obs("tire", 0.9f), obs("grass", 0.6f)))
        assertNull(decision.exclusionLabel)
    }

    @Test
    fun tireWithVehicleContextStillExcludes() {
        assertEquals(
            "tire",
            GroundExclusionRules.exclusionLabelFrom(listOf(obs("tire", 0.9f), obs("car", 0.4f))),
        )
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
        // trashTypeFrom's DEFAULT stays ungated (suppression/routing uses) —
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

    // ── TRASH_TYPE_CONFIDENCE: the autonomous-typing floor (TestFlight 159
    // phantom round: a ~0.02 "paper" guess on plain ground auto-filed litter
    // the user never saw) ──

    @Test
    fun weakTrashGuessDoesNotTypeButStillSuppressesExclusion() {
        // grass clears EXCLUSION_CONFIDENCE, paper is a 0.05 whisper: the
        // candidate must NOT be typed (no autonomous filing) and must NOT be
        // excluded (the whisper keeps it alive) — it survives untyped and
        // routes to the AWAITING_INTENT prompt at full progress.
        val decision = GroundExclusionRules.classify(listOf(obs("grass", 0.9f), obs("paper", 0.05f)))
        assertNull(decision.trashType)
        assertNull(decision.exclusionLabel)
    }

    @Test
    fun confidentTrashGuessStillTypes() {
        val decision = GroundExclusionRules.classify(listOf(obs("grass", 0.9f), obs("bottle", 0.6f)))
        assertEquals("bottle", decision.trashType)
        assertNull(decision.exclusionLabel)
    }

    @Test
    fun typingFloorIsExactlyThirtyPercent() {
        assertEquals(0.30f, GroundExclusionRules.TRASH_TYPE_CONFIDENCE)
        assertEquals("bottle", GroundExclusionRules.classify(listOf(obs("bottle", 0.30f))).trashType)
        assertNull(GroundExclusionRules.classify(listOf(obs("bottle", 0.29f))).trashType)
    }
}
