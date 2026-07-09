/**
 * Admin Route Management API
 * PUT    /api/admin/routes/:routeId - Update route (status, imageUrl, etc.)
 * DELETE /api/admin/routes/:routeId - Delete a route
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { blocks, blockExclusionZones, routes } from '@/db/schema'
import { and, eq, inArray } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'

export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ routeId: string }> }

function toRouteResponse(route: any) {
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

    return NextResponse.json({
      data: toRouteResponse(updated),
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

    // Delete the route and everything hanging off it: the linked block
    // (blocks.routeId is 1:1 auto-created with the route) and that block's
    // exclusion zones. db.batch runs all statements in a single transaction
    // on the neon-http driver.
    const [, , deletedRoutes] = await db.batch([
      db.delete(blockExclusionZones).where(
        inArray(
          blockExclusionZones.blockId,
          db.select({ id: blocks.id }).from(blocks).where(eq(blocks.routeId, routeId)),
        ),
      ),
      db.delete(blocks).where(eq(blocks.routeId, routeId)),
      db.delete(routes).where(eq(routes.id, routeId)).returning({ id: routes.id }),
    ])
    const deleted = deletedRoutes[0]

    if (!deleted) {
      return NextResponse.json({ error: 'Route not found' }, { status: 404 })
    }

    return NextResponse.json({ data: { id: deleted.id, deleted: true }, success: true })
  } catch (error) {
    console.error('DELETE /api/admin/routes/[routeId] error:', error)
    return NextResponse.json({ error: 'Failed to delete route' }, { status: 500 })
  }
}
