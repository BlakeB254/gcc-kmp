import { NextResponse } from 'next/server'
import { db } from '@/db'
import { routes, serviceAreas, blocks } from '@/db/schema'
import { eq, inArray } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'
import { parseAdminClaimStatus } from '@/lib/admin-route-status'
import { parseOptionalTimestamp } from '@/lib/admin-route-sync'
import { commitPublicDataChanges } from '@/lib/public-data/invalidation'
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
        claimStatus: routes.claimStatus,
        publishAt: routes.publishAt,
        unpublishAt: routes.unpublishAt,
        payoutAmount: routes.payoutAmount,
        imageUrl: routes.imageUrl,
        description: routes.description,
        createdAt: routes.createdAt,
        updatedAt: routes.updatedAt,
      })
      .from(routes)
      .leftJoin(serviceAreas, eq(routes.serviceAreaId, serviceAreas.id))

    const routeIds = routeList.map((r) => r.id)
    const blockBoundaryMap = new Map<number, unknown>()
    const blockIdMap = new Map<number, number>()

    if (routeIds.length > 0) {
      const routeBlocks = await db.select({
        id: blocks.id,
        routeId: blocks.routeId,
        locationBoundary: blocks.locationBoundary,
      }).from(blocks)
        .where(inArray(blocks.routeId, routeIds))

      for (const block of routeBlocks) {
        if (block.routeId) {
          blockIdMap.set(block.routeId, block.id)
          if (block.locationBoundary) {
            blockBoundaryMap.set(block.routeId, block.locationBoundary)
          }
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
      claimStatus: route.claimStatus ?? 'closed',
      publishAt: route.publishAt ? new Date(route.publishAt).toISOString() : null,
      unpublishAt: route.unpublishAt ? new Date(route.unpublishAt).toISOString() : null,
      distanceMeters: route.lengthMeters ? parseFloat(route.lengthMeters) : null,
      estimatedDurationMinutes: route.estimatedMinutes ? Math.round(parseFloat(route.estimatedMinutes)) : null,
      payoutAmount: Math.round(parseFloat(route.payoutAmount || '10000')),
      imageUrl: route.imageUrl || null,
      description: route.description || null,
      blockId: blockIdMap.get(route.id) ?? null,
      // The list rows show boundary status — without this the mobile
      // subtitle said 'no boundary yet' for every route (thin-DTO lie).
      blockBoundary: blockBoundaryMap.get(route.id) ?? null,
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
    const { displayName, serviceAreaId = 1, geometry, code, payoutAmount, side, streetName, fromCorner, toCorner, description, claimStatus, publishAt, unpublishAt } = body

    if (!displayName) {
      return NextResponse.json({ error: 'displayName is required' }, { status: 400 })
    }

    // Optional claim switch + scheduled (un)publish timestamps (mobile DTOs
    // already send these; previously ignored — inventory finding (e)).
    let parsedClaimStatus: 'closed' | 'open' | 'scheduled' = 'closed'
    if (claimStatus !== undefined) {
      const parsed = parseAdminClaimStatus(claimStatus)
      if (parsed === null) {
        return NextResponse.json({ error: 'claimStatus must be closed, open, or scheduled' }, { status: 400 })
      }
      parsedClaimStatus = parsed
    }
    let parsedPublishAt: string | null
    let parsedUnpublishAt: string | null
    try {
      parsedPublishAt = parseOptionalTimestamp(publishAt, 'publishAt')
      parsedUnpublishAt = parseOptionalTimestamp(unpublishAt, 'unpublishAt')
    } catch (e) {
      return NextResponse.json({ error: (e as Error).message }, { status: 400 })
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
      claimStatus: parsedClaimStatus,
      publishAt: parsedPublishAt,
      unpublishAt: parsedUnpublishAt,
      payoutAmount: (payoutAmount || 10000).toString(),
      description: description || null,
      createdById: auth.workerId,
    }).returning()

    // Create matching block
    const blockPolygon = lineStringToBlockPolygon(coords)
    const center = computeCenter(blockPolygon)

    // Funded block without authored boundary: public+impact block counts only.
    const [newBlock] = await commitPublicDataChanges(
      ['public-stats', 'impact-stats'],
      () => db.insert(blocks).values({
        code: blockCode,
        name: displayName,
        serviceAreaId,
        routeId: newRoute.id,
        // NO auto-seeded boundary: admins author the block's outer edge in
        // the mobile Boundary Studio (draw-first flow). A machine rectangle
        // must never masquerade as an authored boundary.
        locationBoundary: null,
        locationCenter: { type: 'Point', coordinates: [center.lng, center.lat] },
        streetName: streetName || null,
        fromCorner: fromCorner || null,
        toCorner: toCorner || null,
        side: side || 'north',
        status: 'funded',
      }).returning(),
    )

    return NextResponse.json({
      data: {
        id: newRoute.id,
        code: newRoute.code,
        displayName: newRoute.displayName,
        serviceAreaId: newRoute.serviceAreaId,
        geometry: newRoute.geometry,
        status: newRoute.status,
        claimStatus: newRoute.claimStatus ?? 'closed',
        publishAt: newRoute.publishAt ? new Date(newRoute.publishAt).toISOString() : null,
        unpublishAt: newRoute.unpublishAt ? new Date(newRoute.unpublishAt).toISOString() : null,
        distanceMeters: newRoute.lengthMeters ? parseFloat(newRoute.lengthMeters) : null,
        estimatedDurationMinutes: newRoute.estimatedMinutes ? Math.round(parseFloat(newRoute.estimatedMinutes)) : null,
        payoutAmount: Math.round(parseFloat(newRoute.payoutAmount || '10000')),
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
