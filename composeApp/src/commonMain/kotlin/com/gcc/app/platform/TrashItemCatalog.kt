package com.gcc.app.platform

import kotlin.math.roundToInt

/**
 * Canonical litter taxonomy + physical estimates, shared by both platforms so
 * the close-range confirm flow, minimap glyphs, and map icons all agree on
 * item types. Weight comes from a per-type lookup scaled by observed size —
 * type-specific typical masses beat volume-times-density because most litter
 * is hollow (bottles, cans, wrappers).
 */
data class TrashItemInfo(
    /** Canonical type persisted as world_observations.itemType. */
    val itemType: String,
    /** Human label for confirm UI. */
    val displayName: String,
    /** Typical mass of one item in grams at [typicalWidthMeters]. */
    val typicalWeightGrams: Int,
    /** Width the typical weight was calibrated at. */
    val typicalWidthMeters: Double,
    /**
     * How mass scales with linear size: 1.0 = mass grows linearly with width
     * (sheet-like: bags, paper), 2.0 = with area, 3.0 = with volume (solid).
     * Hollow containers sit near 2.0 (wall material grows with surface area).
     */
    val massScaleExponent: Double,
)

object TrashItemCatalog {

    const val FALLBACK_TYPE = "other"

    /** Weight estimates are clamped to this range to keep outliers sane. */
    private const val MIN_WEIGHT_GRAMS = 1
    private const val MAX_WEIGHT_GRAMS = 20_000

    private val CATALOG: List<TrashItemInfo> = listOf(
        TrashItemInfo("plastic_bottle", "Plastic bottle", 22, 0.23, 2.0),
        TrashItemInfo("glass_bottle", "Glass bottle", 280, 0.25, 2.5),
        TrashItemInfo("can", "Can", 15, 0.12, 2.0),
        TrashItemInfo("plastic_bag", "Plastic bag", 8, 0.30, 1.0),
        TrashItemInfo("cup", "Cup", 12, 0.11, 2.0),
        TrashItemInfo("paper", "Paper", 5, 0.20, 1.0),
        TrashItemInfo("wrapper", "Wrapper", 3, 0.12, 1.0),
        TrashItemInfo("carton", "Carton", 30, 0.20, 2.0),
        TrashItemInfo("cigarette", "Cigarette butt", 1, 0.03, 1.0),
        TrashItemInfo("food_waste", "Food waste", 80, 0.12, 3.0),
        TrashItemInfo("tire", "Tire", 9_000, 0.60, 3.0),
        TrashItemInfo("mattress", "Mattress", 25_000, 1.50, 2.0),
        TrashItemInfo(FALLBACK_TYPE, "Litter", 25, 0.15, 2.0),
    )

    private val BY_TYPE: Map<String, TrashItemInfo> = CATALOG.associateBy { it.itemType }

    /**
     * Maps a detection label (Vision identifier token or a
     * [TrashDetectionRules.TRASH_TOKENS] display label like "Bottle") to a
     * canonical type. Glass/plastic split keys off accompanying tokens, so
     * pass the full raw identifier when available.
     */
    fun canonicalType(label: String): String {
        val tokens = TrashDetectionRules.tokensOf(label)
        fun has(vararg words: String) = tokens.any { it in words }
        return when {
            has("cigarette", "butt") -> "cigarette"
            has("bottle") -> if (has("glass")) "glass_bottle" else "plastic_bottle"
            has("can", "tin", "aluminum") -> "can"
            has("bag", "sack") -> "plastic_bag"
            has("cup", "mug") -> "cup"
            has("paper", "cardboard", "newspaper", "document") -> "paper"
            has("wrapper", "foil", "film") -> "wrapper"
            has("carton", "box") -> "carton"
            has("food", "fruit", "banana", "apple") -> "food_waste"
            has("tire", "tyre") -> "tire"
            has("mattress") -> "mattress"
            has("glass") -> "glass_bottle"
            has("plastic") -> "plastic_bottle"
            else -> FALLBACK_TYPE
        }
    }

    fun infoFor(itemType: String): TrashItemInfo = BY_TYPE[itemType] ?: BY_TYPE.getValue(FALLBACK_TYPE)

    /**
     * Estimates weight in grams for an item of [itemType] whose observed
     * width (from LiDAR depth + bounding-box angular size) is [widthMeters].
     * Null/absurd widths fall back to the type's typical weight.
     */
    fun estimateWeightGrams(itemType: String, widthMeters: Double?): Int {
        val info = infoFor(itemType)
        if (widthMeters == null || widthMeters !in 0.01..3.0) return info.typicalWeightGrams
        val ratio = widthMeters / info.typicalWidthMeters
        val scaled = info.typicalWeightGrams * pow(ratio, info.massScaleExponent)
        return scaled.roundToInt().coerceIn(MIN_WEIGHT_GRAMS, MAX_WEIGHT_GRAMS)
    }

    private fun pow(base: Double, exp: Double): Double {
        if (base <= 0.0) return 0.0
        return kotlin.math.exp(exp * kotlin.math.ln(base))
    }
}
