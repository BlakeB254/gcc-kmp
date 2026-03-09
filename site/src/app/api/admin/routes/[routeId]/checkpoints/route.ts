/**
 * Admin Route Checkpoint Management API
 * POST   /api/admin/routes/:routeId/checkpoints - Add a checkpoint to a route
 * DELETE /api/admin/routes/:routeId/checkpoints?id=N - Delete a checkpoint
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { routeCheckpoints } from '@/db/schema'
import { eq, and } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'

export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ routeId: string }> }

export async function POST(request: NextRequest, context: RouteContext) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const { routeId: routeIdStr } = await context.params
    const routeId = parseInt(routeIdStr, 10)
    if (isNaN(routeId)) {
      return NextResponse.json({ error: 'Invalid route ID' }, { status: 400 })
    }

    const body = await request.json()
    const { name, lat, lng, sequenceOrder, photoRequired, geofenceRadius, type, instructions } = body

    if (!name || lat === undefined || lng === undefined || sequenceOrder === undefined) {
      return NextResponse.json(
        { error: 'name, lat, lng, and sequenceOrder are required' },
        { status: 400 },
      )
    }

    const [checkpoint] = await db
      .insert(routeCheckpoints)
      .values({
        routeId,
        name,
        sequenceOrder,
        coordinates: [lng, lat], // [lng, lat] GeoJSON convention
        photoRequired: photoRequired ?? true,
        geofenceRadius: String(geofenceRadius ?? 15),
        type: type ?? 'photo_point',
        photoInstructions: instructions ?? null,
      })
      .returning()

    const coords = checkpoint.coordinates as [number, number]
    return NextResponse.json({
      data: {
        id: checkpoint.id,
        name: checkpoint.name,
        sequenceOrder: checkpoint.sequenceOrder,
        coordinates: { lat: coords[1], lng: coords[0] },
        photoRequired: checkpoint.photoRequired,
        geofenceRadius: parseFloat(checkpoint.geofenceRadius || '15'),
        type: checkpoint.type,
        instructions: checkpoint.photoInstructions,
      },
    })
  } catch (error) {
    console.error('POST /api/admin/routes/[routeId]/checkpoints error:', error)
    return NextResponse.json({ error: 'Failed to create checkpoint' }, { status: 500 })
  }
}

export async function DELETE(request: NextRequest, context: RouteContext) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const { routeId: routeIdStr } = await context.params
    const routeId = parseInt(routeIdStr, 10)
    const checkpointId = parseInt(request.nextUrl.searchParams.get('id') || '', 10)

    if (isNaN(routeId) || isNaN(checkpointId)) {
      return NextResponse.json({ error: 'Invalid route or checkpoint ID' }, { status: 400 })
    }

    const [deleted] = await db
      .delete(routeCheckpoints)
      .where(and(eq(routeCheckpoints.id, checkpointId), eq(routeCheckpoints.routeId, routeId)))
      .returning({ id: routeCheckpoints.id })

    if (!deleted) {
      return NextResponse.json({ error: 'Checkpoint not found' }, { status: 404 })
    }

    return NextResponse.json({ data: { id: deleted.id, deleted: true }, success: true })
  } catch (error) {
    console.error('DELETE /api/admin/routes/[routeId]/checkpoints error:', error)
    return NextResponse.json({ error: 'Failed to delete checkpoint' }, { status: 500 })
  }
}
