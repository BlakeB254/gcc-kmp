import { NextRequest, NextResponse } from 'next/server'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { recordWorldObservation, gridCell } from '@/lib/world-observations'
import { db } from '@/db'
import { pointsLedger, worldObservations } from '@/db/schema'
import { RECTIFICATION_BONUS_POINTS } from '@/lib/points-config'
import { and, eq } from 'drizzle-orm'

export const dynamic = 'force-dynamic'

/**
 * POST /api/observations/rectify
 *
 * Environment-scan variant of /api/scans/[id]/rectify: the worker stood at a
 * previously-reported-dirty spot during an environment scan (no route scan in
 * progress) and confirmed it is actually clean now. Records the superseding
 * clean observation and awards the flat rectification bonus — ONLY when a
 * real prior 'dirty' observation exists in this grid cell (anti-farming; the
 * same guard the route-scan variant uses; there is no scan-ownership check
 * here because environment scans have no scan row, so the prior-dirty check
 * and authenticated worker identity are the accountability anchors).
 */
export async function POST(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json({ success: false, error: 'Not authenticated' }, { status: 401 })
  }

  const body = await request.json().catch(() => null)
  const lat = Number(body?.lat)
  const lng = Number(body?.lng)
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) {
    return NextResponse.json({ success: false, error: 'lat and lng are required' }, { status: 400 })
  }

  // Server-side timestamp — ledger rows must not carry client-controlled times.
  const capturedAt = new Date().toISOString()

  // Anti-farming: award only when a real prior dirty observation exists in
  // this cell. Read BEFORE recordWorldObservation's superseding write.
  const cellId = gridCell(lat, lng)
  const [priorDirty] = await db
    .select({ id: worldObservations.id })
    .from(worldObservations)
    .where(and(
      eq(worldObservations.locationCellId, cellId),
      eq(worldObservations.state, 'dirty'),
      eq(worldObservations.isCurrent, true),
    ))
    .limit(1)

  const observation = await recordWorldObservation({
    sourceType: 'spot_report',
    lat,
    lng,
    itemType: null,
    state: 'clean',
    capturedAt,
  })

  if (priorDirty) {
    if (auth.role !== 'admin') await db.insert(pointsLedger).values({
      workerId: auth.workerId,
      action: 'scan_coverage_rectification',
      points: RECTIFICATION_BONUS_POINTS,
      referenceType: 'world_observation',
      referenceId: observation.id,
      createdAt: capturedAt,
    })
  }

  return NextResponse.json({
    success: true,
    observationId: observation.id,
    pointsAwarded: priorDirty && auth.role !== 'admin' ? RECTIFICATION_BONUS_POINTS : 0,
    wasRectification: Boolean(priorDirty),
  })
}
