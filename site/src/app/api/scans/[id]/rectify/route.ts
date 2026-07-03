import { NextRequest, NextResponse } from 'next/server'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { recordWorldObservation, gridCell } from '@/lib/world-observations'
import { db } from '@/db'
import { pointsLedger, routeScans, worldObservations } from '@/db/schema'
import { RECTIFICATION_BONUS_POINTS } from '@/lib/points-config'
import { and, eq } from 'drizzle-orm'

export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ id: string }> }

/**
 * POST /api/scans/[id]/rectify
 *
 * Backend for design spec §1.5: the user aimed the camera at a marker for a
 * previously-expected-dirty spot and confirmed it's actually clean now.
 * Records the clean observation (superseding the prior dirty one via
 * recordWorldObservation's existing logic) and awards a flat rectification
 * bonus — but ONLY if there was a real prior 'dirty' observation in this
 * cell to rectify (see security note below) and the scan belongs to the
 * authenticated worker.
 */
export async function POST(request: NextRequest, context: RouteContext) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json({ success: false, error: 'Not authenticated' }, { status: 401 })
  }

  const { id } = await context.params
  const scanId = parseInt(id, 10)
  if (Number.isNaN(scanId)) {
    return NextResponse.json({ success: false, error: 'Invalid scan ID' }, { status: 400 })
  }

  // Ownership check: the scan must exist and belong to the authenticated worker.
  const [scan] = await db
    .select({ workerId: routeScans.workerId })
    .from(routeScans)
    .where(eq(routeScans.id, scanId))
    .limit(1)
  if (!scan) {
    return NextResponse.json({ success: false, error: 'Scan not found' }, { status: 404 })
  }
  if (scan.workerId !== auth.workerId) {
    return NextResponse.json({ success: false, error: 'Scan does not belong to this worker' }, { status: 403 })
  }

  const body = await request.json().catch(() => null)
  const lat = Number(body?.lat)
  const lng = Number(body?.lng)
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) {
    return NextResponse.json({ success: false, error: 'lat and lng are required' }, { status: 400 })
  }

  // Server-side timestamp — not client-supplied. The ledger's createdAt for a
  // points-awarding event should reflect when the server actually processed
  // it, not a client-controllable value.
  const capturedAt = new Date().toISOString()

  // Anti-farming check: only award points if a real prior 'dirty' observation
  // exists in this cell. Read BEFORE recordWorldObservation's write, since
  // that call would otherwise supersede the very row we need to check for.
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
    sourceType: 'route_scan',
    lat,
    lng,
    itemType: null,
    state: 'clean',
    capturedAt,
  })

  if (priorDirty) {
    await db.insert(pointsLedger).values({
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
    pointsAwarded: priorDirty ? RECTIFICATION_BONUS_POINTS : 0,
    wasRectification: Boolean(priorDirty),
  })
}
