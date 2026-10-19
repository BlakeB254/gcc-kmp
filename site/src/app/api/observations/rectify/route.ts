import { NextRequest, NextResponse } from 'next/server'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { recordWorldObservation, gridCell } from '@/lib/world-observations'
import { db } from '@/db'
import { pointsLedger } from '@/db/schema'
import { RECTIFICATION_BONUS_POINTS } from '@/lib/points-config'
import { decideRectification, parseRequestFix } from '@/lib/rectify-checks'
import { claimDirtyObservation, loadDirtyObservations, loadLatestFix, loadRecentFixes } from '@/lib/rectify-checks-db'

export const dynamic = 'force-dynamic'

/**
 * POST /api/observations/rectify
 *
 * Environment-scan variant of /api/scans/[id]/rectify: the worker stood at a
 * previously-reported-dirty spot during an environment scan (no route scan in
 * progress) and confirmed it is actually clean now. Records the superseding
 * clean observation and awards the flat rectification bonus — ONLY when the
 * anti-farming rules in lib/rectify-checks.ts hold: a current dirty
 * observation in this cell that someone else reported, and the worker's own
 * recorded GPS at the cell. There is no scan-ownership check here because
 * environment scans have no scan row.
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

  // Read BEFORE recordWorldObservation's superseding write.
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
    sourceType: 'spot_report',
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
