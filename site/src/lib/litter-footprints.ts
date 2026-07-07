/**
 * Web mirror of composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/
 * GeoJsonLayers.kt's `buildObservationFootprintsGeoJson` + the
 * `typicalWidthMeters` table from composeApp/src/commonMain/kotlin/com/gcc/
 * app/platform/TrashItemCatalog.kt — the close-zoom, true-scale litter
 * footprint layer that replaces the removed spot pin/glyph/verification-ring
 * layers on both platforms (Pillar 8 Task 2 mobile, Task 3 web).
 *
 * IMPORTANT: this file and TrashItemCatalog.kt must be updated together —
 * item types, typical widths, and the visibility-floor derivation are
 * intentionally duplicated (Kotlin Multiplatform doesn't reach the Next.js
 * site) and must stay in lockstep by hand.
 */

// ── Item catalog mirror (TrashItemCatalog.kt CATALOG.typicalWidthMeters) ──

export const FALLBACK_ITEM_TYPE = 'other'

export const TYPICAL_WIDTH_METERS: Record<string, number> = {
  plastic_bottle: 0.23,
  glass_bottle: 0.25,
  can: 0.12,
  plastic_bag: 0.30,
  cup: 0.11,
  paper: 0.20,
  wrapper: 0.12,
  carton: 0.20,
  cigarette: 0.03,
  food_waste: 0.12,
  tire: 0.60,
  mattress: 1.50,
  // Crinkly foil snack bag — ~0.20-0.25m across (Pillar 8 litter-truth spec example size).
  chip_bag: 0.22,
  // Distinct from plastic_bag (0.30m, shopping-bag sized): a full kitchen/
  // lawn trash bag is bigger (~0.6m, the spec's other example size).
  trash_bag: 0.60,
  [FALLBACK_ITEM_TYPE]: 0.15,
}

export function typicalWidthMetersFor(itemType?: string | null): number {
  const key = itemType ?? FALLBACK_ITEM_TYPE
  return TYPICAL_WIDTH_METERS[key] ?? TYPICAL_WIDTH_METERS[FALLBACK_ITEM_TYPE]
}

// ── Visibility floor (GeoJsonLayers.kt FOOTPRINT_VISIBILITY_FLOOR_METERS) ─
//
// Spec: "min 2.5px at render zoom, color carries noticeability, size carries
// truth" — the minimum world-space width/diameter a footprint may render
// at, so a true-scale tiny item (a 0.03m cigarette butt, a 0.22m chip bag)
// never shrinks under 2.5 screen pixels at the zoom where this layer
// reaches full opacity (z15.5 — LITTER_FOOTPRINT_ZOOM.FADE_IN_FULL in
// design-tokens.ts). The floor is a fixed world-space constant computed
// once here, not a live per-frame value — same approach as the Kotlin
// reference.

/** Web Mercator meters-per-pixel constant: Earth's circumference
 *  (~40,075,017m) spread across a 256px tile at zoom 0 -> 156543.03392 m/px
 *  at the equator; halves every zoom level and scales by cos(latitude)
 *  elsewhere. Standard formula, shared source of truth with the Kotlin
 *  constant of the same derivation. */
const WEB_MERCATOR_MPP_AT_ZOOM0_EQUATOR = 156543.03392

/** cos(41.85 deg) — a representative Chicago latitude, used only to derive
 *  the visibility floor below (a fixed constant, not per-observation
 *  latitude — the floor just needs to be "in the right ballpark" everywhere
 *  the app operates today). */
const CHICAGO_REPRESENTATIVE_LAT_COS = 0.7447

/** 2^15.5 — the zoom level where the footprint layer reaches FULL opacity
 *  (LITTER_FOOTPRINT_ZOOM.FADE_IN_FULL in design-tokens.ts, mirroring
 *  mobile's footprintFadeInFullZoom). The visibility floor below is
 *  anchored to the END of the fade-in band — where the shapes are fully
 *  legible — not the start. */
const FOOTPRINT_FULL_OPACITY_ZOOM_POW2 = 46_340.95 // 2^15.5

/** meters/px at z15.5 for a representative Chicago latitude:
 *  156543.03392 * 0.7447 / 46340.95 ~= 2.5156 m/px. */
