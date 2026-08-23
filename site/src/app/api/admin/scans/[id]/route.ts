import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { reconstructionAssets, routeScans, scanCoverageEvents, scanHotspots, workers } from '@/db/schema'
import { asc, desc, eq, inArray } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'
import { gridCell } from '@/lib/world-observations'
import { isR2Configured, getR2ViewUrl } from '@/lib/r2'
import { toMobileScanHotspot } from '@/lib/mobile-route-scan-response'

type RouteContext = { params: Promise<{ id: string }> }

/**
 * Local scan-visualizer detail view — deliberately unfiltered (unlike the
 * mobile-facing GET /api/scans/[id]): it shows every hotspot regardless of
 * the private-property exclusion, plus any reconstructed 3D assets and raw
 * R2 capture blobs known for the location cell(s) this scan touched, so an
 * operator can see the full raw truth behind a scan, not the worker's view
 * of it.
 */
export async function GET(request: NextRequest, context: RouteContext) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const { id } = await context.params
    const scanId = parseInt(id, 10)
    if (Number.isNaN(scanId)) {
      return NextResponse.json({ data: null, success: false, message: 'Invalid scan ID' }, { status: 400 })
    }

    const [scanRow] = await db
      .select({
        scan: routeScans,
        workerName: workers.name,
      })
      .from(routeScans)
      .leftJoin(workers, eq(workers.id, routeScans.workerId))
      .where(eq(routeScans.id, scanId))
      .limit(1)

    if (!scanRow) {
      return NextResponse.json({ data: null, success: false, message: 'Scan not found' }, { status: 404 })
    }

    const hotspots = await db
      .select()
      .from(scanHotspots)
      .where(eq(scanHotspots.scanId, scanId))
      .orderBy(asc(scanHotspots.detectedAt))

    const cellIds = new Set<string>([gridCell(scanRow.scan.startLat, scanRow.scan.startLng)])
    for (const h of hotspots) cellIds.add(gridCell(h.lat, h.lng))
    const cellIdList = Array.from(cellIds)

    const [reconAssets, coverageEvents] = await Promise.all([
      db
        .select()
        .from(reconstructionAssets)
        .where(inArray(reconstructionAssets.locationCellId, cellIdList))
        .orderBy(desc(reconstructionAssets.version)),
      db
        .select()
        .from(scanCoverageEvents)
        .where(inArray(scanCoverageEvents.locationCellId, cellIdList))
        .orderBy(desc(scanCoverageEvents.capturedAt))
        .limit(200),
    ])

    const r2Ready = isR2Configured()
    const reconAssetsWithUrls = await Promise.all(
      reconAssets.map(async (a) => ({
        ...a,
        viewUrl: r2Ready ? await getR2ViewUrl(a.r2Key).catch(() => null) : null,
      }))
    )
    const coverageEventsWithUrls = await Promise.all(
      coverageEvents.map(async (e) => ({
        ...e,
        rawViewUrl: r2Ready && e.rawR2Key ? await getR2ViewUrl(e.rawR2Key).catch(() => null) : null,
      }))
    )

    return NextResponse.json({
      data: {
        scan: scanRow.scan,
        workerName: scanRow.workerName,
        hotspots: hotspots.map(toMobileScanHotspot),
        locationCellIds: cellIdList,
        reconstructionAssets: reconAssetsWithUrls,
        coverageEvents: coverageEventsWithUrls,
        r2Configured: r2Ready,
      },
      success: true,
    })
  } catch (error) {
    console.error('Admin scan detail error:', error)
    return NextResponse.json({ data: null, success: false, message: 'Failed to load scan' }, { status: 500 })
  }
}
