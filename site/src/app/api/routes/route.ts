import { NextResponse } from 'next/server'
import { db } from '@/db'
import { blocks, routes, serviceAreas } from '@/db/schema'
import { eq, and, inArray } from 'drizzle-orm'

/**
 * GET /api/routes - Get published routes
 *
 * Returns routes that are available for workers to claim.
 * Only returns published routes with their scannable geometry.
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
        claimStatus: routes.claimStatus,
        publishAt: routes.publishAt,
        unpublishAt: routes.unpublishAt,
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

    const routeIds = routeList.map((r) => r.id)
    const blockBoundaryMap = new Map<number, unknown>()
    const innerBoundaryMap = new Map<number, unknown>()

    if (routeIds.length > 0) {
      const routeBlocks = await db
        .select({
          routeId: blocks.routeId,
          locationBoundary: blocks.locationBoundary,
          innerBoundary: blocks.innerBoundary,
        })
        .from(blocks)
        .where(inArray(blocks.routeId, routeIds))

      for (const block of routeBlocks) {
        if (block.routeId && block.locationBoundary) {
          blockBoundaryMap.set(block.routeId, block.locationBoundary)
        }
        if (block.routeId && block.innerBoundary) {
          innerBoundaryMap.set(block.routeId, block.innerBoundary)
        }
      }
    }

    const result = routeList.flatMap((route) => {
      if (
        !blockBoundaryMap.get(route.id) &&
        !route.geometry
      ) {
        return []
      }

      return [{
        id: route.id,
        code: route.code,
        displayName: route.displayName,
        serviceAreaId: route.serviceAreaId,
        geometry: route.geometry,
        blockBoundary: blockBoundaryMap.get(route.id) ?? null,
        innerBoundary: innerBoundaryMap.get(route.id) ?? null,
        status: route.status,
        // Claim switch + scheduled window so mobile can render "opens Friday"
        // states. Claimable = published + claim_status='open'.
        claimStatus: route.claimStatus ?? 'closed',
        claimable: route.status === 'published' && route.claimStatus === 'open',
        publishAt: route.claimStatus === 'scheduled' && route.publishAt ? new Date(route.publishAt).toISOString() : null,
        unpublishAt: route.claimStatus === 'scheduled' && route.unpublishAt ? new Date(route.unpublishAt).toISOString() : null,
        distanceMeters: route.lengthMeters ? parseFloat(route.lengthMeters) : null,
        estimatedDurationMinutes: route.estimatedMinutes ? Math.round(parseFloat(route.estimatedMinutes)) : null,
        payoutAmount: Math.round(parseFloat(route.payoutAmount || '10000')),
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
