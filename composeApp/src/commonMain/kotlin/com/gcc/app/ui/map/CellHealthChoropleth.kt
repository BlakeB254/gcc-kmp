package com.gcc.app.ui.map

import com.gcc.shared.model.CommunityReport
import com.gcc.shared.model.CommunityReportStatus
import com.gcc.shared.model.MapCoverageCell
import com.gcc.shared.model.WorldObservation
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor

/**
 * clean / dirty / stale / unscanned — the SAME four-state visual language as
 * the per-cell coverage tiles (see [buildCoverageStateGeoJsons]'s green/red/
 * amber/fog system), extended to far-zoom aggregated cells (v2 Pillar 8
 * amendment, choropleth — spec: docs/superpowers/plans/
 * 2026-07-08-choropleth-heatmap.md).
 */
enum class HealthState { CLEAN, DIRTY, STALE, UNSCANNED }

/**
 * One aggregated far-zoom tile: a [tileMeters]-sized bin of the underlying
 * 10m coverage ledger + litter observations + reported spots, colored by its
 * OWN constituents (litter weight vs clean-coverage vs stale) — not a blurred
 * point-density kernel.
 *
 * [health] is the continuous [0,1] scale (1 = clean-scanned, 0 = heavy-
 * litter/never-refreshed). [dominant] is the categorical bucket a renderer
 * can filter/color on directly.
 */
data class HealthTile(
    val centerLat: Double,
    val centerLng: Double,
    val health: Double,
    val dominant: HealthState,
)

private const val QUALITY_WEIGHT_CLOSE = 1.0
private const val QUALITY_WEIGHT_GRAZED = 0.6
private const val QUALITY_WEIGHT_SEEN = 0.3
private const val QUALITY_WEIGHT_DEFAULT = 0.5

/**
 * A stale cell still counts as "coverage exists" — it was scanned before, it
 * just needs a refresh — so it earns half credit toward the tile's clean
 * baseline instead of zero.
 */
private const val STALE_CREDIT = 0.5

/**
 * Cumulative weighted litter (grams) in a bin at which the litter penalty
 * saturates at 1.0 (full red, regardless of scan quality) — roughly a
 * mattress/tire-scale pile, not a single cigarette butt.
 */
private const val LITTER_SATURATION_GRAMS = 4000.0

/**
 * Fallback litter weight (grams) for a dirty observation with no
 * [WorldObservation.estimatedWeightGrams] on record.
 */
private const val DEFAULT_DIRTY_OBSERVATION_WEIGHT_GRAMS = 150.0

/**
 * Litter weight (grams) attributed to an active community spot report so
 * REPORTED messes stay representable at far zoom until the ground is scanned
 * over (product feedback 2026-07-08: switching the far-zoom view to a
 * coverage choropleth must not make reported messes vanish — a big reported
 * pile should still read as a hot region, just not as a generic pin). A
 * report carries no measured weight, so it's estimated from severity ×
 * status: a verified high-severity report (~3600g) clears the DIRTY
 * threshold on its own (a large mess reads red); a plain pending low report
 * (~500g) reads as light attention. Once the cell is scanned, the coverage
 * ledger + route-scan observations drive the health and (when the report is
 * resolved) it drops out of this set — "update with the scanned data."
 */
private const val SPOT_LITTER_BASE_GRAMS = 500.0

private fun spotLitterGrams(spot: CommunityReport): Double {
    val severityFactor = when (spot.aiAnalysis?.severity) {
        "high" -> 6.0
        "medium" -> 2.4
        else -> 1.0
    }
    val statusFactor = when (spot.status) {
        CommunityReportStatus.VERIFIED -> 1.2
        CommunityReportStatus.ACKNOWLEDGED -> 1.1
        CommunityReportStatus.PENDING -> 1.0
        else -> 0.8
    }
    return SPOT_LITTER_BASE_GRAMS * severityFactor * statusFactor
}

/**
 * Heavy litter always dominates the tile's category regardless of how clean
 * its scan history is — a scanned-clean cell with a mattress dumped on it
 * reads DIRTY, not CLEAN.
 */
private const val DIRTY_DOMINANT_LITTER_PENALTY_THRESHOLD = 0.5

private const val CLEAN_DOMINANT_HEALTH_THRESHOLD = 0.66
private const val DIRTY_DOMINANT_HEALTH_THRESHOLD = 0.34

private fun qualityWeight(quality: String): Double = when (quality) {
    "close" -> QUALITY_WEIGHT_CLOSE
    "grazed" -> QUALITY_WEIGHT_GRAZED
    "seen" -> QUALITY_WEIGHT_SEEN
    else -> QUALITY_WEIGHT_DEFAULT
}

/**
 * Picks a bin size (meters) for the far-zoom choropleth from the map's
 * TARGET zoom (same convention as `useOutlineCoverage` in GccMap.ios.kt).
 * Coarser at world/city zoom (keeps tile count bounded over a huge
 * viewport), finer as zoom approaches the footprint hand-off (~14.2) where
 * the choropleth is about to cede the story to true-scale footprints anyway.
 */
fun choroplethTileMetersForZoom(zoom: Double): Double = when {
    zoom < 6.0 -> 6400.0
    zoom < 8.0 -> 3200.0
    zoom < 9.5 -> 1600.0
    zoom < 11.0 -> 800.0
    // Street-approach band: the choropleth is an ESTIMATE standing in for
    // scan truth, so as the camera nears the zoom where real coverage blobs
    // become legible its tiles must get small enough to read as texture
    // rather than as slabs. 400/200/100m read as "the whole block is red";
    // halved so problem areas localise. (Blake, 2026-07-09 field screenshot.)
    zoom < 12.5 -> 200.0
    zoom < 13.5 -> 100.0
    else -> 50.0
}

