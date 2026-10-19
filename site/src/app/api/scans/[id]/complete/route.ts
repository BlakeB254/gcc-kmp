import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { requireMobileAuth } from '@/lib/mobile-auth'
import {
  canAccessRouteScan,
  findRouteScan,
  hotspotsForScan,
  toMobileRouteScan,
} from '@/lib/mobile-route-scan-response'
import { gridCell, parseCoveredCellIds, resolveByCoveredCells } from '@/lib/world-observations'
import {
  coverageRefusal,
  isScanAlreadyComplete,
  scanCompletionRefusal,
  supersedableCells,
} from '@/lib/scan-verification'
import { loadScanRouteCoverage, markScanComplete } from '@/lib/scan-completion'
import { scanCompletionPoints } from '@/lib/hotspot-checks'
import { NON_PAYING_PHOTO_FLAGS } from '@/lib/photo-fingerprint'
import { flagsForUse, loadPhotoFlags } from '@/lib/photo-fingerprint-db'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

type RouteContext = { params: Promise<{ id: string }> }

/**
 * Scan points from server-checked values only (lib/hotspot-checks.ts):
 * capped volumes, and a cleaned hotspot pays only when its after photo is
 * not flagged as reused or identical to its before photo. The client's
 * verificationScore is not an input.
 */
async function calculatePoints(scanId: number, hotspots: Awaited<ReturnType<typeof hotspotsForScan>>): Promise<number> {
  const afterFlags = await loadPhotoFlags(db, hotspots.map((h) => h.afterPhotoUrl ?? '').filter(Boolean))
  const paying = hotspots.map((h) => {
    const flags = h.afterPhotoUrl ? flagsForUse(afterFlags.get(h.afterPhotoUrl), { kind: 'scan', id: scanId }) : []
    return flags.some((f) => NON_PAYING_PHOTO_FLAGS.has(f)) ? { ...h, status: 'cleaned_unverified' } : h
  })
  return scanCompletionPoints(paying)
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

    // A retried submit (the first response was lost) must not error the
    // worker's finished scan, and must not re-run the supersede sweep.
    if (isScanAlreadyComplete(scan)) {
      return NextResponse.json({
        data: {
          scan: toMobileRouteScan(scan, await hotspotsForScan(scanId, 'complete')),
          pointsAwarded: scan.pointsAwarded,
        },
        success: true,
      })
    }

    // Server-verified completion (finding H2): state machine first — only from
    // the cleaning lap, only with lap-one proof on record — then coverage the
    // SERVER computes from the coverage events attributed to this scan at ingest
    // (scan_coverage_events.route_scan_id) against the route.
    // Nothing the client sends in the body can satisfy either check.
    const stateRefusal = scanCompletionRefusal(scan)
    if (stateRefusal) {
      return NextResponse.json({ data: null, success: false, message: stateRefusal.message }, { status: stateRefusal.status })
    }

    const now = new Date().toISOString()
    // Only coverage events the server attributed to THIS scan at ingest count.
    const { requiredCells, workedCells, coverage } = await loadScanRouteCoverage(db, scan)
    const coverageProblem = coverageRefusal(coverage)
    if (coverageProblem) {
      return NextResponse.json(
        { data: { coverage }, success: false, message: coverageProblem.message },
        { status: coverageProblem.status },
      )
    }

    const body = await request.json().catch(() => null)
    const hotspots = await hotspotsForScan(scanId, 'complete')
    const pointsAwarded = await calculatePoints(scanId, hotspots)

    const updated = await markScanComplete(
      db,
      scanId,
      {
        cleanPathGeoJson: typeof body?.cleanPathGeoJson === 'string' ? body.cleanPathGeoJson : scan.cleanPathGeoJson,
        totalTrashVolumeLiters: Number.isFinite(Number(body?.totalTrashVolumeLiters))
          ? Number(body.totalTrashVolumeLiters)
          : scan.totalTrashVolumeLiters,
        totalTrashWeightLbs: Number.isFinite(Number(body?.totalTrashWeightLbs))
          ? Number(body.totalTrashWeightLbs)
          : scan.totalTrashWeightLbs,
        pointsAwarded,
        verifiedCoverageRatio: coverage.ratio,
      },
      now,
    )
    if (!updated) {
      return NextResponse.json(
        { data: null, success: false, message: 'Scan changed while it was being submitted — reload and try again' },
        { status: 409 },
      )
    }

    // The client's coveredCellIds are only a hint: narrowed to cells the
    // server recorded THIS scan working on THIS route, so a submission can
    // never resolve community reports somewhere the worker did not scan.
    const sweepCells = supersedableCells(parseCoveredCellIds(body?.coveredCellIds), workedCells, requiredCells)
    if (sweepCells.length > 0) {
      const confirmedCells = new Set(hotspots.map((hotspot) => gridCell(hotspot.lat, hotspot.lng)))
      const staleCells = sweepCells.filter((cellId) => !confirmedCells.has(cellId))
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
