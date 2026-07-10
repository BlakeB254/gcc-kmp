/**
 * GET /api/cron/building-footprints — off-request OSM building-footprint
 * ingestion for the indoor gate. Populates the `building_footprints` cache from
 * Overpass so the user-facing GET /api/map/buildings stays read-only + fast
 * (the inline fetch blew the Vercel serverless timeout). Idempotent (30d
 * per-tile TTL). Guarded by CRON_SECRET; also callable manually for a one-off
 * area.
 *
 * Driven by the Render cron job `gcc-cron-building-footprints` (hourly; see
 * `render.yaml`). It previously had no scheduler at all: a second `vercel.json`
 * entry was unsafe (Hobby cron limit + the "more-frequent-than-daily breaks all
 * deploys" gotcha), so ingestion piggybacked on the route-releases cron — where
 * it silently died, because that handler ran at Vercel's ~10s default while a
 * full Overpass sweep needs ~48s. Moving it here, behind its own schedule and
 * its own `maxDuration`, is what actually lets it finish.
 *
 * Leased because Overpass rate-limits aggressively: overlapping sweeps waste
 * quota and get us throttled. Ingestion itself is idempotent, so a skipped tick
 * costs nothing.
 *
 * Query: `?bbox=minLng,minLat,maxLng,maxLat` (default = pilot service area).
 */
import { NextRequest, NextResponse } from 'next/server'
import { ingestBuildingFootprints, parseBboxString, ingestBboxFromEnv } from '@/lib/building-footprints'
import { withCronLease } from '@/lib/cron-lease'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'
export const maxDuration = 60

const LEASE_TTL_SECONDS = 120

export async function GET(request: NextRequest) {
  const secret = process.env.CRON_SECRET
  // Fail closed: without a configured secret the endpoint is disabled.
  if (!secret) {
    return NextResponse.json({ error: 'CRON_SECRET not configured', success: false }, { status: 503 })
  }
  const auth = request.headers.get('authorization')
  if (auth !== `Bearer ${secret}`) {
    return NextResponse.json({ error: 'Unauthorized', success: false }, { status: 401 })
  }

  try {
    const { searchParams } = new URL(request.url)
    const bbox = parseBboxString(searchParams.get('bbox')) ?? ingestBboxFromEnv()
    const outcome = await withCronLease('building-footprints', LEASE_TTL_SECONDS, () =>
      ingestBuildingFootprints(bbox),
    )

    // A held lease means a sweep is already in flight. Expected, not an error.
    if (!outcome.ran) {
      return NextResponse.json({ success: true, bbox, skipped: outcome.skipped })
    }

    return NextResponse.json({ success: true, bbox, ...outcome.result })
  } catch (error) {
    console.error('GET /api/cron/building-footprints error:', error)
    return NextResponse.json({ success: false, error: String(error) }, { status: 500 })
  }
}
