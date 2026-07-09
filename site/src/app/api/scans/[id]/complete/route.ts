import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { routeScans } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import {
  canAccessRouteScan,
  findRouteScan,
  hotspotsForScan,
  toMobileRouteScan,
} from '@/lib/mobile-route-scan-response'
import { gridCell, parseCoveredCellIds, resolveByCoveredCells } from '@/lib/world-observations'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

type RouteContext = { params: Promise<{ id: string }> }

function calculatePoints(hotspots: Awaited<ReturnType<typeof hotspotsForScan>>): number {
  const cleaned = hotspots.filter((hotspot) => hotspot.status === 'cleaned')
  const volumeBonus = cleaned.reduce((sum, hotspot) => sum + (hotspot.estimatedVolumeLiters ?? 0), 0)
  return 50 + cleaned.length * 10 + Math.floor(volumeBonus)
}

export async function POST(request: NextRequest, context: RouteContext) {
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
    const hotspots = await hotspotsForScan(scanId, 'complete')
    const pointsAwarded = calculatePoints(hotspots)
    const now = new Date().toISOString()
    const coveredCellIds = parseCoveredCellIds(body?.coveredCellIds)

    const [updated] = await db
      .update(routeScans)
      .set({
        phase: 'complete',
        status: 'completed',
        cleanCompletedAt: now,
        cleanPathGeoJson: typeof body?.cleanPathGeoJson === 'string' ? body.cleanPathGeoJson : scan.cleanPathGeoJson,
        totalTrashVolumeLiters: Number.isFinite(Number(body?.totalTrashVolumeLiters))
          ? Number(body.totalTrashVolumeLiters)
          : scan.totalTrashVolumeLiters,
        totalTrashWeightLbs: Number.isFinite(Number(body?.totalTrashWeightLbs))
          ? Number(body.totalTrashWeightLbs)
          : scan.totalTrashWeightLbs,
        pointsAwarded,
        updatedAt: now,
      })
      .where(eq(routeScans.id, scanId))
      .returning()

    if (coveredCellIds.length > 0) {
      const confirmedCells = new Set(hotspots.map((hotspot) => gridCell(hotspot.lat, hotspot.lng)))
      const staleCells = [...new Set(coveredCellIds)].filter((cellId) => !confirmedCells.has(cellId))
      if (staleCells.length > 0) {
        const { resolvedReportIds, invalidatedObservationCount } = await resolveByCoveredCells(
          staleCells,
          undefined,
          `Superseded by route scan ${scanId} sweep ${now} — covered by scan cell sweep`
        )
        if (resolvedReportIds.length > 0 || invalidatedObservationCount > 0) {
          console.log(
            `Route scan ${scanId} cell-sweep superseded reports [${resolvedReportIds.join(', ')}] and invalidated ${invalidatedObservationCount} world observation(s) across ${staleCells.length} stale cell(s)`
          )
        }
      }
    }

    return NextResponse.json({
      data: {
        scan: toMobileRouteScan(updated, hotspots),
        pointsAwarded,
      },
      success: true,
    })
  } catch (error) {
    console.error('POST /api/scans/[id]/complete error:', error)
    return NextResponse.json({ data: null, success: false, message: 'Failed to complete scan' }, { status: 500 })
  }
}
