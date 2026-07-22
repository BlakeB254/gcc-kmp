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

    /**
     * Minimum classifier confidence for a trash token to TYPE a candidate.
     * Typing is what makes a candidate eligible to auto-complete and file
     * with no user interaction ([GroundCandidateTracker.completedCandidates]
     * accepts `itemType != null` alone — classification runs on PROPOSED
     * candidates, BEFORE any dwell/tap gate). The crop's top-10 labels
     * arrive with no floor at all, so a ~0.02-confidence "paper" guess on a
     * leaf pile used to auto-file litter the user never saw. Below this
     * floor the candidate stays untyped: it is NOT lost — at full scan
     * progress it routes to the AWAITING_INTENT "is this litter?" prompt,
     * which is exactly where an uncertain guess belongs.
     */
    const val TRASH_TYPE_CONFIDENCE = 0.30f

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
        // Field rounds on TestFlight 157/158: the original 9 tokens covered
        // clean, isolated containers but missed most real-world litter —
        // crumpled/soft items and anything the classifier names generically.
        // These stay SPECIFIC (each token affirmatively names refuse or a
        // common litter item); deliberately NOT added: "plastic",
        // "container", "packaging" and similar broad material words, which
        // would type planters/bins/fixtures as litter.
        "trash", "garbage", "litter", "waste", "debris", "napkin", "tissue",
        "foil", "styrofoam", "straw", "lid", "jug",
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
     * The trash-type keyword named by [labels] at or above [minConfidence],
     * or null when nothing matches — callers fall back to
     * [UNKNOWN_LITTER_LABEL] in that case. [minConfidence] defaults to 0
     * (any guess) for suppression/routing uses; [classify] passes
     * [TRASH_TYPE_CONFIDENCE] for the typing decision because a typed
     * candidate can auto-file with no user interaction (classification runs
     * on PROPOSED candidates — there is no dwell/tap gate ahead of it).
     */
    fun trashTypeFrom(
        labels: List<ClassificationObservation>,
        minConfidence: Float = 0f,
    ): String? =
        labels.firstNotNullOfOrNull { obs ->
            if (obs.confidence < minConfidence) return@firstNotNullOfOrNull null
            matchToken(obs.identifier, TRASH_TOKENS)
        }

    /**
     * The one classification decision for a ground candidate. A positive
     * trash-type match SUPPRESSES a fixture exclusion: the candidate crop
     * always includes ground context, so a bottle lying on grass classifies
     * as BOTH "bottle" and "grass" — and grass clears [EXCLUSION_CONFIDENCE]
     * easily. Running the exclusion check first (the pre-158 call-site order)
     * silently killed exactly the candidates the detector was built for:
     * litter on grass, next to a curb, or near a parked car. A specific
     * trash token is the stronger signal — background context never
     * outvotes it.
     */
    data class GroundClassification(val exclusionLabel: String?, val trashType: String?)

    fun classify(labels: List<ClassificationObservation>): GroundClassification {
        // Suppression stays UNGATED (any trash token keeps the candidate
        // alive — bottle-on-grass must not be excluded as "grass"), but the
        // TYPE only sticks at TRASH_TYPE_CONFIDENCE. A weak trash guess thus
        // yields (exclusion=null, trashType=null): the candidate survives to
        // full progress and asks the user instead of silently auto-filing.
        val anyTrashToken = trashTypeFrom(labels)
        val trashType = trashTypeFrom(labels, TRASH_TYPE_CONFIDENCE)
        val exclusionLabel = if (anyTrashToken != null) null else exclusionLabelFrom(labels)
        return GroundClassification(exclusionLabel = exclusionLabel, trashType = trashType)
    }
}
