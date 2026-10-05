/**
 * City blocks from a street network.
 *
 * The pilot blocks were traced by hand, and each one is a whole city block:
 * the land enclosed by the streets around it, alley included. That is a face
 * of the street graph. So "the same rules, everywhere" is: take the street
 * centrelines (no alleys, no footpaths), find every enclosed face, and each
 * face of a sensible size is a block. The same code works for any community
 * area.
 *
 * Output rows match `block_proposals` — the admin-only staging table reviewed
 * at /admin/block-proposals. Nothing here writes to `blocks`, so generating
 * proposals opens nothing to workers or sponsors.
 *
 * Pure: no network, no database. scripts/generate-block-proposals.ts fetches
 * the streets from OpenStreetMap and calls this.
 */
import turfArea from '@turf/area'
import { featureCollection, lineString } from '@turf/helpers'
import { polygonize } from '@turf/polygonize'

/** An OpenStreetMap way as Overpass returns it with `out geom`. */
export interface OsmWay {
  id: number
  tags?: Record<string, string>
  nodes: number[]
  geometry: Array<{ lon: number; lat: number }>
}

type Ring = Array<[number, number]> // [lng, lat], closed

export interface BlockProposal {
  codeHint: string
  name: string
  locationNeighborhood: string
  locationBoundary: { type: 'Polygon'; coordinates: [number, number][][] }
  locationCenter: { type: 'Point'; coordinates: [number, number] }
  /** The streets on the north and south sides, e.g. "W 15th St to W 16th St". */
  streetName: string
  /** The street on the west side. */
  fromCorner: string
  /** The street on the east side. */
  toCorner: string
  areaSqM: number
  /** OSM way ids of the bounding streets, for traceability. */
  sourceRefs: { osmWayIds: number[] }
}

export interface GenerateResult {
  proposals: BlockProposal[]
  skipped: {
    outsideArea: number
    tooSmallOrThin: number
    /** The same named street on both sides: the median of a boulevard. */
    median: number
    tooLarge: number
    alreadyExists: number
  }
}

/** Streets that bound a block. `service` (alleys, driveways) and paths do not. */
const BOUNDING_HIGHWAYS = new Set([
  'residential',
  'unclassified',
  'living_street',
  'tertiary',
  'secondary',
  'primary',
  'trunk',
  'tertiary_link',
  'secondary_link',
  'primary_link',
])

// A pilot block is about 110 m x 200 m = 22,000 m2.
/** Smaller than this is a traffic island or a median, not a block. */
const MIN_AREA_SQM = 3000
/** Larger than this is a park, rail yard or campus: left for a person to split. */
const MAX_AREA_SQM = 90000
/** 4*pi*A/P^2: 1 for a circle, ~0.7 for a pilot block, near 0 for a sliver. */
const MIN_COMPACTNESS = 0.2
/**
 * Narrower than this is a boulevard median or a strip of parkway. The
 * narrowest real blocks are about 55 m across; medians are about 30 m.
 */
const MIN_WIDTH_M = 45

/** Short side of the rectangle with this area and perimeter. */
function approxWidth(areaSqM: number, perimeterM: number): number {
  const disc = perimeterM * perimeterM - 16 * areaSqM
  return disc <= 0 ? Math.sqrt(areaSqM) : (perimeterM - Math.sqrt(disc)) / 4
}

const R = 6371000
const rad = (d: number) => (d * Math.PI) / 180

function metres(a: [number, number], b: [number, number]): number {
  const dLat = rad(b[1] - a[1])
  const dLng = rad(b[0] - a[0])
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a[1])) * Math.cos(rad(b[1])) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.sqrt(h))
}

function centroid(ring: Ring): [number, number] {
  // Area-weighted centroid of a simple polygon (planar is fine at block scale).
  let a = 0
  let cx = 0
  let cy = 0
  for (let i = 0; i < ring.length - 1; i++) {
    const [x0, y0] = ring[i]
    const [x1, y1] = ring[i + 1]
    const cross = x0 * y1 - x1 * y0
    a += cross
    cx += (x0 + x1) * cross
    cy += (y0 + y1) * cross
  }
  if (a === 0) return ring[0]
  return [cx / (3 * a), cy / (3 * a)]
}

function pointInRing(pt: [number, number], ring: Ring): boolean {
  let inside = false
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [xi, yi] = ring[i]
    const [xj, yj] = ring[j]
    if (yi > pt[1] !== yj > pt[1] && pt[0] < ((xj - xi) * (pt[1] - yi)) / (yj - yi) + xi) inside = !inside
  }
  return inside
}

const edgeKey = (a: [number, number], b: [number, number]) => {
  const ka = `${a[0]},${a[1]}`
  const kb = `${b[0]},${b[1]}`
  return ka < kb ? `${ka}|${kb}` : `${kb}|${ka}`
}

/**
 * A street name safe to store and show. OpenStreetMap is edited by the
 * public, so a name is untrusted text that ends up in block names on maps and
 * admin pages. Only letters, digits, spaces and . ' - survive, capped at 80
 * characters — enough for any real street, and nothing that can be markup.
 */
