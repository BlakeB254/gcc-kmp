/**
 * Admin Route Management API
 * PUT    /api/admin/routes/:routeId - Update route (status, imageUrl, etc.)
 * DELETE /api/admin/routes/:routeId - Delete a route
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { routes, routeCheckpoints, serviceAreas } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'

export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ routeId: string }> }

function toRouteResponse(route: any, checkpoints: any[] = []) {
  return {
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
    description: route.description || null,
    createdAt: new Date(route.createdAt).toISOString(),
    updatedAt: new Date(route.updatedAt).toISOString(),
  }
}

export async function PUT(request: NextRequest, context: RouteContext) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const { routeId: routeIdStr } = await context.params
    const routeId = parseInt(routeIdStr, 10)
    if (isNaN(routeId)) {
      return NextResponse.json({ error: 'Invalid route ID' }, { status: 400 })
    }

    const body = await request.json()
    const updateData: Record<string, any> = {
      updatedAt: new Date().toISOString(),
    }

    if (body.status !== undefined) updateData.status = body.status
    if (body.imageUrl !== undefined) updateData.imageUrl = body.imageUrl
    if (body.displayName !== undefined) updateData.displayName = body.displayName
    if (body.description !== undefined) updateData.description = body.description
    if (body.payoutAmount !== undefined) {
      updateData.payoutAmount = String(body.payoutAmount * 100) // Convert dollars to cents
    }

    const [updated] = await db
      .update(routes)
      .set(updateData)
      .where(eq(routes.id, routeId))
      .returning()

    if (!updated) {
      return NextResponse.json({ error: 'Route not found' }, { status: 404 })
    }

    // Fetch checkpoints for the response
    const checkpoints = await db
      .select()
      .from(routeCheckpoints)
      .where(eq(routeCheckpoints.routeId, routeId))
      .orderBy(routeCheckpoints.sequenceOrder)

    return NextResponse.json({ data: toRouteResponse(updated, checkpoints) })
  } catch (error) {
    console.error('PUT /api/admin/routes/[routeId] error:', error)
    return NextResponse.json({ error: 'Failed to update route' }, { status: 500 })
  }
}

export async function DELETE(request: NextRequest, context: RouteContext) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const { routeId: routeIdStr } = await context.params
    const routeId = parseInt(routeIdStr, 10)
    if (isNaN(routeId)) {
      return NextResponse.json({ error: 'Invalid route ID' }, { status: 400 })
    }

    // Delete checkpoints first, then route
    await db.delete(routeCheckpoints).where(eq(routeCheckpoints.routeId, routeId))
    const [deleted] = await db
      .delete(routes)
      .where(eq(routes.id, routeId))
      .returning({ id: routes.id })

    if (!deleted) {
      return NextResponse.json({ error: 'Route not found' }, { status: 404 })
    }

    return NextResponse.json({ data: { id: deleted.id, deleted: true }, success: true })
  } catch (error) {
    console.error('DELETE /api/admin/routes/[routeId] error:', error)
    return NextResponse.json({ error: 'Failed to delete route' }, { status: 500 })
  }
}
