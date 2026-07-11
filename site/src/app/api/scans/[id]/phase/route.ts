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
} from '@/lib/mobile-route-scan-response'

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