export function cleanStreetName(raw: string | undefined): string {
  if (typeof raw !== 'string') return ''
  return raw
    .normalize('NFC')
    .replace(/[^\p{L}\p{M}\p{N} .'\u2019-]/gu, '')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 80)
}

/**
 * Block-code prefix for a Chicago community area. North Lawndale keeps the
 * `NL` the pilot blocks use; every other area is `C` + its two-digit number,
 * because initials collide (Logan Square and Lincoln Square are both "LS").
 */
export function areaCodePrefix(communityAreaNumber: number): string {
  if (communityAreaNumber === 29) return 'NL'
  return `C${String(communityAreaNumber).padStart(2, '0')}`
}

export function generateBlockProposals(input: {
  ways: OsmWay[]
  /** Community-area outline, [lng, lat], closed. */
  areaRing: Ring
  codePrefix: string
  neighborhood: string
  /** Outlines of blocks that already exist; a face whose centre is inside one is skipped. */
  existingRings?: Ring[]
}): GenerateResult {
  const { ways, areaRing, codePrefix, neighborhood, existingRings = [] } = input

  // Split every bounding street into node-to-node segments. OSM ways share a
  // node where streets cross, so this is a properly noded network.
  const seen = new Set<string>()
  const edgeInfo = new Map<string, { name: string; wayId: number }>()
  const segments = []
  for (const way of ways) {
    if (!BOUNDING_HIGHWAYS.has(way.tags?.highway ?? '')) continue
    if (way.tags?.tunnel === 'yes' || way.tags?.bridge === 'yes') continue
    const name = cleanStreetName(way.tags?.name)
    for (let i = 0; i < way.geometry.length - 1; i++) {
      const a: [number, number] = [way.geometry[i].lon, way.geometry[i].lat]
      const b: [number, number] = [way.geometry[i + 1].lon, way.geometry[i + 1].lat]
      if (a[0] === b[0] && a[1] === b[1]) continue
      const key = edgeKey(a, b)
      if (seen.has(key)) continue
      seen.add(key)
      edgeInfo.set(key, { name, wayId: way.id })
      segments.push(lineString([a, b]))
    }
  }

  const skipped = { outsideArea: 0, tooSmallOrThin: 0, median: 0, tooLarge: 0, alreadyExists: 0 }
  if (segments.length === 0) return { proposals: [], skipped }

  const faces = polygonize(featureCollection(segments)).features
  const kept: Array<Omit<BlockProposal, 'codeHint'>> = []

  for (const face of faces) {
    const ring = face.geometry.coordinates[0] as Ring
    const centre = centroid(ring)

    if (!pointInRing(centre, areaRing)) {
      skipped.outsideArea++
      continue
    }

    const areaSqM = turfArea(face)
    let perimeter = 0
    for (let i = 0; i < ring.length - 1; i++) perimeter += metres(ring[i], ring[i + 1])
    const compactness = (4 * Math.PI * areaSqM) / (perimeter * perimeter)
    if (
      areaSqM < MIN_AREA_SQM ||
      compactness < MIN_COMPACTNESS ||
      approxWidth(areaSqM, perimeter) < MIN_WIDTH_M
    ) {
      skipped.tooSmallOrThin++
      continue
    }
    if (areaSqM > MAX_AREA_SQM) {
      skipped.tooLarge++
      continue
    }
    if (existingRings.some((r) => pointInRing(centre, r))) {
      skipped.alreadyExists++
      continue
    }

    // Name each side by the street that covers most of it.
    const sides: Record<'north' | 'south' | 'east' | 'west', Map<string, number>> = {
      north: new Map(),
      south: new Map(),
      east: new Map(),
      west: new Map(),
    }
    const wayIds = new Set<number>()
    for (let i = 0; i < ring.length - 1; i++) {
      const a = ring[i]
      const b = ring[i + 1]
      const info = edgeInfo.get(edgeKey(a, b))
      if (!info) continue
      wayIds.add(info.wayId)
      if (!info.name) continue
      const len = metres(a, b)
      const eastWest = metres([a[0], a[1]], [b[0], a[1]]) >= metres([a[0], a[1]], [a[0], b[1]])
      const mid: [number, number] = [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2]
      const side = eastWest ? (mid[1] >= centre[1] ? 'north' : 'south') : mid[0] >= centre[0] ? 'east' : 'west'
      sides[side].set(info.name, (sides[side].get(info.name) ?? 0) + len)
    }
    const best = (m: Map<string, number>) =>
      [...m.entries()].sort((x, y) => y[1] - x[1])[0]?.[0] ?? 'unnamed street'

    const west = best(sides.west)
    const east = best(sides.east)
    const north = best(sides.north)
    const south = best(sides.south)
    const streetName = `${north} to ${south}`

    const named = (n: string) => n !== 'unnamed street'
    if ((named(west) && west === east) || (named(north) && north === south)) {
      skipped.median++
      continue
    }

    kept.push({
      name: `${west} to ${east}, ${streetName}`,
      locationNeighborhood: neighborhood,
      locationBoundary: { type: 'Polygon', coordinates: [ring] },
      locationCenter: { type: 'Point', coordinates: centre },
      streetName,
      fromCorner: west,
      toCorner: east,
      areaSqM: Math.round(areaSqM),
      sourceRefs: { osmWayIds: [...wayIds].sort((x, y) => x - y) },
    })
  }

  // Reading order: north to south in rows, west to east within a row. Rows are
  // ~200 m apart, so bucketing latitude at ~60 m keeps a row together.
  const ROW = 0.00055
  kept.sort((p, q) => {
    const [plng, plat] = p.locationCenter.coordinates
    const [qlng, qlat] = q.locationCenter.coordinates
    const rowDiff = Math.round(qlat / ROW) - Math.round(plat / ROW)
    return rowDiff !== 0 ? rowDiff : plng - qlng
  })

  const proposals = kept.map((p, i) => ({
    codeHint: `${codePrefix}-B${String(i + 1).padStart(3, '0')}`,
    ...p,
  }))
  return { proposals, skipped }
}
