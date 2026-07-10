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
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { civicReports, civicReportEvidence, pointsLedger } from '@/db/schema'
import { and, eq, isNull } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'

export const dynamic = 'force-dynamic'

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
