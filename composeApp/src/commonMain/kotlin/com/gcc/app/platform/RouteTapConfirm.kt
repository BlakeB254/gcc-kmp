package com.gcc.app.platform

import kotlin.math.round

/** grams per pound — hotspots speak lbs (CreateHotspotRequest.estimatedWeightLbs),
 *  markers speak grams (ArDetectedMarkerRequest.estimatedWeightGrams). */
private const val GRAMS_PER_POUND = 453.59237

data class RouteTapFiling(
    val itemType: String,
    val estimatedWeightGrams: Int,
    val estimatedWeightLbs: Double,
    val severity: Int,
)

/**
 * Filing payload for a route-lap tap confirmation: canonical type plus the
 * type's TYPICAL weight (no LiDAR box on the tap path — same fallback the
 * env scan's tap confirm uses), in both units its two consumers need.
 * Null when the candidate never geo-resolved (no lat/lng) — callers must
 * treat that as "nothing to file", never a zero-coordinate hotspot.
 */
fun routeTapFiling(candidate: TapCandidateState): RouteTapFiling? {
    if (candidate.lat == null || candidate.lng == null) return null
    val itemType = TrashItemCatalog.canonicalType(candidate.label ?: UNKNOWN_LITTER_LABEL)
    val grams = TrashItemCatalog.estimateWeightGrams(itemType, null)
    return RouteTapFiling(
        itemType = itemType,
        estimatedWeightGrams = grams,
        estimatedWeightLbs = round(grams / GRAMS_PER_POUND * 100) / 100,
        severity = 1,
    )
}
