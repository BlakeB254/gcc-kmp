package com.gcc.app.viewmodel

import com.gcc.app.platform.TrashItemCatalog
import com.gcc.shared.model.HotspotStatus
import com.gcc.shared.model.ScanHotspot
import com.gcc.shared.model.WorldObservation

/**
 * Lap-2 progress = confirmed-cleaned estimated weight ÷ total estimated
 * weight identified in lap 1.
 *
 * "Identified in lap 1" = every hotspot the worker flagged during the scout
 * lap plus every pre-existing dirty world observation surfaced for the route.
 * "Confirmed cleaned" = hotspots with a cleaning submission (CLEANED) plus
 * observations rectified via the "Already clean" confirmation this session.
 */
data class Lap2WeightProgress(
    /** Estimated pounds confirmed cleaned so far. */
    val cleanedLbs: Double = 0.0,
    /** Estimated pounds identified in lap 1. */
    val totalLbs: Double = 0.0,
    /** Items confirmed cleaned (cleaned hotspots + rectified observations). */
    val cleanedCount: Int = 0,
    /** Items identified in lap 1 (hotspots + dirty observations). */
    val totalCount: Int = 0,
) {
    /** 0–100. Zero when nothing was identified (nothing to clean). */
    val percent: Double
        get() = if (totalLbs > 0.0) ((cleanedLbs / totalLbs) * 100.0).coerceIn(0.0, 100.0) else 0.0
}

object Lap2WeightMath {

    const val GRAMS_PER_POUND = 453.59237

    /**
     * Weight of one lap-1 hotspot in pounds: the AI/depth estimate when
     * present, otherwise the per-type typical weight from [TrashItemCatalog]
     * keyed off the hotspot's first trash tag.
     */
    fun hotspotWeightLbs(hotspot: ScanHotspot): Double =
        hotspot.estimatedWeightLbs?.takeIf { it > 0.0 && it.isFinite() }
            ?: (TrashItemCatalog.estimateWeightGrams(
                itemType = TrashItemCatalog.canonicalType(hotspot.trashTypes.firstOrNull()?.name ?: ""),
                widthMeters = null,
            ) / GRAMS_PER_POUND)

    /**
     * Weight of one expected world observation in pounds: catalog estimate
     * from its canonical itemType, scaled by measured width when available.
     * Unknown/null types fall back to the catalog's generic litter weight.
     */
    fun observationWeightLbs(observation: WorldObservation): Double =
        TrashItemCatalog.estimateWeightGrams(
            itemType = observation.itemType ?: TrashItemCatalog.FALLBACK_TYPE,
            widthMeters = observation.widthMeters,
        ) / GRAMS_PER_POUND

    /**
     * @param hotspots all hotspots recorded during lap 1 (any status).
     * @param observations dirty world observations surfaced for the route.
     * @param rectifiedObservationIds observations confirmed "Already clean"
     *   this session.
     */
    fun compute(
        hotspots: List<ScanHotspot>,
        observations: List<WorldObservation>,
        rectifiedObservationIds: Set<Int>,
    ): Lap2WeightProgress {
        val hotspotWeights = hotspots.map { it to hotspotWeightLbs(it) }
        val observationWeights = observations.map { it to observationWeightLbs(it) }

        val cleanedHotspots = hotspotWeights.filter { (h, _) -> h.status == HotspotStatus.CLEANED }
        val rectified = observationWeights.filter { (o, _) -> o.id in rectifiedObservationIds }

        return Lap2WeightProgress(
            cleanedLbs = cleanedHotspots.sumOf { it.second } + rectified.sumOf { it.second },
            totalLbs = hotspotWeights.sumOf { it.second } + observationWeights.sumOf { it.second },
            cleanedCount = cleanedHotspots.size + rectified.size,
            totalCount = hotspots.size + observations.size,
        )
    }
}
