import { NextResponse } from 'next/server'
import { db } from '@/db'
import { blocks, jobs, routes, routeCheckpoints, routeScans, scanHotspots, serviceAreas } from '@/db/schema'
import { and, desc, eq, inArray } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { hotspotCheckpoints, toMobileRouteCheckpoints } from '@/lib/route-checkpoints'

/**
 * GET /api/routes/[id] - Get a single route by ID
 *
 * Returns the route with its checkpoints in mobile ApiResponse format.
 */
export async function GET(
  request: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  try {
    const { id } = await params
    const routeId = parseInt(id, 10)
    if (isNaN(routeId)) {
      return NextResponse.json(
        { error: 'Invalid route ID', success: false },
        { status: 400 },
      )
    }

    const [route] = await db
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
      .where(eq(routes.id, routeId))
      .limit(1)

    if (!route) {
      return NextResponse.json(
        { error: 'Route not found', success: false },
        { status: 404 },
      )
    }

    if (route.status !== 'published') {
      const auth = await requireMobileAuth(request)
      let canViewAssignedRoute = auth?.role === 'admin'

      if (!canViewAssignedRoute && auth?.workerId) {
        const routeBlocks = await db
          .select({ id: blocks.id })
          .from(blocks)
          .where(eq(blocks.routeId, routeId))

        if (routeBlocks.length > 0) {
          const [assignment] = await db
            .select({ id: jobs.id })
            .from(jobs)
            .where(
              and(
                inArray(jobs.blockId, routeBlocks.map((block) => block.id)),
                eq(jobs.workerId, auth.workerId),
                inArray(jobs.status, ['claimed', 'in_progress', 'pending_review']),
              ),
            )
            .limit(1)

          canViewAssignedRoute = !!assignment
        }
      }

      if (!canViewAssignedRoute) {
        return NextResponse.json(
          { error: 'Route is not available', success: false },
          { status: 404 },
        )
      }
    }

    // Get checkpoints for this route
    const checkpoints = await db
      .select()
      .from(routeCheckpoints)
      .where(eq(routeCheckpoints.routeId, routeId))
      .orderBy(routeCheckpoints.sequenceOrder)

    const [routeBlock] = await db
      .select({ locationBoundary: blocks.locationBoundary })
      .from(blocks)
      .where(eq(blocks.routeId, routeId))
      .limit(1)

    const baseCheckpointResponse = toMobileRouteCheckpoints(
      checkpoints,
      routeBlock?.locationBoundary ?? route.geometry,
    )
    if (baseCheckpointResponse.length < 2) {
      return NextResponse.json(
        { error: 'Route is not configured for work', success: false },
        { status: 404 },
      )
    }

    let historicalHotspots: Array<{
      lat: number
      lng: number
      severity: number
      detectedAt: string | Date
      status: string
    }> = []
    try {
      historicalHotspots = await db
        .select({
          lat: scanHotspots.lat,
          lng: scanHotspots.lng,
          severity: scanHotspots.severity,
          detectedAt: scanHotspots.detectedAt,
          status: scanHotspots.status,
        })
        .from(scanHotspots)
        .innerJoin(routeScans, eq(scanHotspots.scanId, routeScans.id))
        .where(
          and(
            eq(routeScans.routeId, routeId),
            inArray(scanHotspots.status, ['detected', 'skipped']),
          ),
        )
        .orderBy(desc(scanHotspots.detectedAt))
        .limit(4)
    } catch (hotspotError) {
      console.warn('Unable to attach route hotspot checkpoints:', hotspotError)
    }

    const checkpointResponse = [
      ...baseCheckpointResponse,
      ...hotspotCheckpoints(historicalHotspots, baseCheckpointResponse.length + 1),
    ]

    const result = {
      id: route.id,
      code: route.code,
      displayName: route.displayName,
      serviceAreaId: route.serviceAreaId,
      geometry: route.geometry,
      blockBoundary: routeBlock?.locationBoundary ?? null,
      innerBoundary: routeBlock?.innerBoundary ?? null,
      status: route.status,
      distanceMeters: route.lengthMeters ? parseFloat(route.lengthMeters) : null,
      estimatedDurationMinutes: route.estimatedMinutes
        ? Math.round(parseFloat(route.estimatedMinutes))
        : null,
      payoutAmount: Math.round(parseFloat(route.payoutAmount || '10000')),
      checkpoints: checkpointResponse,
      imageUrl: route.imageUrl || null,
      imageLocationLat: route.imageLocationLat ?? null,
      imageLocationLng: route.imageLocationLng ?? null,
      description: route.description || null,
      createdAt: new Date(route.createdAt).toISOString(),
      updatedAt: new Date(route.updatedAt).toISOString(),
    }

    return NextResponse.json({ data: result, success: true })
  } catch (error) {
    console.error('Error fetching route:', error)
    return NextResponse.json(
      { error: 'Failed to fetch route', success: false },
      { status: 500 },
    )
  }
}
