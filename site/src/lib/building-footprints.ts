import { db } from '@/db'
import { buildingFootprints, buildingFootprintTiles } from '@/db/schema'
import { inArray } from 'drizzle-orm'

// ~0.01° ≈ 1.1km tiles, matching ScanAreaCacheStore.TILE_DEGREES + the client
// BuildingFootprintStore so client and server quantize identically.
export const TILE_DEGREES = 0.01
export const TTL_MS = 30 * 24 * 60 * 60 * 1000 // fetch each tile at most once / 30d
const OVERPASS_URL = process.env.OVERPASS_URL || 'https://overpass-api.de/api/interpreter'
const OVERPASS_TIMEOUT_MS = 25_000
const OVERPASS_TILE_DELAY_MS = 1_200 // space requests so the free instance doesn't 429

// The pilot service area (North Lawndale, Chicago). Ingestion runs against this
// by default so footprints are populated where workers actually are, off the
// user request path. Override with BUILDING_FOOTPRINT_INGEST_BBOX
// ("minLng,minLat,maxLng,maxLat") to ingest a different area.
export const DEFAULT_INGEST_BBOX: Bbox = { minLng: -87.735, minLat: 41.848, maxLng: -87.695, maxLat: 41.870 }

export interface Bbox {
  minLng: number
  minLat: number
  maxLng: number
  maxLat: number
}

export interface Tile {
  key: string
  south: number
  west: number
  north: number
  east: number
}

export function tilesForBbox(minLng: number, minLat: number, maxLng: number, maxLat: number): Tile[] {
  const tiles: Tile[] = []
  const latStart = Math.floor(minLat / TILE_DEGREES)
  const latEnd = Math.floor(maxLat / TILE_DEGREES)
  const lngStart = Math.floor(minLng / TILE_DEGREES)
  const lngEnd = Math.floor(maxLng / TILE_DEGREES)
  for (let la = latStart; la <= latEnd; la++) {
    for (let ln = lngStart; ln <= lngEnd; ln++) {
      tiles.push({
        key: `b:${la}:${ln}`,
        south: la * TILE_DEGREES,
        west: ln * TILE_DEGREES,
        north: (la + 1) * TILE_DEGREES,
        east: (ln + 1) * TILE_DEGREES,
      })
    }
  }
  return tiles
}

export interface ParsedFootprint {
  osmId: number
  polygon: number[][] // [lng,lat][]
  buildingType: string | null
  minLng: number
  minLat: number
  maxLng: number
  maxLat: number
}

/**
 * building=* values that are NOT enclosed, occupiable structures — standing
 * "inside" their polygon says nothing about being indoors, so they must never
 * feed the scan screens' indoor gate. (Field incident 2026-07-11: park
 * structures over the Douglass Park running track flipped the gate INDOOR on
 * every GPS fix.)
 */
export const NON_OCCUPIABLE_BUILDING_TYPES = new Set([
  'roof',
  'grandstand',
  'stadium',
  'bridge',
  'shelter',
  'carport',
  'gazebo',
  'greenhouse',
  'ruins',
  'construction',
  'no',
])

export function parseOverpassElements(json: any): ParsedFootprint[] {
  const out: ParsedFootprint[] = []
  for (const el of json?.elements ?? []) {
    if (el.type !== 'way' || !Array.isArray(el.geometry) || el.geometry.length < 3) continue
    if (NON_OCCUPIABLE_BUILDING_TYPES.has(el.tags?.building)) continue
    const ring: number[][] = el.geometry.map((g: any) => [g.lon, g.lat])
    let minLng = Infinity, minLat = Infinity, maxLng = -Infinity, maxLat = -Infinity
    for (const [lng, lat] of ring) {
      if (lng < minLng) minLng = lng
      if (lng > maxLng) maxLng = lng
      if (lat < minLat) minLat = lat
      if (lat > maxLat) maxLat = lat
    }
    out.push({
      osmId: el.id,
      polygon: ring,
      buildingType: el.tags?.building ?? null,
      minLng, minLat, maxLng, maxLat,
    })
  }
  return out
}

async function fetchOverpassTile(tile: Tile): Promise<ParsedFootprint[]> {
  const query =
    `[out:json][timeout:25];(way["building"](${tile.south},${tile.west},${tile.north},${tile.east}););out geom;`
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), OVERPASS_TIMEOUT_MS)
  try {
    const res = await fetch(OVERPASS_URL, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        // overpass-api.de's Apache does content negotiation and returns 406
        // Not Acceptable without an explicit Accept; a UA is also expected.
        Accept: 'application/json',
        'User-Agent': 'gcc-building-footprints/1.0 (globalcommunitycleaners.org)',
      },
      body: `data=${encodeURIComponent(query)}`,
      signal: controller.signal,
    })
    if (!res.ok) throw new Error(`Overpass ${res.status}`)
    return parseOverpassElements(await res.json())
  } finally {
    clearTimeout(timer)
  }
}

