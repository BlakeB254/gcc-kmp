package com.gcc.app.platform

/**
 * Classification vocabulary for [GroundCandidatePacket]s, split by what the
 * label MEANS for a LiDAR-proposed ground protrusion (build-164 rebuild —
 * "geometry proposes, classification disposes" inverted to "geometry
 * proposes, only a confident FIXTURE label disposes"):
 *
 *  - FIXTURE ([exclusionLabelFrom]): bench, pole, hydrant, parked car — a
 *    known permanent object. Terminal, silent exclusion, as before.
 *  - BACKDROP ([backdropLabelFrom]): grass, bush, leaves, soil, rock — the
 *    natural ground cover the candidate is sitting IN. The pre-164 rules
 *    treated these as fixtures, which silently killed most real litter: a
 *    weathered wrapper's crop classifies as "grass 0.6, plant 0.5" and
 *    nothing else, because the classifier only sees the backdrop. Backdrop
 *    labels now leave the candidate ALIVE but reticle-quiet
 *    ([GroundCandidateTracker] tracks it as backdrop-natural): it surfaces
 *    only when the user shows clear intent (steady close observation or a
 *    tap), and then through the "is this litter?" prompt — never auto-filed.
 *  - Trash ([trashTypeFrom]): names the item type, unchanged.
 *
 * Distinct from [TrashDetectionRules] — that object votes indoor/outdoor
 * scene context and a *weak* routing label for the legacy whole-frame flow;
 * this one gates the ground-candidate lifecycle itself.
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
    //
    // Permanent fixtures/infrastructure/beings only. Natural ground cover
    // lives in BACKDROP_TOKENS; "tire"/"wheel" are context-gated below (a
    // tire ON a car is a fixture part, a tire dumped in a lot is litter —
    // 2026-07-25 field video: two dumped tires, zero identifications).
    private val FIXTURE_TOKENS = setOf(
        "bench", "pole", "streetlight", "lamp", "sign", "hydrant", "mailbox",
        "bollard", "planter", "curb", "step", "car", "animal",
    )

    /** Excluded only when the labels also carry vehicle context — see [FIXTURE_TOKENS] comment. */
    private val VEHICLE_GATED_TOKENS = setOf("tire", "wheel")

    /**
     * Labels meaning "an actual vehicle is present". Deliberately NOT
     * [TrashDetectionRules.VEHICLE_CONTEXT_KEYWORDS] — that set includes
     * "wheel"/"wheels"/"tyre" (right for suppressing a whole-frame Tire
     * promotion), which here would make a lone dumped wheel supply its own
     * vehicle context and self-exclude.
     */
    private val VEHICLE_PRESENT_TOKENS = setOf(
        "car", "truck", "van", "bus", "vehicle", "automobile", "motorcycle",
        "bicycle", "bike", "trailer", "suv", "jeep",
    )

    /** Confidence floor for a vehicle-presence label, mirroring [TrashDetectionRules]' scene floor. */
    private const val VEHICLE_PRESENT_CONFIDENCE = 0.30f

    private fun hasVehiclePresent(labels: List<ClassificationObservation>): Boolean =
        labels.any { obs ->
            obs.confidence >= VEHICLE_PRESENT_CONFIDENCE &&
                TrashDetectionRules.tokensOf(obs.identifier).any { it in VEHICLE_PRESENT_TOKENS }
        }

    /**
     * Natural ground cover and vegetation: describes what the candidate sits
     * IN (or a living thing that is never litter "within reason" — a bush is
     * a bush even on a sidewalk). Soft outcome: the candidate survives,
     * quiet. Deliberately NOT surface-conditional this round — grass grows
     * through sidewalk cracks in every field lot; promoting
     * vegetation-on-pavement as an anomaly would be reticle spam.
     */
    private val BACKDROP_TOKENS = setOf(
        "plant", "bush", "tree", "grass", "lawn", "turf", "meadow", "weed",
        "moss", "hay", "leaf", "leaves", "foliage", "flower", "soil", "dirt",
        "sand", "mud", "gravel", "rock", "stone", "pebble", "mulch",
        // The Stage-2 crop classifier's negative class (LitterCropClassifier):
        // a confident "background" verdict means "nothing litter-like in this
        // crop" — soft, same as natural ground cover (quiet but tappable),
        // never terminal while the model is young.
        "background",
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
     * The FIXTURE keyword matched at or above [EXCLUSION_CONFIDENCE], or
     * null when nothing in [labels] names a known fixture. Tire/wheel count
     * only when [TrashDetectionRules.hasVehicleContext] holds over the same
     * labels. The first observation (in list order) whose identifier matches
     * wins, mirroring [TrashDetectionRules.trashLabelFrom]'s
     * firstNotNullOfOrNull ordering.
     */
    fun exclusionLabelFrom(labels: List<ClassificationObservation>): String? {
        val vehicleContext by lazy { hasVehiclePresent(labels) }
        return labels.firstNotNullOfOrNull { obs ->
            if (obs.confidence < EXCLUSION_CONFIDENCE) return@firstNotNullOfOrNull null
            matchToken(obs.identifier, FIXTURE_TOKENS)
                ?: matchToken(obs.identifier, VEHICLE_GATED_TOKENS)?.takeIf { vehicleContext }
        }
    }

    /** The BACKDROP keyword matched at or above [EXCLUSION_CONFIDENCE], or null. */
    fun backdropLabelFrom(labels: List<ClassificationObservation>): String? =
        labels.firstNotNullOfOrNull { obs ->
            if (obs.confidence < EXCLUSION_CONFIDENCE) return@firstNotNullOfOrNull null
            matchToken(obs.identifier, BACKDROP_TOKENS)
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
     * trash-type match SUPPRESSES both a fixture exclusion and a backdrop
     * match: the candidate crop always includes ground context, so a bottle
     * lying on grass classifies as BOTH "bottle" and "grass" — and grass
     * clears [EXCLUSION_CONFIDENCE] easily. Running the exclusion check
     * first (the pre-158 call-site order) silently killed exactly the
     * candidates the detector was built for: litter on grass, next to a
     * curb, or near a parked car. A specific trash token is the stronger
     * signal — background context never outvotes it.
     *
     * Exactly one of the three fields is non-null, or all three are null
     * (appearance-unknown): fixture wins over backdrop when both match,
     * because a fixture label is a positive identification of a permanent
     * object while backdrop merely describes the surroundings.
     */
    data class GroundClassification(
        val exclusionLabel: String?,
        val trashType: String?,
        val backdropLabel: String? = null,
    )

    fun classify(labels: List<ClassificationObservation>): GroundClassification {
        // Suppression stays UNGATED (any trash token keeps the candidate
        // alive — bottle-on-grass must not be excluded as "grass"), but the
        // TYPE only sticks at TRASH_TYPE_CONFIDENCE. A weak trash guess thus
        // yields (exclusion=null, trashType=null): the candidate survives to
        // full progress and asks the user instead of silently auto-filing.
        val anyTrashToken = trashTypeFrom(labels)
        val trashType = trashTypeFrom(labels, TRASH_TYPE_CONFIDENCE)
        if (anyTrashToken != null) {
            return GroundClassification(exclusionLabel = null, trashType = trashType)
        }
        val exclusionLabel = exclusionLabelFrom(labels)
        if (exclusionLabel != null) {
            return GroundClassification(exclusionLabel = exclusionLabel, trashType = null)
        }
        return GroundClassification(
            exclusionLabel = null,
            trashType = null,
            backdropLabel = backdropLabelFrom(labels),
        )
    }
}
