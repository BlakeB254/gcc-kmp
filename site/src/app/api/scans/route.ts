import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { blocks, routeScans, routes, workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { normalizeDeviceType, toMobileRouteScan } from '@/lib/mobile-route-scan-response'
import { ensureJobForRouteWorker } from '@/lib/ensure-job-for-route-worker'
import { findActiveScanConflict } from '@/lib/scan-completion'

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

    // Admin proxy: resolve effective worker + audit id
    const onBehalfOfWorkerId = typeof body?.onBehalfOfWorkerId === 'number' ? body.onBehalfOfWorkerId : null
    let effectiveWorkerId = auth.workerId
    let performedByAdminId: number | null = null
    if (onBehalfOfWorkerId != null) {
      if (auth.role !== 'admin') {
        return NextResponse.json({ error: 'Only admins can scan on behalf of a worker' }, { status: 403 })
      }
      const [target] = await db.select().from(workers).where(eq(workers.id, onBehalfOfWorkerId)).limit(1)
      if (!target || target.verificationStatus !== 'approved') {
        return NextResponse.json({ error: 'Target worker is not a verified user' }, { status: 422 })
      }
      effectiveWorkerId = onBehalfOfWorkerId
      performedByAdminId = auth.workerId
    }

    const [route] = await db
      .select({ id: routes.id })
      .from(routes)
      .where(eq(routes.id, routeId))
      .limit(1)

    if (!route) {
      return NextResponse.json({ data: null, success: false, message: 'Route not found' }, { status: 404 })
    }

    // One active route scan per worker, and per person walking it (an admin
    // performing on someone's behalf). Coverage events are attributed to the
    // caller's single active scan at ingest (POST /api/scans/coverage), so a
    // second concurrent scan would make that attribution ambiguous.
    const conflict = await findActiveScanConflict(
      db,
      { workerId: effectiveWorkerId, callerId: auth.workerId },
      new Date().toISOString(),
    )
    if (conflict) {
      const whose =
        conflict.workerId === effectiveWorkerId
          ? effectiveWorkerId === auth.workerId
            ? 'You already have'
            : 'This worker already has'
          : 'You are already performing'
      return NextResponse.json(
        {
          data: { activeScanId: conflict.id },
          success: false,
          message: `${whose} a route scan in progress (scan ${conflict.id}). Finish it before starting another.`,
        },
        { status: 409 },
      )
    }

    // Ensure a Job exists for (route, effectiveWorker) when acting on behalf
    if (onBehalfOfWorkerId != null) {
      await ensureJobForRouteWorker(routeId, effectiveWorkerId, { performedByAdminId })
    }

    // Resolve the owning block (1:1 via blocks.route_id) so downstream
    // observation writes can run block exclusion-zone checks. Null when no
    // block row references this route.
    const [block] = await db
      .select({ id: blocks.id })
      .from(blocks)
      .where(eq(blocks.routeId, routeId))
      .limit(1)

    const now = new Date().toISOString()
    const [scan] = await db
      .insert(routeScans)
      .values({
        routeId,
        blockId: block?.id ?? null,
        workerId: effectiveWorkerId,
        deviceType: normalizeDeviceType(body?.deviceType),
        phase: 'scouting',
        coveragePercent: 0,
        scoutStartedAt: now,
        startLat,
        startLng,
        status: 'in_progress',
        pointsAwarded: 0,
        performedByAdminId,
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