/**
 * Aggregates the 10m coverage ledger + litter observations + active community
 * spot reports into [tileMeters]-sized health tiles: each tile is colored by
 * its OWN constituents (litter weight vs clean-coverage vs stale), not a
 * blurred density kernel — the far-zoom choropleth (v2 Pillar 8 amendment).
 *
 * Binning: all inputs are snapped onto ONE global equirectangular grid
 * (anchored at the equator/prime meridian, not a moving camera-relative
 * anchor) using a single longitude scale derived from the combined inputs'
 * average latitude — precise enough for a far-zoom overview tile.
 *
 * A bin with NEITHER a coverage cell NOR a dirty observation NOR an active
 * report is never manufactured — it's simply absent from the result (the
 * world-fog layer already renders the "never scanned anywhere near here"
 * veil at every zoom). Every bin the result DOES describe therefore has
 * something to say; a bin that exists only because of reported/observed
 * litter reads as a warm unscanned MESS (amber→red), never neutral grey —
 * that is what keeps reported messes visible until the ground is scanned.
 *
 * Clean-state observations (confirmed-no-litter checks) are NOT counted —
 * the coverage ledger is the authoritative "scanned clean" signal; only
 * 'dirty' observations and unresolved reports feed the litter penalty.
 */
fun aggregateCellHealth(
    coverageCells: List<MapCoverageCell>,
    observations: List<WorldObservation>,
    spots: List<CommunityReport>,
    tileMeters: Double,
): List<HealthTile> {
    if (tileMeters <= 0.0) return emptyList()

    val dirtyObservations = observations.filter { it.state == "dirty" }
    // Active (unresolved) community reports keep reported messes on the map
    // until the ground is scanned/the report is resolved — same "active spot"
    // filter the coverage-state tiles use (buildCoverageStateGeoJsons).
    val activeSpots = spots.filter { it.status != CommunityReportStatus.RESOLVED }
    if (coverageCells.isEmpty() && dirtyObservations.isEmpty() && activeSpots.isEmpty()) return emptyList()

    val avgLat = (coverageCells.map { it.lat } + dirtyObservations.map { it.lat } +
        activeSpots.map { it.coordinates.lat }).average()
    val metersPerDegreeLat = 111_320.0
    val metersPerDegreeLng = (metersPerDegreeLat * cos(avgLat * PI / 180.0)).coerceAtLeast(1.0)

    fun binKey(lat: Double, lng: Double): Pair<Long, Long> {
        val row = floor((lat * metersPerDegreeLat) / tileMeters).toLong()
        val col = floor((lng * metersPerDegreeLng) / tileMeters).toLong()
        return row to col
    }

    class Bin {
        val cells = mutableListOf<MapCoverageCell>()
        val litterGrams = mutableListOf<Double>()
    }

    val bins = LinkedHashMap<Pair<Long, Long>, Bin>()
    coverageCells.forEach { cell ->
        bins.getOrPut(binKey(cell.lat, cell.lng)) { Bin() }.cells.add(cell)
    }
    dirtyObservations.forEach { obs ->
        val weight = obs.estimatedWeightGrams?.takeIf { it > 0 }?.toDouble()
            ?: DEFAULT_DIRTY_OBSERVATION_WEIGHT_GRAMS
        bins.getOrPut(binKey(obs.lat, obs.lng)) { Bin() }.litterGrams.add(weight)
    }
    activeSpots.forEach { spot ->
        bins.getOrPut(binKey(spot.coordinates.lat, spot.coordinates.lng)) { Bin() }
            .litterGrams.add(spotLitterGrams(spot))
    }

    return bins.map { (key, bin) ->
        val (row, col) = key
        val centerLat = (row + 0.5) * tileMeters / metersPerDegreeLat
        val centerLng = (col + 0.5) * tileMeters / metersPerDegreeLng

        val totalLitterWeight = bin.litterGrams.sum()
        val hasLitter = totalLitterWeight > 0.0
        val litterPenalty = (totalLitterWeight / LITTER_SATURATION_GRAMS).coerceIn(0.0, 1.0)

        val hasCoverage = bin.cells.isNotEmpty()
        val coverageComponent = if (!hasCoverage) {
            // No scan record here yet — a bin only exists because of reported/
            // observed litter, so keep it in the amber→red half (0.5 * …); it
            // must read as an unscanned MESS, never as clean green.
            0.5
        } else {
            val qualitySum = bin.cells.sumOf { cell ->
                if (cell.state == "stale") STALE_CREDIT else qualityWeight(cell.quality)
            }
            (qualitySum / bin.cells.size).coerceIn(0.0, 1.0)
        }

        val health = (coverageComponent * (1.0 - litterPenalty)).coerceIn(0.0, 1.0)
        val allStale = hasCoverage && bin.cells.all { it.state == "stale" }

        val dominant = when {
            litterPenalty >= DIRTY_DOMINANT_LITTER_PENALTY_THRESHOLD -> HealthState.DIRTY
            // Reported/observed mess with no scan yet: warm attention (colored
            // by health above), never the neutral grey veil — that would hide
            // exactly the messes we want to keep showing until they're cleaned.
            !hasCoverage -> HealthState.DIRTY
            allStale && !hasLitter -> HealthState.STALE
            health >= CLEAN_DOMINANT_HEALTH_THRESHOLD -> HealthState.CLEAN
            health <= DIRTY_DOMINANT_HEALTH_THRESHOLD -> HealthState.DIRTY
            else -> HealthState.STALE
        }

        HealthTile(centerLat = centerLat, centerLng = centerLng, health = health, dominant = dominant)
    }
}
