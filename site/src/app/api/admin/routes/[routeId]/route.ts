/**
 * Admin Route Management API
 * PUT    /api/admin/routes/:routeId - Update route (status, imageUrl, etc.)
 * DELETE /api/admin/routes/:routeId - Delete a route
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { blocks, routes, routeCheckpoints } from '@/db/schema'
import { and, eq, inArray } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'
import { toMobileRouteCheckpoints } from '@/lib/route-checkpoints'

export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ routeId: string }> }

function toRouteResponse(route: any, checkpoints: any[] = [], checkpointGeometry: unknown = route.geometry) {
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
    checkpoints: toMobileRouteCheckpoints(checkpoints, checkpointGeometry),
    imageUrl: route.imageUrl || null,
    imageLocationLat: route.imageLocationLat ?? null,
    imageLocationLng: route.imageLocationLng ?? null,
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

    if (body.status !== undefined) {
      updateData.status = body.status
      updateData.publishedAt = body.status === 'published' ? new Date().toISOString() : null
    }
    if (body.imageUrl !== undefined) updateData.imageUrl = body.imageUrl
    if (body.displayName !== undefined) updateData.displayName = body.displayName
    if (body.description !== undefined) updateData.description = body.description
    if (body.imageLocationLat !== undefined) updateData.imageLocationLat = body.imageLocationLat
    if (body.imageLocationLng !== undefined) updateData.imageLocationLng = body.imageLocationLng
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

    if (body.status !== undefined) {
      await db
        .update(blocks)
        .set({
          status: body.status === 'published' ? 'available' : 'funded',
          updatedAt: new Date().toISOString(),
        })
        .where(
          and(
            eq(blocks.routeId, routeId),
            inArray(blocks.status, ['available', 'funded']),
          ),
        )
    }

    // Fetch checkpoints for the response
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

    return NextResponse.json({
      data: toRouteResponse(updated, checkpoints, routeBlock?.locationBoundary ?? updated.geometry),
    })
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
