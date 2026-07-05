package com.gcc.app.platform

/**
 * Non-litter world elements the environment scan registers to the grid so
 * future scans (and the world model) can EXPECT them: vegetation and fixed
 * infrastructure are stable landmarks — useful for re-localization, change
 * detection ("the fence is gone"), and for not re-flagging a bush as an
 * unidentified object every session.
 *
 * Domains match world_observations.domain:
 * 'vegetation' (living) and 'construction' (built structures).
 */
data class EnvironmentElement(
    /** Canonical type persisted as world_observations.itemType. */
    val itemType: String,
    /** world_observations.domain value. */
    val domain: String,
)

object EnvironmentElementCatalog {

    private const val ELEMENT_CONFIDENCE = 0.4f

    private val TOKEN_MAP = mapOf(
        "tree" to EnvironmentElement("tree", "vegetation"),
        "bush" to EnvironmentElement("bush", "vegetation"),
        "shrub" to EnvironmentElement("bush", "vegetation"),
        "hedge" to EnvironmentElement("bush", "vegetation"),
        "fence" to EnvironmentElement("fence", "construction"),
        "gate" to EnvironmentElement("gate", "construction"),
        "pole" to EnvironmentElement("pole", "construction"),
        "hydrant" to EnvironmentElement("hydrant", "construction"),
        "bench" to EnvironmentElement("bench", "construction"),
        "mailbox" to EnvironmentElement("mailbox", "construction"),
        "sign" to EnvironmentElement("sign", "construction"),
    )

    /** All canonical element types (for glyph rendering / filtering). */
    val ELEMENT_TYPES: Set<String> = TOKEN_MAP.values.map { it.itemType }.toSet()

    /**
     * First landmark element among the classifications, or null. Runs on the
     * same observation list the trash matcher uses — call it on ticks where
     * no trash matched, so a "plastic bag stuck in a bush" registers as the
     * bag, not the bush.
     */
    fun elementFrom(observations: List<ClassificationObservation>): Pair<EnvironmentElement, Float>? =
        observations.firstNotNullOfOrNull { obs ->
            if (obs.confidence < ELEMENT_CONFIDENCE) return@firstNotNullOfOrNull null
            TrashDetectionRules.tokensOf(obs.identifier)
                .firstNotNullOfOrNull { TOKEN_MAP[it] }
                ?.let { it to obs.confidence }
        }
}