const METERS_PER_PIXEL_AT_FOOTPRINT_FULL_OPACITY =
  (WEB_MERCATOR_MPP_AT_ZOOM0_EQUATOR * CHICAGO_REPRESENTATIVE_LAT_COS) / FOOTPRINT_FULL_OPACITY_ZOOM_POW2

/** 2.5px * 2.5156 m/px ~= 6.29m. See GeoJsonLayers.kt's
 *  FOOTPRINT_VISIBILITY_FLOOR_METERS doc comment for the full trade-off
 *  discussion (deliberately generous at z15.5; on-screen size only ever
 *  grows at deeper zoom, so the 2.5px guarantee always holds). */
export const FOOTPRINT_VISIBILITY_FLOOR_METERS = 2.5 * METERS_PER_PIXEL_AT_FOOTPRINT_FULL_OPACITY

// ── Geometry primitives (mirror GeoJsonLayers.kt's private helpers) ──────

type LngLat = [number, number]

/**
 * Closed ring of `segments` points around (lat, lng) at `radiusMeters`.
 * Mirrors GeoJsonLayers.kt's private `circlePolygonRing`.
 */
export function circlePolygonRing(
  lat: number,
  lng: number,
  radiusMeters: number,
  segments = 32
): LngLat[] {
  const metersPerDegreeLat = 111_320.0
  const metersPerDegreeLng = Math.max(111_320.0 * Math.cos((lat * Math.PI) / 180.0), 1.0)
  const points: LngLat[] = []
  for (let index = 0; index < segments; index++) {
    const angle = (2.0 * Math.PI * index) / segments
    const dx = (Math.cos(angle) * radiusMeters) / metersPerDegreeLng
    const dy = (Math.sin(angle) * radiusMeters) / metersPerDegreeLat
    points.push([lng + dx, lat + dy])
  }
  points.push(points[0])
  return points
}

/**
 * Oriented rectangle ring for the non-vegetation branch: rotate +/-half-
 * extents around center in already-degree-converted space. Mirrors
 * GeoJsonLayers.kt's private `footprintRectRing` EXACTLY, including the
 * corner-rotation handedness — do not "simplify" this rotation without
 * re-reading that comment. yaw is a compass BEARING (clockwise-from-north),
 * not a CCW math angle: a plain CCW rotation would mirror the shape and mix
 * lat/lng-scaled axes before conversion. Rotating in meters first and
 * converting per-axis afterward (lat: /metersPerDegreeLat, lng:
 * /metersPerDegreeLng) keeps the footprint's true aspect ratio — yaw 0
 * keeps the (long) width axis east-west, yaw 90 swings it to north-south,
 * yaw 45 to bearing 135 (SE-NW).
 */
export function footprintRectRing(
  lat: number,
  lng: number,
  widthMeters: number,
  depthMeters: number,
  yawDegrees: number,
  metersPerDegreeLat: number,
  metersPerDegreeLng: number
): LngLat[] {
  const halfW = widthMeters / 2.0
  const halfD = depthMeters / 2.0
  const rad = (yawDegrees * Math.PI) / 180.0
  const cosR = Math.cos(rad)
  const sinR = Math.sin(rad)

  const corners: [number, number][] = [
    [-halfW, -halfD],
    [halfW, -halfD],
    [halfW, halfD],
    [-halfW, halfD],
  ]

  const ring: LngLat[] = corners.map(([dx, dy]) => {
    const rx = dx * cosR + dy * sinR
    const ry = -dx * sinR + dy * cosR
    const dLng = rx / metersPerDegreeLng
    const dLat = ry / metersPerDegreeLat
    return [lng + dLng, lat + dLat]
  })
  ring.push(ring[0])
  return ring
}

// ── Observation -> footprint feature ──────────────────────────────────────

/** Subset of GET /api/map/observations's row shape this module needs. */
export interface MapObservation {
  id: number
  lat: number
  lng: number
  itemType?: string | null
  domain?: string | null
  state: string
  widthMeters?: number | null
  yawDegrees?: number | null
  mediaUrl?: string | null
  estimatedWeightGrams?: number | null
  capturedAt?: string | null
}

export interface FootprintFeature {
  type: 'Feature'
  properties: { id: number; itemType: string | null; domain: string | null; state: string }
  geometry: { type: 'Polygon'; coordinates: LngLat[][] }
}

