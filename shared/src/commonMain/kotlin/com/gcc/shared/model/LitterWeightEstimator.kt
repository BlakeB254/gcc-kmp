package com.gcc.shared.model

import kotlin.math.roundToInt

/**
 * Estimates a litter item's mass from its LiDAR-measured bounding box, for
 * completed ground-candidate scans (Task 4/6's [GroundCandidateTracker] /
 * `ArEnvScanOverlay.setGroundCandidateCompletedListener]) — a REAL measured
 * size rather than the close-range confirm card's per-type typical-weight
 * lookup ([com.gcc.app.platform.TrashItemCatalog], which assumes a canonical
 * size for the type). Volume-times-density is the right model here because
 * the measured box is the actual observed extent of the item, not a guess.
 *
 * All constants are public so a future tuning pass can adjust them without
 * touching the formula.
 */
object LitterWeightEstimator {

    /** Fraction of the measured bounding box actually filled by material —
     *  litter is mostly hollow/crumpled, not a solid block. */
    const val VOLUME_FILL_FRACTION = 0.3

    /** g/L for flexible film items: bags, wrappers, sheet plastic. */
    const val DENSITY_FILM_G_PER_L = 40

    /** g/L for rigid hollow containers: plastic bottles, cups. */
    const val DENSITY_CONTAINER_G_PER_L = 90

    /** g/L for glass — solid, dense material, not hollow-container-scaled. */
    const val DENSITY_GLASS_G_PER_L = 300

    /** g/L for metal cans. */
    const val DENSITY_CAN_G_PER_L = 120

    /** g/L for paper/cardboard: boxes, cartons, loose paper. */
    const val DENSITY_PAPER_G_PER_L = 80

    /** g/L fallback for anything not matched by a more specific bucket. */
    const val DENSITY_DEFAULT_G_PER_L = 80

    /**
     * `volumeLiters = widthM * heightM * depthM * 1000`,
     * `grams = (volumeLiters * VOLUME_FILL_FRACTION * densityFor(itemType)).roundToInt().coerceAtLeast(1)`.
     * Always at least 1 gram — a degenerate (zero-area) measurement must
     * never file a zero-weight item.
     */
    fun estimateGrams(itemType: String, widthM: Float, heightM: Float, depthM: Float): Int {
        val volumeLiters = widthM.toDouble() * heightM.toDouble() * depthM.toDouble() * 1000.0
        val grams = volumeLiters * VOLUME_FILL_FRACTION * densityFor(itemType)
        return grams.roundToInt().coerceAtLeast(1)
    }

    /**
     * Density bucket for [itemType] (a [com.gcc.app.platform.TrashItemCatalog]
     * canonical type, e.g. "glass_bottle", "plastic_bag" — but matched by
     * substring so it degrades gracefully on raw/unclassified labels too).
     * "glass" is checked before "bottle"/"container" so "glass_bottle" —
     * which carries both tokens — resolves to solid glass density, not the
     * hollow-container one.
     */
    private fun densityFor(itemType: String): Int {
        val type = itemType.lowercase()
        return when {
            type.contains("glass") -> DENSITY_GLASS_G_PER_L
            type.contains("bag") || type.contains("wrapper") || type.contains("film") -> DENSITY_FILM_G_PER_L
            type.contains("bottle") || type.contains("cup") || type.contains("container") -> DENSITY_CONTAINER_G_PER_L
            type.contains("can") -> DENSITY_CAN_G_PER_L
            type.contains("paper") || type.contains("box") || type.contains("carton") -> DENSITY_PAPER_G_PER_L
            else -> DENSITY_DEFAULT_G_PER_L
        }
    }
}
