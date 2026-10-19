import { NextRequest, NextResponse } from 'next/server'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { recordWorldObservation, gridCell } from '@/lib/world-observations'
import { db } from '@/db'
import { pointsLedger, routeScans } from '@/db/schema'
import { RECTIFICATION_BONUS_POINTS } from '@/lib/points-config'
import { decideRectification, parseRequestFix } from '@/lib/rectify-checks'
import { claimDirtyObservation, loadDirtyObservations, loadLatestFix, loadRecentFixes } from '@/lib/rectify-checks-db'
import { eq } from 'drizzle-orm'

export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ id: string }> }

/**
 * POST /api/scans/[id]/rectify
 *
 * Backend for design spec §1.5: the user aimed the camera at a marker for a
 * previously-expected-dirty spot and confirmed it's actually clean now.
 * Records the clean observation (superseding the prior dirty one via
 * recordWorldObservation's existing logic) and awards a flat rectification
 * bonus — but ONLY when the scan belongs to the authenticated worker and the
 * anti-farming rules in lib/rectify-checks.ts hold (a dirty observation in
 * this cell that someone else reported; the worker's recorded GPS at the
 * cell). Same rules as POST /api/observations/rectify.
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

  // Anti-farming (lib/rectify-checks.ts). Read BEFORE recordWorldObservation's
  // write, since that call supersedes the very rows being checked.
  const cellId = gridCell(lat, lng)
  // Presence: the fix the request carries (`fix: {lat, lng, capturedAt}`,
  // trusted only when fresh and plausible against the recorded trajectory),
  // or a recorded coverage fix near the cell.
  const decision = decideRectification({
    cellId,
    rectifierId: auth.workerId,
    dirty: await loadDirtyObservations(db, cellId, auth.workerId),
    recentFixes: await loadRecentFixes(db, auth.workerId, cellId, capturedAt),
    latestRecorded: await loadLatestFix(db, auth.workerId, capturedAt),
    requestFix: parseRequestFix(body?.fix),
    nowMs: Date.parse(capturedAt),
  })

  // The award is decided by reads; the payout is gated on an atomic claim of
  // the dirty row (before recordWorldObservation supersedes the cell), so a
  // concurrent duplicate request cannot be paid twice.
  const claimed = decision.award && auth.role !== 'admin' && (await claimDirtyObservation(db, decision.observationId))

  const observation = await recordWorldObservation({
    sourceType: 'route_scan',
    lat,
    lng,
    itemType: null,
    state: 'clean',
    capturedAt,
    reporterWorkerId: auth.workerId,
  })

  const awarded = claimed
  if (awarded) {
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
    pointsAwarded: awarded ? RECTIFICATION_BONUS_POINTS : 0,
    wasRectification: decision.award,
    ...(!decision.award
      ? { bonusWithheld: decision.reason }
      : !claimed && auth.role !== 'admin'
        ? { bonusWithheld: 'already_rectified' }
        : {}),
  })
}
