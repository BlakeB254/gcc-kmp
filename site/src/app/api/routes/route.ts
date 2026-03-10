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
        description: routes.description,
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

    // Combine routes with checkpoints (field names match Kotlin Route model)
    const result = routeList.map((route) => ({
      id: route.id,
      code: route.code,
      displayName: route.displayName,
      serviceAreaId: route.serviceAreaId,
      geometry: route.geometry,
      status: route.status,
      distanceMeters: route.lengthMeters ? parseFloat(route.lengthMeters) : null,
      estimatedDurationMinutes: route.estimatedMinutes ? Math.round(parseFloat(route.estimatedMinutes)) : null,
      payoutAmount: Math.round(parseFloat(route.payoutAmount || '10000')),
      checkpoints: (checkpointsMap.get(route.id) || []).map((cp) => ({
        name: cp.name,
        sequenceOrder: cp.sequenceOrder,
        coordinates: { lat: (cp.coordinates as [number, number])?.[1] ?? 0, lng: (cp.coordinates as [number, number])?.[0] ?? 0 },
        photoRequired: cp.photoRequired ?? true,
        geofenceRadius: parseFloat(cp.geofenceRadius || '15'),
        type: cp.type ?? 'photo_point',
      })),
      imageUrl: route.imageUrl || null,
      description: route.description || null,
      createdAt: new Date(route.createdAt).toISOString(),
      updatedAt: new Date(route.updatedAt).toISOString(),
    }))

    return NextResponse.json({ data: result, success: true })
  } catch (error) {
    console.error('Error fetching routes:', error)
    return NextResponse.json(
      { error: 'Failed to fetch routes' },
      { status: 500 }
    )
  }
}
