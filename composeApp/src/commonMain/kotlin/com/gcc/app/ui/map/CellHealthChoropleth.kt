package com.gcc.app.ui.map

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
 * 10m coverage ledger + litter observations, colored by its OWN
 * constituents — not a blurred point-density kernel.
 *
 * [health] is the continuous [0,1] scale (1 = clean-scanned, 0 = heavy-
 * litter/never-refreshed). [dominant] is the categorical bucket a renderer
 * can filter/color on directly — carried alongside [health] (rather than as
 * four separate fixed-color tile layers, the coverage-tile precedent)
 * because the choropleth is ONE data-driven fill layer, not four; see
 * `GeoJsonLayers.kt`'s `buildChoroplethGeoJson`.
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
 * baseline instead of zero (zero would read identically to litter, which is
 * a much stronger negative signal than "due for a rescan").
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
 * [WorldObservation.estimatedWeightGrams] on record — the rough order of
 * magnitude of TrashItemCatalog's "other" default, not a precise figure; it
 * only needs to nudge a bin toward "some litter present" when the real
 * weight is unknown.
 */
private const val DEFAULT_DIRTY_OBSERVATION_WEIGHT_GRAMS = 150.0

/**
 * Heavy litter always dominates the tile's category regardless of how clean
 * its scan history is — a scanned-clean cell with a mattress dumped on it
 * reads DIRTY, not CLEAN (the coverage-tile system makes the same call:
 * `dirty` overrides `clean` whenever a spot is nearby — see
 * [buildCoverageStateGeoJsons]'s `hasSpotNear`).
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
 * TARGET zoom (same convention as `useOutlineCoverage` in GccMap.ios.kt —
 * the caller-set zoom, not the live pinch-gesture zoom; re-aggregating on
 * every fractional pinch tick would be expensive Compose churn for a purely
 * cosmetic bin-size change — deferred, same trade-off already accepted
 * there). Coarser at world/city zoom (keeps tile count bounded over a huge
 * viewport), finer as zoom approaches the footprint hand-off (~14.2) where
 * the choropleth is about to cede the story to true-scale footprints anyway.
 */
fun choroplethTileMetersForZoom(zoom: Double): Double = when {
    zoom < 6.0 -> 6400.0
    zoom < 8.0 -> 3200.0
    zoom < 9.5 -> 1600.0
    zoom < 11.0 -> 800.0
    zoom < 12.5 -> 400.0
    zoom < 13.5 -> 200.0
    else -> 100.0
}

/**
 * Aggregates the 10m coverage ledger + litter observations into
 * [tileMeters]-sized health tiles: each tile is colored by its OWN
 * constituents (litter weight vs clean-coverage vs stale), not a blurred
 * density kernel — the far-zoom choropleth (v2 Pillar 8 amendment).
 *
 * Binning: both inputs are snapped onto ONE global equirectangular grid
 * (anchored at the equator/prime meridian, not a moving camera-relative
 * anchor — see [buildCoverageStateGeoJsons]'s `anchorLat`/`anchorLng` doc for
 * why a moving anchor is the wrong call) using a single longitude scale
 * derived from the combined inputs' average latitude — precise enough for a
 * far-zoom overview tile, not meant to be geodesically exact.
 *
 * A bin with NEITHER a coverage cell NOR a dirty observation is never
 * manufactured — it's simply absent from the result. The existing world-fog
 * layer already renders the "never scanned anywhere near here" veil at every
 * zoom (see `coverage-fog` in GccMap.ios.kt); this function only ever
 * describes bins where there IS something to say, matching the "never-
 * scanned = neutral grey veil" language from the fog system rather than
 * re-painting it per bin.
 *
 * Clean-state observations (confirmed-no-litter checks) are NOT counted —
 * the coverage ledger is the authoritative "this ground was scanned clean"
 * signal; only 'dirty' observations feed the litter penalty.
 */
fun aggregateCellHealth(
    coverageCells: List<MapCoverageCell>,
    observations: List<WorldObservation>,
    tileMeters: Double,
): List<HealthTile> {
    if (tileMeters <= 0.0) return emptyList()
    if (coverageCells.isEmpty() && observations.isEmpty()) return emptyList()

    val dirtyObservations = observations.filter { it.state == "dirty" }
    if (coverageCells.isEmpty() && dirtyObservations.isEmpty()) return emptyList()

    val avgLat = (coverageCells.map { it.lat } + dirtyObservations.map { it.lat }).average()
    val metersPerDegreeLat = 111_320.0
    val metersPerDegreeLng = (metersPerDegreeLat * cos(avgLat * PI / 180.0)).coerceAtLeast(1.0)

    fun binKey(lat: Double, lng: Double): Pair<Long, Long> {
        val row = floor((lat * metersPerDegreeLat) / tileMeters).toLong()
        val col = floor((lng * metersPerDegreeLng) / tileMeters).toLong()
        return row to col
    }

    class Bin {
        val cells = mutableListOf<MapCoverageCell>()
        val dirtyWeightGrams = mutableListOf<Double>()
    }

    val bins = LinkedHashMap<Pair<Long, Long>, Bin>()
    coverageCells.forEach { cell ->
        bins.getOrPut(binKey(cell.lat, cell.lng)) { Bin() }.cells.add(cell)
    }
    dirtyObservations.forEach { obs ->
        val weight = obs.estimatedWeightGrams?.takeIf { it > 0 }?.toDouble()
            ?: DEFAULT_DIRTY_OBSERVATION_WEIGHT_GRAMS
        bins.getOrPut(binKey(obs.lat, obs.lng)) { Bin() }.dirtyWeightGrams.add(weight)
    }

    return bins.map { (key, bin) ->
        val (row, col) = key
        val centerLat = (row + 0.5) * tileMeters / metersPerDegreeLat
        val centerLng = (col + 0.5) * tileMeters / metersPerDegreeLng

        val totalLitterWeight = bin.dirtyWeightGrams.sum()
        val litterPenalty = (totalLitterWeight / LITTER_SATURATION_GRAMS).coerceIn(0.0, 1.0)

        val hasCoverage = bin.cells.isNotEmpty()
        val coverageComponent = if (!hasCoverage) {
            0.5 // neutral baseline — no scan record in this bin at all
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
            !hasCoverage -> HealthState.UNSCANNED
            allStale -> HealthState.STALE
            health >= CLEAN_DOMINANT_HEALTH_THRESHOLD -> HealthState.CLEAN
            health <= DIRTY_DOMINANT_HEALTH_THRESHOLD -> HealthState.DIRTY
            else -> HealthState.STALE
        }

        HealthTile(centerLat = centerLat, centerLng = centerLng, health = health, dominant = dominant)
    }
}
