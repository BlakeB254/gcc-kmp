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
import { cronAuthFailure } from '@/lib/cron-auth'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'
export const maxDuration = 60

const LEASE_TTL_SECONDS = 120

export async function GET(request: NextRequest) {
  // CRON_SECRET guard: fails closed when unset, constant-time compare.
  const denied = cronAuthFailure(request)
  if (denied) return denied

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