export interface FootprintFeatureCollection {
  type: 'FeatureCollection'
  features: FootprintFeature[]
}

/**
 * Builds one true-scale, oriented footprint polygon Feature for a single
 * observation. Mirrors GeoJsonLayers.kt's `buildObservationFootprintsGeoJson`
 * per-observation body exactly:
 *  - domain === 'vegetation' -> circle (12-gon) of diameter [effective width]
 *  - everything else -> oriented rectangle [effective width] x ([effective
 *    width] * 0.4), rotated by yawDegrees
 * "Effective width" = widthMeters when it's a usable measurement (finite,
 * positive), else the type's typical width, floored to
 * FOOTPRINT_VISIBILITY_FLOOR_METERS either way.
 *
 * `centerLat` anchors the lng/lat meter-scale conversion for the oriented
 * rectangles (single lngScale per caller, matching the Kotlin reference);
 * the circle branch reuses `circlePolygonRing`, which scales per-feature
 * off its own latitude.
 */
export function buildFootprintFeature(obs: MapObservation, centerLat: number): FootprintFeature {
  const metersPerDegreeLat = 111_320.0
  const metersPerDegreeLng = Math.max(111_320.0 * Math.cos((centerLat * Math.PI) / 180.0), 1.0)

  const measuredWidth =
    typeof obs.widthMeters === 'number' && Number.isFinite(obs.widthMeters) && obs.widthMeters > 0
      ? obs.widthMeters
      : null
  const rawWidth = measuredWidth ?? typicalWidthMetersFor(obs.itemType)
  const width = Math.max(rawWidth, FOOTPRINT_VISIBILITY_FLOOR_METERS)
  const yaw =
    typeof obs.yawDegrees === 'number' && Number.isFinite(obs.yawDegrees) ? obs.yawDegrees : 0

  const ring =
    obs.domain === 'vegetation'
      ? circlePolygonRing(obs.lat, obs.lng, width / 2.0, 12)
      : footprintRectRing(
          obs.lat,
          obs.lng,
          width,
          width * 0.4,
          yaw,
          metersPerDegreeLat,
          metersPerDegreeLng
        )

  return {
    type: 'Feature',
    properties: {
      id: obs.id,
      itemType: obs.itemType ?? null,
      domain: obs.domain ?? null,
      state: obs.state,
    },
    geometry: { type: 'Polygon', coordinates: [ring] },
  }
}

/**
 * Builds the full FeatureCollection for a set of observations.
 *
 * State filtering: mobile (GccMap.ios.kt / MapFullScreen.kt) applies NO
 * state filter anywhere in this pipeline — every observation the API
 * returns (both 'dirty' and 'clean') gets a footprint. This mirrors that
 * exactly rather than the "filter to dirty" behavior floated in the initial
 * task brief, per the brief's own instruction to verify and match mobile's
 * ACTUAL behavior. See the task report for the full note.
 *
 * No clustering/pillification — every observation maps to its own feature.
 */
export function buildObservationFootprintsGeoJson(
  observations: MapObservation[],
  centerLat: number
): FootprintFeatureCollection {
  return {
    type: 'FeatureCollection',
    features: observations.map((obs) => buildFootprintFeature(obs, centerLat)),
  }
}

// ── Popup helpers ──────────────────────────────────────────────────────────

/** Humanizes a snake_case itemType into a display label, e.g. "chip_bag" ->
 *  "Chip bag" (only the first word capitalized, matching TrashItemCatalog's
 *  displayName casing style without needing to duplicate its full table). */
export function humanizeItemType(itemType?: string | null): string {
  if (!itemType) return 'Litter'
  const words = itemType.split('_').filter(Boolean)
  if (words.length === 0) return 'Litter'
  return words
    .map((w, i) => (i === 0 ? w.charAt(0).toUpperCase() + w.slice(1) : w))
    .join(' ')
}

/** Formats an estimated weight in grams as "215 g" or "1.2 kg" (kg once
 *  >= 1000g, one decimal place). */
export function formatEstimatedWeight(grams?: number | null): string | null {
  if (typeof grams !== 'number' || !Number.isFinite(grams) || grams <= 0) return null
  if (grams >= 1000) return `${(grams / 1000).toFixed(1)} kg`
  return `${Math.round(grams)} g`
}