export function parseBboxString(bboxParam: string | null): Bbox | null {
  if (!bboxParam) return null
  const parts = bboxParam.split(',').map(Number)
  if (parts.length !== 4 || !parts.every(Number.isFinite)) return null
  const [minLng, minLat, maxLng, maxLat] = parts
  return { minLng, minLat, maxLng, maxLat }
}

export interface IngestResult {
  tilesConsidered: number
  tilesFetched: number
  footprints: number
  errors: number
}

/**
 * Populate building_footprints for [bbox] from Overpass — the INGESTION path,
 * decoupled from the user-facing GET /api/map/buildings (which is read-only).
 * Overpass + hundreds of upserts per tile blow the Vercel serverless request
 * timeout, so this runs from a cron/admin trigger instead. Idempotent (30d TTL
 * per tile, upsert on conflict); best-effort (a failed tile is not marked fresh
 * and retries next run). [maxTiles] bounds a single run.
 */
export async function ingestBuildingFootprints(bbox: Bbox, maxTiles = 40): Promise<IngestResult> {
  const tiles = tilesForBbox(bbox.minLng, bbox.minLat, bbox.maxLng, bbox.maxLat).slice(0, maxTiles)
  const result: IngestResult = { tilesConsidered: tiles.length, tilesFetched: 0, footprints: 0, errors: 0 }
  if (tiles.length === 0) return result

  const now = Date.now()
  const keys = tiles.map((t) => t.key)
  const ledger = await db.select().from(buildingFootprintTiles).where(inArray(buildingFootprintTiles.tileKey, keys))
  const freshMs = new Map(ledger.map((r) => [r.tileKey, new Date(r.fetchedAt).getTime()]))

  let firstFetch = true
  for (const tile of tiles) {
    const at = freshMs.get(tile.key)
    if (at !== undefined && now - at < TTL_MS) continue
    // Space Overpass requests — firing every tile back-to-back gets us 429/504
    // (the free instance rate-limits). ~1.2s between fetches is a good citizen.
    if (!firstFetch) await new Promise((r) => setTimeout(r, OVERPASS_TILE_DELAY_MS))
    firstFetch = false
    try {
      const footprints = await fetchOverpassTile(tile)
      const fetchedAtIso = new Date(now).toISOString()
      const writes = [
        ...footprints.map((f) =>
          db
            .insert(buildingFootprints)
            .values({
              osmId: f.osmId,
              source: 'overpass',
              tileKey: tile.key,
              minLng: f.minLng, minLat: f.minLat, maxLng: f.maxLng, maxLat: f.maxLat,
              polygon: f.polygon,
              buildingType: f.buildingType,
              fetchedAt: fetchedAtIso,
            })
            .onConflictDoUpdate({
              target: [buildingFootprints.source, buildingFootprints.osmId],
              set: {
                tileKey: tile.key,
                minLng: f.minLng, minLat: f.minLat, maxLng: f.maxLng, maxLat: f.maxLat,
                polygon: f.polygon,
                buildingType: f.buildingType,
                fetchedAt: fetchedAtIso,
              },
            }),
        ),
        db
          .insert(buildingFootprintTiles)
          .values({ tileKey: tile.key, fetchedAt: fetchedAtIso, footprintCount: footprints.length })
          .onConflictDoUpdate({
            target: buildingFootprintTiles.tileKey,
            set: { fetchedAt: fetchedAtIso, footprintCount: footprints.length },
          }),
      ]
      // `writes` is always non-empty (the ledger upsert); batch wants a tuple.
      await db.batch(writes as [(typeof writes)[number], ...(typeof writes)[number][]])
      result.tilesFetched += 1
      result.footprints += footprints.length
    } catch (e) {
      console.error('ingestBuildingFootprints: tile failed', tile.key, e)
      result.errors += 1
    }
  }
  return result
}

/** Resolve the ingest bbox from env override or the pilot default. */
export function ingestBboxFromEnv(): Bbox {
  return parseBboxString(process.env.BUILDING_FOOTPRINT_INGEST_BBOX ?? null) ?? DEFAULT_INGEST_BBOX
}
