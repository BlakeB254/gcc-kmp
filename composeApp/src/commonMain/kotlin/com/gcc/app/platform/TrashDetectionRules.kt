package com.gcc.app.platform

/** Platform-agnostic view of a single classification result (Vision on iOS, ML Kit on Android). */
data class ClassificationObservation(val identifier: String, val confidence: Float)

/**
 * Shared indoor/outdoor scene voting and trash-keyword matching — used by
 * both the iOS Vision pipeline and the Android ML Kit pipeline so neither
 * platform can silently drift from the other's thresholds/keyword lists.
 * Moved out of iosMain's `SceneVoteVision.kt` (still the iOS entry point,
 * now a thin adapter over this object) as part of the Android-parity work.
 */
object TrashDetectionRules {

    private const val SCENE_CONFIDENCE = 0.3f
    private const val TRASH_CONFIDENCE = 0.35f
    private const val SCENE_SCORE_MIN = 0.5f
    private const val INDOOR_DOMINANCE = 1.25f

    val INDOOR_KEYWORDS = setOf(
        "indoor", "room", "ceiling", "wall", "kitchen", "bathroom", "bedroom",
        "office", "hallway", "furniture", "sofa", "couch", "desk", "carpet",
        "curtain", "shelf", "stairs", "interior", "chair", "table",
        "television", "lamp", "bed", "cabinet", "refrigerator", "laptop",
        "monitor", "screen", "keyboard", "bookcase", "book", "rug", "pillow",
        "blanket", "sink", "counter", "cupboard", "appliance", "tile",
        "computer", "floor", "flooring", "hardwood", "printer", "document",
        "paper", "drawer", "wardrobe", "closet", "mug", "houseplant",
        "blinds", "mirror", "doorway", "fireplace", "dresser", "bathtub", "shower",
        "oven", "stove", "microwave", "dishwasher", "staircase", "toilet",
    )
    val OUTDOOR_KEYWORDS = setOf(
        "outdoor", "street", "road", "sidewalk", "sky", "tree", "grass",
        "park", "building", "asphalt", "pavement", "cloud", "field",
        "vehicle", "car", "house", "fence", "yard", "highway", "lawn",
        "garden", "trail", "bush", "truck", "bicycle", "traffic", "bridge",
        "alley", "playground", "landscape", "horizon",
    )
    val TRASH_TOKENS = mapOf(
        "garbage" to "Trash", "trash" to "Trash", "waste" to "Trash",
        "litter" to "Litter", "rubbish" to "Trash", "debris" to "Debris",
        "bottle" to "Bottle", "can" to "Can", "cup" to "Cup", "bag" to "Bag",
        "plastic" to "Plastic", "wrapper" to "Wrapper", "carton" to "Carton",
        "tire" to "Tire", "mattress" to "Mattress",
    )

    /**
     * Labels that mean "a vehicle is in this frame". A whole-frame classifier
     * cannot tell us whether the tire it saw is ON one of these — so when a
     * vehicle is present at real confidence, a tire/wheel prediction is never
     * auto-promoted. The user can still tap a detached tire and confirm it.
     */
    val VEHICLE_CONTEXT_KEYWORDS = setOf(
        "car", "truck", "van", "bus", "vehicle", "automobile", "motorcycle",
        "bicycle", "bike", "wheel", "wheels", "tyre", "trailer", "suv", "jeep",
    )

    /**
     * Trash labels that are commonly a PART of a larger, non-discarded parent
     * object. Only these are suppressed by [hasVehicleContext]; a bottle next
     * to a parked car is still a bottle.
     */
    val PARENT_ATTACHED_LABELS = setOf("Tire")

    /** True when a vehicle label is present at or above [SCENE_CONFIDENCE]. */
    fun hasVehicleContext(observations: List<ClassificationObservation>): Boolean =
        observations.any { obs ->
            obs.confidence >= SCENE_CONFIDENCE &&
                tokensOf(obs.identifier).any { it in VEHICLE_CONTEXT_KEYWORDS }
        }

    /**
     * The label an AUTOMATIC detection may be promoted with, or null.
     *
     * Distinct from [trashLabelFrom], which stays a weak routing signal for the
     * whole-frame classifier and is deliberately unchanged. This applies the
     * parent-context suppression required before anything auto-files.
     *
     * A user tap MUST NOT call this — a tap is authoritative and may confirm a
     * detached tire even in a driveway full of cars.
     */
    fun autoPromotionLabelFrom(observations: List<ClassificationObservation>): String? {
        val label = trashLabelFrom(observations) ?: return null
        if (label in PARENT_ATTACHED_LABELS && hasVehicleContext(observations)) return null
        return label
    }

    fun tokensOf(identifier: String): List<String> =
        identifier.lowercase().split('_', ' ', ',').filter { it.isNotBlank() }

    fun sceneVoteFrom(observations: List<ClassificationObservation>): SceneVote {
        var indoorScore = 0f
        var outdoorScore = 0f
        for (obs in observations) {
            if (obs.confidence < SCENE_CONFIDENCE) continue
            val tokens = tokensOf(obs.identifier)
            if (tokens.any { it in INDOOR_KEYWORDS }) indoorScore += obs.confidence
            if (tokens.any { it in OUTDOOR_KEYWORDS }) outdoorScore += obs.confidence
        }
        // Confidence-weighted: one "tree through the window" label no longer
        // saves an indoor scene, but ties still read outdoor (porches).
        return when {
            indoorScore >= SCENE_SCORE_MIN && indoorScore > outdoorScore * INDOOR_DOMINANCE -> SceneVote.INDOOR
            outdoorScore >= SCENE_SCORE_MIN && outdoorScore >= indoorScore -> SceneVote.OUTDOOR
            else -> SceneVote.NONE
        }
    }

    fun trashLabelFrom(observations: List<ClassificationObservation>): String? =
        observations.firstNotNullOfOrNull { obs ->
            if (obs.confidence < TRASH_CONFIDENCE) return@firstNotNullOfOrNull null
            tokensOf(obs.identifier).firstNotNullOfOrNull { TRASH_TOKENS[it] }
        }

    /** Confidence of whichever observation actually produced [label] via
     *  [trashLabelFrom] -- callers that already have the label (rather than
     *  re-deriving it) use this to feed a live severity/quality estimate
     *  without threading a second value through every call site by hand. */
    fun trashConfidenceFor(label: String, observations: List<ClassificationObservation>): Float =
        observations
            .filter { it.confidence >= TRASH_CONFIDENCE }
            .filter { obs -> tokensOf(obs.identifier).any { TRASH_TOKENS[it] == label } }
            .maxOfOrNull { it.confidence }
            ?: TRASH_CONFIDENCE
}
