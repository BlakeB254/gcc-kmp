import { NextResponse } from 'next/server'
import { db } from '@/db'
import { routes, routeCheckpoints, serviceAreas } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'

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
      const allCheckpoints = await db.select().from(routeCheckpoints).orderBy(routeCheckpoints.sequenceOrder)
      for (const cp of allCheckpoints) {
        if (routeIds.includes(cp.routeId)) {
          if (!checkpointsMap.has(cp.routeId)) checkpointsMap.set(cp.routeId, [])
          checkpointsMap.get(cp.routeId)!.push(cp)
        }
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
