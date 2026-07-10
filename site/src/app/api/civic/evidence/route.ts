/**
 * POST /api/civic/evidence — link a GCC scan/photo to a Chicago 311 report as
 * visual evidence (docs/superpowers/specs/2026-07-10-chicago-311-civic-loop-
 * design.md §5.3). Body: { srNumber, sessionId, observationId?, photoKey? }.
 *
 * Idempotent on (srNumber, sessionId). Stamps the report's
 * `first_captured_at` on the first evidence. The points bonus is gated on
 * CIVIC_CAPTURE_BONUS_POINTS (unset/0 = record evidence, award nothing) —
 * the bonus size is an open product decision (spec §5.4), so the loop works
 * end-to-end while the economics stay off until explicitly configured.
 *
 * Referenced-resource validation: sessions and observations carry NO worker
 * attribution today (scan_sessions is device-keyed — a known model gap), so
 * per-worker OWNERSHIP of a session/observation is structurally unverifiable.
 * What IS enforced: the session must exist and be completed, the observation
 * must exist AND sit within EVIDENCE_MAX_DISTANCE_M of the report, and
 * photoKey must be a well-formed raw/ scan key whose cell is near the report
 * — so only real, geographically-consistent artifacts can attach. Keeping
 * CIVIC_CAPTURE_BONUS_POINTS at 0 until session→worker attribution exists is
 * the guard against point farming via someone else's artifacts (spec §5.4).
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { civicReports, civicReportEvidence, pointsLedger, scanSessions, worldObservations } from '@/db/schema'
import { and, eq, isNull, isNotNull } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'

export const dynamic = 'force-dynamic'

const METERS_PER_DEG_LAT = 111_000
const METERS_PER_DEG_LNG = 82_300
const EVIDENCE_MAX_DISTANCE_M = 150
// rawScanKey/rawSessionArtifactKey shape: raw/{latIdx}:{lngIdx}/…
const RAW_KEY_PATTERN = /^raw\/(-?\d+):(-?\d+)\//

function distanceMeters(latA: number, lngA: number, latB: number, lngB: number): number {
  return Math.hypot((latA - latB) * METERS_PER_DEG_LAT, (lngA - lngB) * METERS_PER_DEG_LNG)
}

export async function POST(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json({ data: null, success: false, message: 'Not authenticated' }, { status: 401 })
  }

  const body = await request.json().catch(() => null)
  const srNumber = typeof body?.srNumber === 'string' ? body.srNumber.slice(0, 50) : ''
  const sessionId = typeof body?.sessionId === 'string' ? body.sessionId.slice(0, 64) : ''
  const observationId = Number.isInteger(body?.observationId) ? body.observationId : null
  const photoKey = typeof body?.photoKey === 'string' ? body.photoKey.slice(0, 500) : null
  if (!srNumber || !sessionId) {
    return NextResponse.json({ data: null, success: false, message: 'srNumber and sessionId required' }, { status: 400 })
  }

  const [report] = await db.select().from(civicReports).where(eq(civicReports.srNumber, srNumber)).limit(1)
  if (!report) {
    return NextResponse.json({ data: null, success: false, message: 'Unknown 311 report' }, { status: 404 })
  }

  // The session must be a real, completed scan session — not a fabricated id.
  const [session] = await db
    .select({ sessionId: scanSessions.sessionId })
    .from(scanSessions)
    .where(and(eq(scanSessions.sessionId, sessionId), isNotNull(scanSessions.completedAt)))
    .limit(1)
  if (!session) {
    return NextResponse.json({ data: null, success: false, message: 'Unknown or incomplete scan session' }, { status: 403 })
  }

  // The observation, if referenced, must exist and be physically near the
  // report — evidence has to be geographically consistent with the claim.
  if (observationId !== null) {
    const [obs] = await db
      .select({ lat: worldObservations.lat, lng: worldObservations.lng })
      .from(worldObservations)
      .where(eq(worldObservations.id, observationId))
      .limit(1)
    if (!obs) {
      return NextResponse.json({ data: null, success: false, message: 'Unknown observation' }, { status: 403 })
    }
    if (
      report.lat !== null && report.lng !== null &&
      distanceMeters(obs.lat, obs.lng, report.lat, report.lng) > EVIDENCE_MAX_DISTANCE_M
    ) {
      return NextResponse.json({ data: null, success: false, message: 'Observation too far from report' }, { status: 403 })
    }
  }

  // photoKey must be a well-formed raw/ scan key whose cell sits near the
  // report (the key's leading component IS a grid cell id).
  if (photoKey !== null) {
    const match = RAW_KEY_PATTERN.exec(photoKey)
    if (!match) {
      return NextResponse.json({ data: null, success: false, message: 'Invalid photo key' }, { status: 403 })
    }
    if (report.lat !== null && report.lng !== null) {
      const cellLat = (Number(match[1]) + 0.5) * (10 / METERS_PER_DEG_LAT)
      const cellLng = (Number(match[2]) + 0.5) * (10 / METERS_PER_DEG_LNG)
      if (distanceMeters(cellLat, cellLng, report.lat, report.lng) > EVIDENCE_MAX_DISTANCE_M) {
        return NextResponse.json({ data: null, success: false, message: 'Photo too far from report' }, { status: 403 })
      }
    }
  }

  const inserted = await db
    .insert(civicReportEvidence)
    .values({
      srNumber,
      sessionId,
      observationId,
      photoR2Key: photoKey,
      workerId: auth.workerId,
      matchConfidence: 'user',
    })
    .onConflictDoNothing()
    .returning({ id: civicReportEvidence.id })

  const isNewEvidence = inserted.length > 0
  if (isNewEvidence) {
    await db
      .update(civicReports)
      .set({ firstCapturedAt: new Date().toISOString() })
      .where(and(eq(civicReports.srNumber, srNumber), isNull(civicReports.firstCapturedAt)))
  }

  const bonus = Number(process.env.CIVIC_CAPTURE_BONUS_POINTS) || 0
  let pointsAwarded = 0
  if (isNewEvidence && bonus > 0) {
    await db.insert(pointsLedger).values({
      workerId: auth.workerId,
      action: 'civic_capture',
      points: bonus,
      referenceType: 'civic_report_evidence',
      referenceId: inserted[0].id,
      reason: `311 evidence: ${report.srType} (${srNumber})`,
    })
    pointsAwarded = bonus
  }

  return NextResponse.json({
    data: { recorded: isNewEvidence, pointsAwarded },
    success: true,
  })
}
