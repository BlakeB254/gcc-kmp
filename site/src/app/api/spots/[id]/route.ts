/**
 * Spot Report Management API
 * PATCH  /api/spots/:id - Update spot status (resolve, verify, reject)
 * DELETE /api/spots/:id - Delete a spot report (admin only)
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { communityReports } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { verifyMobileToken } from '@/lib/mobile-auth'

export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ id: string }> }

async function requireAuth(request: NextRequest) {
  const authHeader = request.headers.get('authorization')
  if (!authHeader?.startsWith('Bearer ')) return null
  return verifyMobileToken(authHeader.substring(7))
}

export async function PATCH(request: NextRequest, context: RouteContext) {
  try {
    const auth = await requireAuth(request)
    if (!auth) {
      return NextResponse.json({ error: 'Authorization required' }, { status: 401 })
    }

    const { id } = await context.params
    const spotId = parseInt(id, 10)
    if (isNaN(spotId)) {
      return NextResponse.json({ error: 'Invalid spot ID' }, { status: 400 })
    }

    const body = await request.json()
    const { status, addMediaUrls } = body

    // Only admins can modify spots
    if (auth.role !== 'admin') {
      return NextResponse.json({ error: 'Admin access required' }, { status: 403 })
    }

    const updateData: Record<string, any> = {
      updatedAt: new Date().toISOString(),
    }

    // Handle status change
    if (status) {
      const validStatuses = ['pending', 'verified', 'resolved', 'acknowledged', 'rejected']
      if (!validStatuses.includes(status)) {
        return NextResponse.json(
          { error: `Invalid status. Must be one of: ${validStatuses.join(', ')}` },
          { status: 400 }
        )
      }
      updateData.status = status
      updateData.reviewedById = auth.workerId
      updateData.reviewedAt = new Date().toISOString()
      if (status === 'resolved' || status === 'rejected') {
        updateData.showOnMap = false
      } else {
        // Ensure spots with active statuses are visible on the map
        // (fixes spots that were previously resolved/rejected and re-activated)
        updateData.showOnMap = true
      }
    }

    // Handle adding media URLs to existing photos
    if (addMediaUrls && Array.isArray(addMediaUrls) && addMediaUrls.length > 0) {
      // Fetch current media first
      const [current] = await db
        .select({ mediaIds: communityReports.mediaIds })
        .from(communityReports)
        .where(eq(communityReports.id, spotId))
      const existing = (current?.mediaIds as string[]) || []
      updateData.mediaIds = [...existing, ...addMediaUrls]
    }

    if (!status && !addMediaUrls) {
      return NextResponse.json({ error: 'Nothing to update' }, { status: 400 })
    }

    const [updated] = await db
      .update(communityReports)
      .set(updateData)
      .where(eq(communityReports.id, spotId))
      .returning()

    if (!updated) {
      return NextResponse.json({ error: 'Spot report not found' }, { status: 404 })
    }

    return NextResponse.json({ data: { id: updated.id, status: updated.status } })
  } catch (error) {
    console.error('PATCH /api/spots/[id] error:', error)
    return NextResponse.json({ error: 'Failed to update spot' }, { status: 500 })
  }
}

export async function DELETE(request: NextRequest, context: RouteContext) {
  try {
    const auth = await requireAuth(request)
    if (!auth) {
      return NextResponse.json({ error: 'Authorization required' }, { status: 401 })
    }

    // Only admins can delete spots
    if (auth.role !== 'admin') {
      return NextResponse.json({ error: 'Admin access required' }, { status: 403 })
    }

    const { id } = await context.params
    const spotId = parseInt(id, 10)
    if (isNaN(spotId)) {
      return NextResponse.json({ error: 'Invalid spot ID' }, { status: 400 })
    }

    // Soft-delete: archive the spot (set status to 'rejected') for historical record
    const [archived] = await db
      .update(communityReports)
      .set({ status: 'rejected' })
      .where(eq(communityReports.id, spotId))
      .returning({ id: communityReports.id, status: communityReports.status })

    if (!archived) {
      return NextResponse.json({ error: 'Spot report not found' }, { status: 404 })
    }

    return NextResponse.json({ data: { id: archived.id, archived: true } })
  } catch (error) {
    console.error('DELETE /api/spots/[id] error:', error)
    return NextResponse.json({ error: 'Failed to delete spot' }, { status: 500 })
  }
}
