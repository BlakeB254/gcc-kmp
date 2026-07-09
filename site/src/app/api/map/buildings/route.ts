import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { buildingFootprints, buildingFootprintTiles } from '@/db/schema'
import { and, gte, lte, inArray } from 'drizzle-orm'

export const dynamic = 'force-dynamic'

// ~0.01° ≈ 1.1km tiles, matching ScanAreaCacheStore.TILE_DEGREES + the client
// BuildingFootprintStore so client and server quantize identically.
const TILE_DEGREES = 0.01
const TTL_MS = 30 * 24 * 60 * 60 * 1000 // fetch each tile at most once / 30d
const MAX_TILES = 12 // bound the Overpass load a single request can trigger
const OVERPASS_URL = process.env.OVERPASS_URL || 'https://overpass-api.de/api/interpreter'
const OVERPASS_TIMEOUT_MS = 25_000

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

export function parseOverpassElements(json: any): ParsedFootprint[] {
  const out: ParsedFootprint[] = []
  for (const el of json?.elements ?? []) {
    if (el.type !== 'way' || !Array.isArray(el.geometry) || el.geometry.length < 3) continue
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
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: `data=${encodeURIComponent(query)}`,
      signal: controller.signal,
    })
    if (!res.ok) throw new Error(`Overpass ${res.status}`)
    return parseOverpassElements(await res.json())
  } finally {
    clearTimeout(timer)
  }
}

export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url)
    const bboxParam = searchParams.get('bbox')
    if (!bboxParam) {
      return NextResponse.json({ buildings: [], success: false, error: 'bbox required' }, { status: 400 })
    }
    const parts = bboxParam.split(',').map(Number)
    if (parts.length !== 4 || !parts.every(Number.isFinite)) {
      return NextResponse.json(
        { buildings: [], success: false, error: 'bbox must be minLng,minLat,maxLng,maxLat' },
        { status: 400 },
      )
    }
    const [minLng, minLat, maxLng, maxLat] = parts

    const tiles = tilesForBbox(minLng, minLat, maxLng, maxLat)
    if (tiles.length > MAX_TILES) {
      return NextResponse.json(
        { buildings: [], success: false, error: `bbox too large (${tiles.length} tiles, max ${MAX_TILES})` },
        { status: 400 },
      )
    }

    const now = Date.now()
    const keys = tiles.map((t) => t.key)
    const ledger = keys.length
      ? await db.select().from(buildingFootprintTiles).where(inArray(buildingFootprintTiles.tileKey, keys))
      : []
    const freshMs = new Map(ledger.map((r) => [r.tileKey, new Date(r.fetchedAt).getTime()]))

    // Lazily (re)fetch each stale/missing tile. Best-effort: an Overpass
    // failure leaves whatever is cached and does NOT mark the tile fresh, so
    // it retries next time — a scan is never blocked on this (spec §1 failure
    // handling). All of a tile's upserts (footprints + the tile ledger row)
    // run in ONE db.batch — neon-http has no db.transaction and a dense tile
    // returns hundreds of footprints, so per-row awaits would be hundreds of
    // serial HTTP round-trips inside the request.
    for (const tile of tiles) {
      const at = freshMs.get(tile.key)
      if (at !== undefined && now - at < TTL_MS) continue
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
      } catch (e) {
        console.error('GET /api/map/buildings overpass fetch failed for tile', tile.key, e)
        // graceful: leave cached footprints, don't mark tile fresh (will retry)
      }
    }

    // Serve every footprint whose bbox intersects the request bbox.
    const rows = await db
      .select()
      .from(buildingFootprints)
      .where(and(
        lte(buildingFootprints.minLng, maxLng),
        gte(buildingFootprints.maxLng, minLng),
        lte(buildingFootprints.minLat, maxLat),
        gte(buildingFootprints.maxLat, minLat),
      ))
      .limit(5000)

    const buildings = rows.map((r) => ({
      polygon: r.polygon,
      buildingType: r.buildingType,
      source: r.source,
    }))
    return NextResponse.json({ buildings, success: true })
  } catch (error) {
    console.error('GET /api/map/buildings error:', error)
    return NextResponse.json({ buildings: [], success: false }, { status: 500 })
  }
}
