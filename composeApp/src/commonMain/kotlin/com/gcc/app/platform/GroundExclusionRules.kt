package com.gcc.app.platform

/**
 * Silent-exclusion vocabulary for [GroundCandidatePacket]s: whole-frame
 * environment classification results that mean "this protrusion is a known
 * fixture, not litter" (bench, pole, curb, ...) versus results that name an
 * actual trash type. Distinct from [TrashDetectionRules] — that object votes
 * indoor/outdoor scene context and a *weak* routing label for the legacy
 * whole-frame flow; this one gates the ground-candidate lifecycle itself, so
 * its exclusion match is confidence-gated and its miss path is silent (no
 * user-facing rejection UI — [GroundCandidateTracker.recordClassification]
 * just moves the candidate to EXCLUDED and it never surfaces again).
 */
object GroundExclusionRules {

    /** Minimum classifier confidence for an exclusion label to count. Below this, a stray "bench" guess must not silently kill a real candidate. */
    const val EXCLUSION_CONFIDENCE = 0.45f

    // Whole-word tokens, not raw substrings: identifiers are matched via
    // TrashDetectionRules.tokensOf (splits on '_'/' '/','), and each
    // resulting token is compared for EXACT set membership. Plain substring
    // matching over the whole identifier is unsafe here -- e.g. "carton"
    // contains "car", and "street_light" un-split contains "tree" (inside
    // "sTREEt") -- both would silently misfire against unrelated vocab.
    private val EXCLUSION_TOKENS = setOf(
        "bench", "pole", "streetlight", "lamp", "sign", "hydrant", "mailbox",
        "bollard", "planter", "plant", "bush", "tree", "grass", "curb",
        "step", "car", "tire", "wheel", "animal",
    )

    private val TRASH_TOKENS = setOf(
        "bottle", "cup", "can", "bag", "paper", "wrapper", "box", "carton",
        // Glass shards/bottles are litter: without this token real glass
        // never reaches LitterWeightEstimator's solid-glass density bucket
        // (canonicalType("glass") -> "glass_bottle" -> 300 g/L).
        "glass",
    )

    /** Token-set match for [identifier]: first whole word from [vocab] found
     *  in [TrashDetectionRules.tokensOf], falling back to the tokens joined
     *  with no separator (so a compound label split by the classifier as
     *  e.g. "street" + "light" still matches the single vocab word
     *  "streetlight"). Null when nothing in [vocab] matches either way. */
    private fun matchToken(identifier: String, vocab: Set<String>): String? {
        val tokens = TrashDetectionRules.tokensOf(identifier)
        tokens.firstOrNull { it in vocab }?.let { return it }
        val joined = tokens.joinToString("")
        return joined.takeIf { it in vocab }
    }

    /**
     * The exclusion keyword matched at or above [EXCLUSION_CONFIDENCE], or
     * null when nothing in [labels] names a known fixture. The first
     * observation (in list order) whose identifier matches [EXCLUSION_TOKENS]
     * wins, mirroring [TrashDetectionRules.trashLabelFrom]'s
     * firstNotNullOfOrNull ordering.
     */
    fun exclusionLabelFrom(labels: List<ClassificationObservation>): String? =
        labels.firstNotNullOfOrNull { obs ->
            if (obs.confidence < EXCLUSION_CONFIDENCE) return@firstNotNullOfOrNull null
            matchToken(obs.identifier, EXCLUSION_TOKENS)
        }

    /**
     * The trash-type keyword named by [labels], or null when nothing matches
     * — callers fall back to [UNKNOWN_LITTER_LABEL] in that case. Unlike
     * [exclusionLabelFrom] this is not confidence-gated: a candidate only
     * reaches classification after passing the dwell/tap gates, so any
     * trash-shaped guess is useful routing, never a false accusation.
     */
    fun trashTypeFrom(labels: List<ClassificationObservation>): String? =
        labels.firstNotNullOfOrNull { obs -> matchToken(obs.identifier, TRASH_TOKENS) }
}
