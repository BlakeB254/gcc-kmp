import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { routeScans, routes } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { normalizeDeviceType, toMobileRouteScan } from '@/lib/mobile-route-scan-response'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

export async function POST(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json({ data: null, success: false, message: 'Not authenticated' }, { status: 401 })
  }

  try {
    const body = await request.json().catch(() => null)
    const routeId = Number(body?.routeId)
    const startLat = Number(body?.startLat)
    const startLng = Number(body?.startLng)

    if (!Number.isInteger(routeId) || routeId <= 0) {
      return NextResponse.json({ data: null, success: false, message: 'routeId is required' }, { status: 400 })
    }
    if (!Number.isFinite(startLat) || !Number.isFinite(startLng)) {
      return NextResponse.json({ data: null, success: false, message: 'startLat and startLng are required' }, { status: 400 })
    }

    const [route] = await db
      .select({ id: routes.id })
      .from(routes)
      .where(eq(routes.id, routeId))
      .limit(1)

    if (!route) {
      return NextResponse.json({ data: null, success: false, message: 'Route not found' }, { status: 404 })
    }

    const now = new Date().toISOString()
    const [scan] = await db
      .insert(routeScans)
      .values({
        routeId,
        workerId: auth.workerId,
        deviceType: normalizeDeviceType(body?.deviceType),
        phase: 'scouting',
        coveragePercent: 0,
        scoutStartedAt: now,
        startLat,
        startLng,
        status: 'in_progress',
        pointsAwarded: 0,
        createdAt: now,
        updatedAt: now,
      })
      .returning()

    return NextResponse.json({ data: toMobileRouteScan(scan), success: true }, { status: 201 })
  } catch (error) {
    console.error('POST /api/scans error:', error)
    return NextResponse.json({ data: null, success: false, message: 'Failed to create scan' }, { status: 500 })
  }
}
