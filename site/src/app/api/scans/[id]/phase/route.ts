import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { routeScans } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import {
  canAccessRouteScan,
  findRouteScan,
  hotspotsForScan,
  isValidScanPhaseTransition,
  normalizeScanPhase,
  toMobileRouteScan,
  validateLapOneProof,
} from '@/lib/mobile-route-scan-response'
import { lapOneCoverageMessage, lapOneGate } from '@/lib/scan-verification'
import { loadScanRouteCoverage } from '@/lib/scan-completion'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

type RouteContext = { params: Promise<{ id: string }> }

export async function PATCH(request: NextRequest, context: RouteContext) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json({ data: null, success: false, message: 'Not authenticated' }, { status: 401 })
  }

  try {
    const { id } = await context.params
    const scanId = parseInt(id, 10)
    if (Number.isNaN(scanId)) {
      return NextResponse.json({ data: null, success: false, message: 'Invalid scan ID' }, { status: 400 })
    }

    const scan = await findRouteScan(scanId)
    if (!scan) {
      return NextResponse.json({ data: null, success: false, message: 'Scan not found' }, { status: 404 })
    }
    if (!canAccessRouteScan(scan, auth)) {
      return NextResponse.json({ data: null, success: false, message: 'Scan belongs to a different worker' }, { status: 403 })
    }

    const body = await request.json().catch(() => null)
    const phase = normalizeScanPhase(body?.phase ?? scan.phase)
    if (!isValidScanPhaseTransition(scan.phase, phase)) {
      return NextResponse.json(
        {
          data: null,
          success: false,
          message: `Invalid scan phase transition: ${scan.phase} → ${phase}`,
        },
        { status: 409 },
      )
    }
    const now = new Date().toISOString()

    // Lap-one gate at scouting → scout_review (finding H2 + review follow-up).
    // The client's proof (coveragePercent/loopClosed/closeCoveragePercent) is
    // still REQUIRED, but it is only a consistency check — those are numbers
    // the client chose. The stamp depends on the route coverage the SERVER
    // computes from the coverage events attributed to this scan at ingest
    // (same definition completion uses — lib/scan-completion.ts).
    const fromPhase = normalizeScanPhase(scan.phase)
    const isLapOneBoundary = fromPhase === 'scouting' && phase === 'scout_review'
    const proof = validateLapOneProof(body, String(scan.deviceType).toLowerCase() === 'ar')
    const lapOneCoverage =
      isLapOneBoundary && proof === true ? (await loadScanRouteCoverage(db, scan)).coverage : null
    const lapOne = lapOneGate(fromPhase, phase, proof, lapOneCoverage)
    if (lapOne === 'refused-proof') {
      return NextResponse.json(
        {
          data: null,
          success: false,
          message: 'Lap 1 is incomplete: 95% corridor coverage, a closed loop, and 80% AR close-detail are required',
        },
        { status: 409 },
      )
    }
    if (lapOne === 'refused-coverage') {
      return NextResponse.json(
        { data: { coverage: lapOneCoverage }, success: false, message: lapOneCoverageMessage(lapOneCoverage) },
        { status: 409 },
      )
    }

    const [updated] = await db
      .update(routeScans)
      .set({
        phase,
        coveragePercent: Number.isFinite(Number(body?.coveragePercent))
          ? Number(body.coveragePercent)
          : scan.coveragePercent,
        scoutPathGeoJson: typeof body?.scoutPathGeoJson === 'string' ? body.scoutPathGeoJson : scan.scoutPathGeoJson,
        cleanPathGeoJson: typeof body?.cleanPathGeoJson === 'string' ? body.cleanPathGeoJson : scan.cleanPathGeoJson,
        scoutCompletedAt: phase === 'scout_review' && !scan.scoutCompletedAt ? now : scan.scoutCompletedAt,
        cleanStartedAt: phase === 'cleaning' && !scan.cleanStartedAt ? now : scan.cleanStartedAt,
        lapOneVerifiedAt: lapOne === 'verified' && !scan.lapOneVerifiedAt ? now : scan.lapOneVerifiedAt,
        lapOneCoverageRatio:
          lapOne === 'verified' && !scan.lapOneVerifiedAt && lapOneCoverage
            ? lapOneCoverage.ratio
            : scan.lapOneCoverageRatio,
        updatedAt: now,
      })
      .where(eq(routeScans.id, scanId))
      .returning()

    return NextResponse.json({
      data: toMobileRouteScan(updated, await hotspotsForScan(scanId, updated.phase)),
      success: true,
    })
  } catch (error) {
    console.error('PATCH /api/scans/[id]/phase error:', error)
    return NextResponse.json({ data: null, success: false, message: 'Failed to update scan phase' }, { status: 500 })
  }
}
