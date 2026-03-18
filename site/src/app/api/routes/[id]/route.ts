import { NextResponse } from 'next/server'
import { db } from '@/db'
import { routes, routeCheckpoints, serviceAreas } from '@/db/schema'
import { eq } from 'drizzle-orm'

/**
 * GET /api/routes/[id] - Get a single route by ID
 *
 * Returns the route with its checkpoints in mobile ApiResponse format.
 */
export async function GET(
  _request: Request,
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

    // Get checkpoints for this route
    const checkpoints = await db
      .select()
      .from(routeCheckpoints)
      .where(eq(routeCheckpoints.routeId, routeId))
      .orderBy(routeCheckpoints.sequenceOrder)

    const result = {
      id: route.id,
      code: route.code,
      displayName: route.displayName,
      serviceAreaId: route.serviceAreaId,
      geometry: route.geometry,
      status: route.status,
      distanceMeters: route.lengthMeters ? parseFloat(route.lengthMeters) : null,
      estimatedDurationMinutes: route.estimatedMinutes
        ? Math.round(parseFloat(route.estimatedMinutes))
        : null,
      payoutAmount: Math.round(parseFloat(route.payoutAmount || '10000')),
      checkpoints: checkpoints.map((cp) => ({
        name: cp.name,
        sequenceOrder: cp.sequenceOrder,
        coordinates: {
          lat: (cp.coordinates as [number, number])?.[1] ?? 0,
          lng: (cp.coordinates as [number, number])?.[0] ?? 0,
        },
        photoRequired: cp.photoRequired ?? true,
        geofenceRadius: parseFloat(cp.geofenceRadius || '15'),
        type: cp.type ?? 'photo_point',
      })),
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
