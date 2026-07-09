/**
 * GET /api/cron/building-footprints — off-request OSM building-footprint
 * ingestion for the indoor gate. Populates the `building_footprints` cache from
 * Overpass so the user-facing GET /api/map/buildings stays read-only + fast
 * (the inline fetch blew the Vercel serverless timeout). Idempotent (30d
 * per-tile TTL). Guarded by CRON_SECRET (Vercel Cron sends
 * `Authorization: Bearer $CRON_SECRET`); also callable by an external scheduler
 * or manually for a one-off area.
 *
 * NOT wired into vercel.json crons on purpose — Hobby's cron limit + the
 * "more-frequent-than-daily breaks all deploys" gotcha make a second entry
 * risky, so daily auto-population piggybacks on the existing route-releases
 * cron. This endpoint is the on-demand / external-scheduler entrypoint.
 *
 * Query: `?bbox=minLng,minLat,maxLng,maxLat` (default = pilot service area).
 */
import { NextRequest, NextResponse } from 'next/server'
import { ingestBuildingFootprints, parseBboxString, ingestBboxFromEnv } from '@/lib/building-footprints'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'
export const maxDuration = 60

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
    const result = await ingestBuildingFootprints(bbox)
    return NextResponse.json({ success: true, bbox, ...result })
  } catch (error) {
    console.error('GET /api/cron/building-footprints error:', error)
    return NextResponse.json({ success: false, error: String(error) }, { status: 500 })
  }
}
