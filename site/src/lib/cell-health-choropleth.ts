/**
 * Web mirror of composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/
 * CellHealthChoropleth.kt — the far-zoom cell-health choropleth aggregation
 * (v2 Pillar 8 amendment). Each tile is colored by its OWN constituents
 * (litter weight vs clean-coverage vs stale), replacing the point-density
 * spot heatmap. See the Kotlin file for the full rationale on every constant.
 *
 * IMPORTANT: this file and CellHealthChoropleth.kt must stay in lockstep by
 * hand (Kotlin Multiplatform doesn't reach the Next.js site — same
 * duplication contract as litter-footprints.ts <-> GeoJsonLayers.kt).
 */

export type HealthState = 'clean' | 'dirty' | 'stale' | 'unscanned'

export interface HealthTile {
  centerLat: number
  centerLng: number
  /** [0,1]: 1 = clean-scanned, 0 = heavy-litter / never-refreshed. */
  health: number
  dominant: HealthState
}

/** Minimal coverage-cell shape this module needs (GET /api/map/coverage). */
export interface CoverageCellInput {
  lat: number
  lng: number
  state: string // 'fresh' | 'stale'
  quality?: string | null // 'seen' | 'grazed' | 'close'
}

/** Minimal observation shape this module needs (GET /api/map/observations). */
export interface ObservationInput {
  lat: number
  lng: number
  state: string // 'dirty' | 'clean'
  estimatedWeightGrams?: number | null
}

const QUALITY_WEIGHT_CLOSE = 1.0
const QUALITY_WEIGHT_GRAZED = 0.6
const QUALITY_WEIGHT_SEEN = 0.3
const QUALITY_WEIGHT_DEFAULT = 0.5

const STALE_CREDIT = 0.5
const LITTER_SATURATION_GRAMS = 4000.0
const DEFAULT_DIRTY_OBSERVATION_WEIGHT_GRAMS = 150.0
const DIRTY_DOMINANT_LITTER_PENALTY_THRESHOLD = 0.5
const CLEAN_DOMINANT_HEALTH_THRESHOLD = 0.66
const DIRTY_DOMINANT_HEALTH_THRESHOLD = 0.34

function qualityWeight(quality: string | null | undefined): number {
  switch (quality) {
    case 'close':
      return QUALITY_WEIGHT_CLOSE
    case 'grazed':
      return QUALITY_WEIGHT_GRAZED
    case 'seen':
      return QUALITY_WEIGHT_SEEN
    default:
      return QUALITY_WEIGHT_DEFAULT
  }
}

/**
 * Bin size (meters) for the far-zoom choropleth from the map's zoom — mirror
 * of the Kotlin `choroplethTileMetersForZoom`. Coarser at world/city zoom
 * (bounded tile count over a huge viewport), finer approaching the footprint
 * hand-off where the choropleth cedes to true-scale footprints.
 */
export function choroplethTileMetersForZoom(zoom: number): number {
  if (zoom < 6.0) return 6400.0
  if (zoom < 8.0) return 3200.0
  if (zoom < 9.5) return 1600.0
  if (zoom < 11.0) return 800.0
  if (zoom < 12.5) return 400.0
  if (zoom < 13.5) return 200.0
  return 100.0
}

/**
 * Aggregates the coverage ledger + dirty litter observations into
 * `tileMeters`-sized health tiles. Mirrors CellHealthChoropleth.kt's
 * `aggregateCellHealth` exactly — bins on one global equirectangular grid
 * anchored at the equator/prime meridian (a stable anchor, never the moving
 * camera), a bin with neither coverage nor a dirty observation is absent
 * from the result (never fabricated), and clean-state observations are not
 * counted.
 */
