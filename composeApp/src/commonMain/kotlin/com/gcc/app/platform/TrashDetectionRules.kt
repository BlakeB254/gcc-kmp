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
        "blinds", "mirror", "doorway", "fireplace", "dresser", "bathtub", "shower", "oven", "stove", "microwave", "dishwasher", "staircase",
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
}
