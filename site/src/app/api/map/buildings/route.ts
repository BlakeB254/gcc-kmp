import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { buildingFootprints } from '@/db/schema'
import { and, gte, lte } from 'drizzle-orm'
import { NON_OCCUPIABLE_BUILDING_TYPES, parseBboxString, tilesForBbox } from '@/lib/building-footprints'

export const dynamic = 'force-dynamic'

// A single request can only ask for so large an area (guards the serve query).
const MAX_TILES = 12

// READ-ONLY: serve building footprints already cached in Neon for [bbox]. The
// Overpass fetch that POPULATES the cache used to run inline here, but it blows
// the Vercel serverless request timeout on dense tiles — so ingestion now runs
// off the request path (see lib/building-footprints.ts + the cron/ingest
// endpoint + the daily cron piggyback). This handler never calls Overpass, so
// it's fast and can't time out; areas not yet ingested return empty and the
// client falls back to its offline rectangles.
export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url)
    const bbox = parseBboxString(searchParams.get('bbox'))
    if (!bbox) {
      return NextResponse.json(
        { buildings: [], success: false, error: 'bbox required as minLng,minLat,maxLng,maxLat' },
        { status: 400 },
      )
    }
    const { minLng, minLat, maxLng, maxLat } = bbox

    const tiles = tilesForBbox(minLng, minLat, maxLng, maxLat)
    if (tiles.length > MAX_TILES) {
      return NextResponse.json(
        { buildings: [], success: false, error: `bbox too large (${tiles.length} tiles, max ${MAX_TILES})` },
        { status: 400 },
      )
    }

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

    // Rows ingested before the parse-time filter existed can still carry
    // non-occupiable structures (roof/grandstand/…) — drop them at serve time
    // so stale cache rows can't feed the scan screens' indoor gate.
    const buildings = rows
      .filter((r) => !r.buildingType || !NON_OCCUPIABLE_BUILDING_TYPES.has(r.buildingType))
      .map((r) => ({
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
