import { NextResponse } from 'next/server'
import { db } from '@/db'
import { routes, routeCheckpoints, serviceAreas, blocks } from '@/db/schema'
import { eq, inArray } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'
import { haversineDistance, estimateWalkingMinutes, lineStringToBlockPolygon, computeCenter } from '@/lib/geo-utils'
import { deriveAreaPrefix, generateNextCode } from '@/lib/route-code-generator'

export async function GET(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
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
        imageUrl: routes.imageUrl,
        createdAt: routes.createdAt,
        updatedAt: routes.updatedAt,
      })
      .from(routes)
      .leftJoin(serviceAreas, eq(routes.serviceAreaId, serviceAreas.id))

    const routeIds = routeList.map((r) => r.id)
    const checkpointsMap = new Map<number, any[]>()

    if (routeIds.length > 0) {
      const filtered = await db.select().from(routeCheckpoints)
        .where(inArray(routeCheckpoints.routeId, routeIds))
        .orderBy(routeCheckpoints.sequenceOrder)
      for (const cp of filtered) {
        if (!checkpointsMap.has(cp.routeId)) checkpointsMap.set(cp.routeId, [])
        checkpointsMap.get(cp.routeId)!.push(cp)
      }
    }

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
      createdAt: new Date(route.createdAt).toISOString(),
      updatedAt: new Date(route.updatedAt).toISOString(),
    }))

    return NextResponse.json({ data: result })
  } catch (error) {
    console.error('Error fetching admin routes:', error)
    return NextResponse.json({ error: 'Failed to fetch routes' }, { status: 500 })
  }
}

export async function POST(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const body = await request.json()
    const { displayName, serviceAreaId = 1, geometry, code, payoutAmount, side, streetName, fromCorner, toCorner, description } = body

    if (!displayName) {
      return NextResponse.json({ error: 'displayName is required' }, { status: 400 })
    }

    // If no geometry provided, default to service area center (North Lawndale)
    let routeGeometry = geometry
    if (!routeGeometry?.coordinates?.length) {
      const [area] = await db.select({ mapCenter: serviceAreas.mapCenter }).from(serviceAreas).where(eq(serviceAreas.id, serviceAreaId))
      const center = (area?.mapCenter as number[] | null) ?? [-87.7155, 41.8600]
      const lng = center[0], lat = center[1]
      // Create a tiny 50m line so the route has valid geometry
      const offset = 0.0003
      routeGeometry = { type: 'LineString', coordinates: [[lng - offset, lat], [lng + offset, lat]] }
    }

    // Auto-generate code if not provided
    let routeCode = code
    let blockCode: string | undefined
    if (!routeCode) {
      // Parallel DB reads for code generation
      const [areaRows, routeCodes, blockCodes] = await Promise.all([
        db.select({ name: serviceAreas.name }).from(serviceAreas).where(eq(serviceAreas.id, serviceAreaId)),
        db.select({ code: routes.code }).from(routes),
        db.select({ code: blocks.code }).from(blocks),
      ])
      const prefix = deriveAreaPrefix(areaRows[0]?.name || 'XX')
      const allCodes = [
        ...routeCodes.map(r => r.code),
        ...blockCodes.map(b => b.code).filter(Boolean) as string[],
      ]
      const generated = generateNextCode(allCodes, prefix, (side || 'north') as 'north' | 'south')
      routeCode = generated.routeCode
      blockCode = generated.blockCode
    } else {
      blockCode = routeCode.replace('-R', '-')
    }

    const coords = routeGeometry.coordinates as [number, number][]
    const distanceMeters = haversineDistance(coords)
    const estimatedMinutes = estimateWalkingMinutes(distanceMeters)

    // Create route
    const [newRoute] = await db.insert(routes).values({
      code: routeCode,
      displayName,
      serviceAreaId,
      geometry: routeGeometry,
      lengthMeters: distanceMeters.toFixed(1),
      estimatedMinutes: estimatedMinutes.toString(),
      status: 'draft',
      payoutAmount: (payoutAmount || 10000).toString(),
      description: description || null,
      createdById: auth.workerId,
    }).returning()

    // Create matching block
    const blockPolygon = lineStringToBlockPolygon(coords)
    const center = computeCenter(blockPolygon)

    const [newBlock] = await db.insert(blocks).values({
      code: blockCode,
      name: displayName,
      serviceAreaId,
      routeId: newRoute.id,
      locationBoundary: { type: 'Polygon', coordinates: blockPolygon },
      locationCenter: { type: 'Point', coordinates: [center.lng, center.lat] },
      streetName: streetName || null,
      fromCorner: fromCorner || null,
      toCorner: toCorner || null,
      side: side || 'north',
      status: 'available',
    }).returning()

    // Create start and end checkpoints
    const startCoord = coords[0]
    const endCoord = coords[coords.length - 1]
    await db.insert(routeCheckpoints).values([
      {
        routeId: newRoute.id,
        name: 'Start',
        sequenceOrder: 0,
        coordinates: startCoord,
        photoRequired: true,
        type: 'route_start',
      },
      {
        routeId: newRoute.id,
        name: 'End',
        sequenceOrder: 1,
        coordinates: endCoord,
        photoRequired: true,
        type: 'route_end',
      },
    ])

    // Fetch created checkpoints for the response
    const cps = await db.select().from(routeCheckpoints)
      .where(eq(routeCheckpoints.routeId, newRoute.id))

    return NextResponse.json({
      data: {
        id: newRoute.id,
        code: newRoute.code,
        displayName: newRoute.displayName,
        serviceAreaId: newRoute.serviceAreaId,
        geometry: newRoute.geometry,
        status: newRoute.status,
        distanceMeters: newRoute.lengthMeters ? parseFloat(newRoute.lengthMeters) : null,
        estimatedDurationMinutes: newRoute.estimatedMinutes ? Math.round(parseFloat(newRoute.estimatedMinutes)) : null,
        payoutAmount: Math.round(parseFloat(newRoute.payoutAmount || '10000')),
        checkpoints: cps.map((cp) => ({
          id: cp.id,
          name: cp.name,
          sequenceOrder: cp.sequenceOrder,
          coordinates: { lat: (cp.coordinates as [number, number])?.[1] ?? 0, lng: (cp.coordinates as [number, number])?.[0] ?? 0 },
          photoRequired: cp.photoRequired ?? true,
          geofenceRadius: parseFloat(cp.geofenceRadius || '15'),
          type: cp.type ?? 'photo_point',
        })),
        imageUrl: newRoute.imageUrl || null,
        createdAt: new Date(newRoute.createdAt).toISOString(),
        updatedAt: new Date(newRoute.updatedAt).toISOString(),
      },
    }, { status: 201 })
  } catch (error) {
    console.error('Error creating route:', error)
    return NextResponse.json({ error: 'Failed to create route' }, { status: 500 })
  }
}
