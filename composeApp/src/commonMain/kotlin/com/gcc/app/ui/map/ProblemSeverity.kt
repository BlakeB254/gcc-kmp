package com.gcc.app.ui.map

/**
 * How bad a problem area reads on the map. Hue encodes severity; a merged
 * marching-squares outline cannot carry per-cell attributes, so cells are
 * bucketed into tiers and each tier is merged into its own blob (the same
 * reason clean/grazed/seen are three separate collections).
 */
enum class ProblemSeverity { LIGHT, MODERATE, SEVERE }

/** ~2kg — a bag's worth of litter. */
const val SEVERITY_MODERATE_GRAMS = 2_000.0

/** ~15kg — a dumped pile, not scattered litter. */
const val SEVERITY_SEVERE_GRAMS = 15_000.0

/** Count fallback, used only when no observation in the cell carried a weight. */
const val SEVERITY_MODERATE_COUNT = 3
const val SEVERITY_SEVERE_COUNT = 6

/**
 * Tier a problem cell by mass, falling back to item count when nothing in the
 * cell was ever weighed.
 *
 * The fallback matters: a cell can hold several observations and still report
 * `litterWeightGrams == 0` (weight is only estimated on a close-range,
 * diagnosed capture). Tiering such a cell by weight alone would silently paint
 * a pile of un-weighed trash as LIGHT.
 *
 * A cell that is a problem solely because of an unresolved community report
 * (`litterCount == 0`, no weight) tiers LIGHT — a human said something is here,
 * but no scan has characterised it yet.
 */
fun problemSeverityTier(litterCount: Int, litterWeightGrams: Double): ProblemSeverity {
    if (litterWeightGrams > 0.0) {
        return when {
            litterWeightGrams >= SEVERITY_SEVERE_GRAMS -> ProblemSeverity.SEVERE
            litterWeightGrams >= SEVERITY_MODERATE_GRAMS -> ProblemSeverity.MODERATE
            else -> ProblemSeverity.LIGHT
        }
    }
    return when {
        litterCount >= SEVERITY_SEVERE_COUNT -> ProblemSeverity.SEVERE
        litterCount >= SEVERITY_MODERATE_COUNT -> ProblemSeverity.MODERATE
        else -> ProblemSeverity.LIGHT
    }
}
