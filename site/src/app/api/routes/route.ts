import { NextResponse } from 'next/server'
import { db } from '@/db'
import { blocks, routes, routeCheckpoints, serviceAreas } from '@/db/schema'
import { eq, and, inArray } from 'drizzle-orm'
import { toMobileRouteCheckpoints } from '@/lib/route-checkpoints'

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

    // Filter by status unless 'all' is requested
    if (status !== 'all') {
      conditions.push(eq(routes.status, status as 'draft' | 'published' | 'archived'))
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
        createdAt: routes.createdAt,
        updatedAt: routes.updatedAt,
        imageUrl: routes.imageUrl,
        imageLocationLat: routes.imageLocationLat,
        imageLocationLng: routes.imageLocationLng,
        description: routes.description,
      })
      .from(routes)
      .leftJoin(serviceAreas, eq(routes.serviceAreaId, serviceAreas.id))
      .where(conditions.length > 0 ? and(...conditions) : undefined)

    // Get checkpoints for all routes
    const routeIds = routeList.map((r) => r.id)

    let checkpointsMap: Map<number, typeof routeCheckpoints.$inferSelect[]> = new Map()
    const blockBoundaryMap = new Map<number, unknown>()

    if (routeIds.length > 0) {
      const [allCheckpoints, routeBlocks] = await Promise.all([
        db
          .select()
          .from(routeCheckpoints)
          .where(inArray(routeCheckpoints.routeId, routeIds))
          .orderBy(routeCheckpoints.sequenceOrder),
        db
          .select({
            routeId: blocks.routeId,
            locationBoundary: blocks.locationBoundary,
          })
          .from(blocks)
          .where(inArray(blocks.routeId, routeIds)),
      ])

      // Group by route
      for (const cp of allCheckpoints) {
        if (!checkpointsMap.has(cp.routeId)) {
          checkpointsMap.set(cp.routeId, [])
        }
        checkpointsMap.get(cp.routeId)!.push(cp)
      }

      for (const block of routeBlocks) {
        if (block.routeId && block.locationBoundary) {
          blockBoundaryMap.set(block.routeId, block.locationBoundary)
        }
      }
    }

    // Combine routes with checkpoints (field names match Kotlin Route model)
    const result = routeList.flatMap((route) => {
      const checkpoints = toMobileRouteCheckpoints(
        checkpointsMap.get(route.id) || [],
        blockBoundaryMap.get(route.id) ?? route.geometry,
      )
      if (checkpoints.length < 2) return []

      return [{
        id: route.id,
        code: route.code,
        displayName: route.displayName,
        serviceAreaId: route.serviceAreaId,
        geometry: route.geometry,
        blockBoundary: blockBoundaryMap.get(route.id) ?? null,
        status: route.status,
        distanceMeters: route.lengthMeters ? parseFloat(route.lengthMeters) : null,
        estimatedDurationMinutes: route.estimatedMinutes ? Math.round(parseFloat(route.estimatedMinutes)) : null,
        payoutAmount: Math.round(parseFloat(route.payoutAmount || '10000')),
        checkpoints,
        imageUrl: route.imageUrl || null,
        imageLocationLat: route.imageLocationLat ?? null,
        imageLocationLng: route.imageLocationLng ?? null,
        description: route.description || null,
        createdAt: new Date(route.createdAt).toISOString(),
        updatedAt: new Date(route.updatedAt).toISOString(),
      }]
    })

    return NextResponse.json({ data: result, success: true })
  } catch (error) {
    console.error('Error fetching routes:', error)
    return NextResponse.json(
      { error: 'Failed to fetch routes' },
      { status: 500 }
    )
  }
}
