import { NextResponse } from 'next/server'
import { db } from '@/db'
import { routes, routeCheckpoints, serviceAreas } from '@/db/schema'
import { eq, and } from 'drizzle-orm'

/**
 * GET /api/routes - Get published routes
 *
 * Returns routes that are available for workers to claim.
 * Only returns published routes with their checkpoints.
 *
 * Query params:
 * - serviceAreaId: Filter by service area
 * - status: Filter by status (default: published)
 */
export async function GET(request: Request) {
  try {
    const { searchParams } = new URL(request.url)
    const serviceAreaId = searchParams.get('serviceAreaId')
    const status = searchParams.get('status') || 'published'

    // Build conditions
    const conditions = []

    // Only return published routes by default for the public API
    if (status === 'published') {
      conditions.push(eq(routes.status, 'published'))
    }

    if (serviceAreaId) {
      conditions.push(eq(routes.serviceAreaId, parseInt(serviceAreaId, 10)))
    }

    // Get routes
    const routeList = await db
      .select({
        id: routes.id,
        code: routes.code,
        displayName: routes.displayName,
        serviceAreaId: routes.serviceAreaId,
        serviceAreaName: serviceAreas.name,
        geometry: routes.geometry,
        lengthMeters: routes.lengthMeters,
        estimatedMinutes: routes.estimatedMinutes,
        status: routes.status,
        payoutAmount: routes.payoutAmount,
      })
      .from(routes)
      .leftJoin(serviceAreas, eq(routes.serviceAreaId, serviceAreas.id))
      .where(conditions.length > 0 ? and(...conditions) : undefined)

    // Get checkpoints for all routes
    const routeIds = routeList.map((r) => r.id)

    let checkpointsMap: Map<number, typeof routeCheckpoints.$inferSelect[]> = new Map()

    if (routeIds.length > 0) {
      const allCheckpoints = await db
        .select()
        .from(routeCheckpoints)
        .orderBy(routeCheckpoints.sequenceOrder)

      // Group by route
      for (const cp of allCheckpoints) {
        if (routeIds.includes(cp.routeId)) {
          if (!checkpointsMap.has(cp.routeId)) {
            checkpointsMap.set(cp.routeId, [])
          }
          checkpointsMap.get(cp.routeId)!.push(cp)
        }
      }
    }

    // Combine routes with checkpoints
    const result = routeList.map((route) => ({
      ...route,
      lengthMeters: route.lengthMeters ? parseFloat(route.lengthMeters) : null,
      estimatedMinutes: route.estimatedMinutes ? parseFloat(route.estimatedMinutes) : null,
      payoutAmount: parseFloat(route.payoutAmount || '10000'),
      checkpoints: (checkpointsMap.get(route.id) || []).map((cp) => ({
        name: cp.name,
        sequenceOrder: cp.sequenceOrder,
        coordinates: cp.coordinates as [number, number],
        photoRequired: cp.photoRequired ?? true,
        photoInstructions: cp.photoInstructions,
        geofenceRadius: parseFloat(cp.geofenceRadius || '15'),
        type: cp.type,
      })),
    }))

    return NextResponse.json(result)
  } catch (error) {
    console.error('Error fetching routes:', error)
    return NextResponse.json(
      { error: 'Failed to fetch routes' },
      { status: 500 }
    )
  }
}
