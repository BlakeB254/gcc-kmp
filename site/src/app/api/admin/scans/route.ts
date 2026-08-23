import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { routeScans, workers } from '@/db/schema'
import { desc, eq } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'

const DEFAULT_LIMIT = 50
const MAX_LIMIT = 200

/**
 * Local scan-visualizer list view — not a mobile-facing endpoint. Returns
 * the most recent route-scan sessions with the worker's name attached so
 * the admin scan inspector doesn't need a second round-trip per row.
 */
export async function GET(request: NextRequest) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const url = new URL(request.url)
    const limit = Math.min(MAX_LIMIT, Math.max(1, Number(url.searchParams.get('limit')) || DEFAULT_LIMIT))
    const offset = Math.max(0, Number(url.searchParams.get('offset')) || 0)

    const rows = await db
      .select({
        id: routeScans.id,
        routeId: routeScans.routeId,
        workerId: routeScans.workerId,
        workerName: workers.name,
        deviceType: routeScans.deviceType,
        phase: routeScans.phase,
        status: routeScans.status,
        coveragePercent: routeScans.coveragePercent,
        startLat: routeScans.startLat,
        startLng: routeScans.startLng,
        totalTrashVolumeLiters: routeScans.totalTrashVolumeLiters,
        totalTrashWeightLbs: routeScans.totalTrashWeightLbs,
        pointsAwarded: routeScans.pointsAwarded,
        scoutStartedAt: routeScans.scoutStartedAt,
        cleanCompletedAt: routeScans.cleanCompletedAt,
        createdAt: routeScans.createdAt,
      })
      .from(routeScans)
      .leftJoin(workers, eq(workers.id, routeScans.workerId))
      .orderBy(desc(routeScans.createdAt))
      .limit(limit)
      .offset(offset)

    return NextResponse.json({ data: rows, success: true })
  } catch (error) {
    console.error('Admin list scans error:', error)
    return NextResponse.json({ data: null, success: false, message: 'Failed to list scans' }, { status: 500 })
  }
}