export function aggregateCellHealth(
  coverageCells: CoverageCellInput[],
  observations: ObservationInput[],
  tileMeters: number
): HealthTile[] {
  if (tileMeters <= 0.0) return []
  if (coverageCells.length === 0 && observations.length === 0) return []

  const dirtyObservations = observations.filter((o) => o.state === 'dirty')
  if (coverageCells.length === 0 && dirtyObservations.length === 0) return []

  const lats = [...coverageCells.map((c) => c.lat), ...dirtyObservations.map((o) => o.lat)]
  const avgLat = lats.reduce((sum, v) => sum + v, 0) / lats.length
  const metersPerDegreeLat = 111_320.0
  const metersPerDegreeLng = Math.max(metersPerDegreeLat * Math.cos((avgLat * Math.PI) / 180.0), 1.0)

  interface Bin {
    row: number
    col: number
    cells: CoverageCellInput[]
    dirtyWeightGrams: number[]
  }

  const bins = new Map<string, Bin>()
  const ensureBin = (lat: number, lng: number): Bin => {
    const row = Math.floor((lat * metersPerDegreeLat) / tileMeters)
    const col = Math.floor((lng * metersPerDegreeLng) / tileMeters)
    const key = `${row}:${col}`
    let bin = bins.get(key)
    if (!bin) {
      bin = { row, col, cells: [], dirtyWeightGrams: [] }
      bins.set(key, bin)
    }
    return bin
  }

  for (const cell of coverageCells) {
    ensureBin(cell.lat, cell.lng).cells.push(cell)
  }
  for (const obs of dirtyObservations) {
    const weight =
      typeof obs.estimatedWeightGrams === 'number' && obs.estimatedWeightGrams > 0
        ? obs.estimatedWeightGrams
        : DEFAULT_DIRTY_OBSERVATION_WEIGHT_GRAMS
    ensureBin(obs.lat, obs.lng).dirtyWeightGrams.push(weight)
  }

  const tiles: HealthTile[] = []
  for (const bin of bins.values()) {
    const centerLat = ((bin.row + 0.5) * tileMeters) / metersPerDegreeLat
    const centerLng = ((bin.col + 0.5) * tileMeters) / metersPerDegreeLng

    const totalLitterWeight = bin.dirtyWeightGrams.reduce((sum, v) => sum + v, 0)
    const litterPenalty = Math.min(Math.max(totalLitterWeight / LITTER_SATURATION_GRAMS, 0.0), 1.0)

    const hasCoverage = bin.cells.length > 0
    let coverageComponent: number
    if (!hasCoverage) {
      coverageComponent = 0.5
    } else {
      const qualitySum = bin.cells.reduce(
        (sum, cell) => sum + (cell.state === 'stale' ? STALE_CREDIT : qualityWeight(cell.quality)),
        0.0
      )
      coverageComponent = Math.min(Math.max(qualitySum / bin.cells.length, 0.0), 1.0)
    }

    const health = Math.min(Math.max(coverageComponent * (1.0 - litterPenalty), 0.0), 1.0)
    const allStale = hasCoverage && bin.cells.every((c) => c.state === 'stale')

    let dominant: HealthState
    if (litterPenalty >= DIRTY_DOMINANT_LITTER_PENALTY_THRESHOLD) {
      dominant = 'dirty'
    } else if (!hasCoverage) {
      dominant = 'unscanned'
    } else if (allStale) {
      dominant = 'stale'
    } else if (health >= CLEAN_DOMINANT_HEALTH_THRESHOLD) {
      dominant = 'clean'
    } else if (health <= DIRTY_DOMINANT_HEALTH_THRESHOLD) {
      dominant = 'dirty'
    } else {
      dominant = 'stale'
    }

    tiles.push({ centerLat, centerLng, health, dominant })
  }
  return tiles
}

interface ChoroplethFeature {
  type: 'Feature'
  properties: { health: number; dominant: HealthState }
  geometry: { type: 'Polygon'; coordinates: [number, number][][] }
}

export interface ChoroplethFeatureCollection {
  type: 'FeatureCollection'
  features: ChoroplethFeature[]
}

/**
 * Builds the GeoJSON FeatureCollection for the choropleth — one square
 * Feature per tile carrying `health` (0..1) + `dominant`, so one data-driven
 * fill layer colors every tile. Mirrors GeoJsonLayers.kt's
 * `buildChoroplethGeoJson` (per-feature longitude scale off the tile's own
 * latitude).
 */
export function buildChoroplethGeoJson(tiles: HealthTile[], tileMeters: number): ChoroplethFeatureCollection {
  const halfLat = tileMeters / 2.0 / 111_320.0
  const features = tiles.map((tile): ChoroplethFeature => {
    const lngScale = Math.max(Math.cos((tile.centerLat * Math.PI) / 180.0), 0.01)
    const halfLng = tileMeters / 2.0 / (111_320.0 * lngScale)
    const lat = tile.centerLat
    const lng = tile.centerLng
    return {
      type: 'Feature',
      properties: { health: tile.health, dominant: tile.dominant },
      geometry: {
        type: 'Polygon',
        coordinates: [
          [
            [lng - halfLng, lat - halfLat],
            [lng + halfLng, lat - halfLat],
            [lng + halfLng, lat + halfLat],
            [lng - halfLng, lat + halfLat],
            [lng - halfLng, lat - halfLat],
          ],
        ],
      },
    }
  })
  return { type: 'FeatureCollection', features }
}
