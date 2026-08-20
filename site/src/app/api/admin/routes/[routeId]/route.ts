/**
 * Admin Route Management API
 * PUT    /api/admin/routes/:routeId - Update route (status, claimStatus, schedule, imageUrl, etc.)
 * DELETE /api/admin/routes/:routeId - Delete a route (refuses while active jobs / pending offers exist)
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { blocks, blockExclusionZones, jobs, routes, routeJobOffers, routeReleaseRoutes } from '@/db/schema'
import { and, eq, gt, inArray } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'
import {
  eligibleBlockStatusForRoute,
  parseAdminClaimStatus,
  parseAdminRouteStatus,
} from '@/lib/admin-route-status'
import { linkedBlockStatusUpdate, parseOptionalTimestamp } from '@/lib/admin-route-sync'
import { toRouteResponse } from '@/lib/admin-route-response'
import { commitPublicDataChanges } from '@/lib/public-data/invalidation'

export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ routeId: string }> }

const ACTIVE_JOB_STATUSES = ['claimed', 'in_progress', 'pending_review'] as const

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
    if (typeof body !== 'object' || body === null || Array.isArray(body)) {
      return NextResponse.json({ error: 'Request body must be a JSON object' }, { status: 400 })
    }

    const hasStatus = Object.prototype.hasOwnProperty.call(body, 'status')
    const nextStatus = hasStatus ? parseAdminRouteStatus(body.status) : null
    if (hasStatus && nextStatus === null) {
      return NextResponse.json({ error: 'status must be draft, published, or archived' }, { status: 400 })
    }

    const hasClaimStatus = Object.prototype.hasOwnProperty.call(body, 'claimStatus')
    const nextClaimStatus = hasClaimStatus ? parseAdminClaimStatus(body.claimStatus) : null
    if (hasClaimStatus && nextClaimStatus === null) {
      return NextResponse.json({ error: 'claimStatus must be closed, open, or scheduled' }, { status: 400 })
    }

    let publishAt: string | null
    let unpublishAt: string | null
    try {
      publishAt = parseOptionalTimestamp(body.publishAt, 'publishAt')
      unpublishAt = parseOptionalTimestamp(body.unpublishAt, 'unpublishAt')
    } catch (e) {
      return NextResponse.json({ error: (e as Error).message }, { status: 400 })
    }

    const [current] = await db.select().from(routes).where(eq(routes.id, routeId)).limit(1)
    if (!current) {
      return NextResponse.json({ error: 'Route not found' }, { status: 404 })
    }

    const now = new Date().toISOString()
    const updateData: Record<string, any> = {
      updatedAt: now,
    }

    if (nextStatus !== null) {
      updateData.status = nextStatus
      updateData.publishedAt = nextStatus === 'published' ? now : null
    }
    if (nextClaimStatus !== null) {
      updateData.claimStatus = nextClaimStatus
    } else if (nextStatus === 'published') {
      // Legacy behavior: publishing via PUT with no explicit claimStatus is
      // activation (block -> available). Use the claim-status verb for the
      // published-but-not-claimable states.
      updateData.claimStatus = 'open'
    }
    if (publishAt !== null) updateData.publishAt = publishAt
    if (unpublishAt !== null) updateData.unpublishAt = unpublishAt
    if (body.imageUrl !== undefined) updateData.imageUrl = body.imageUrl
    if (body.displayName !== undefined) updateData.displayName = body.displayName
    if (body.description !== undefined) updateData.description = body.description
    if (body.imageLocationLat !== undefined) updateData.imageLocationLat = body.imageLocationLat
    if (body.imageLocationLng !== undefined) updateData.imageLocationLng = body.imageLocationLng
    if (body.payoutAmount !== undefined) {
      updateData.payoutAmount = String(body.payoutAmount * 100) // Convert dollars to cents
    }

    const routeStateChanged = nextStatus !== null || updateData.claimStatus !== undefined
    const routeUpdate = db.update(routes).set(updateData).where(eq(routes.id, routeId)).returning()
    let updated: typeof routes.$inferSelect | undefined

    if (routeStateChanged) {
      const finalStatus = nextStatus ?? current.status ?? 'draft'
      const finalClaimStatus =
        (updateData.claimStatus as 'closed' | 'open' | 'scheduled' | undefined) ??
        current.claimStatus ??
        'closed'
      const [updatedRoutes] = await db.batch([
        routeUpdate,
        linkedBlockStatusUpdate(routeId, eligibleBlockStatusForRoute({
          status: finalStatus,
          claimStatus: finalClaimStatus,
        }), now),
      ])
      updated = updatedRoutes[0]
    } else {
      ;[updated] = await routeUpdate
    }

    if (!updated) {
      return NextResponse.json({ error: 'Route not found' }, { status: 404 })
    }

    if (routeStateChanged) {
      await commitPublicDataChanges(
        ['cleanup-impact', 'affected-area'],
        async () => updated,
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

    const [route] = await db.select({ id: routes.id }).from(routes).where(eq(routes.id, routeId)).limit(1)
    if (!route) {
      return NextResponse.json({ error: 'Route not found' }, { status: 404 })
    }

    const routeBlocks = await db
      .select({ id: blocks.id })
      .from(blocks)
      .where(eq(blocks.routeId, routeId))
    // inArray() rejects empty lists; -1 matches nothing (ids are serial).
    const blockIds = routeBlocks.length > 0 ? routeBlocks.map((b) => b.id) : [-1]

    // Refuse while work is in flight: active jobs on the route's blocks, or
    // pending unexpired offers in any release batch containing this route.
    const now = new Date().toISOString()
    const batchIdsSubquery = db
      .select({ batchId: routeReleaseRoutes.batchId })
      .from(routeReleaseRoutes)
      .where(eq(routeReleaseRoutes.routeId, routeId))

    const [activeJobs, pendingOffers] = await Promise.all([
      db
        .select({ id: jobs.id })
        .from(jobs)
        .where(and(inArray(jobs.blockId, blockIds), inArray(jobs.status, [...ACTIVE_JOB_STATUSES]))),
      db
        .select({ id: routeJobOffers.id })
        .from(routeJobOffers)
        .where(
          and(
            inArray(routeJobOffers.batchId, batchIdsSubquery),
            eq(routeJobOffers.status, 'pending'),
            gt(routeJobOffers.expiresAt, now),
          ),
        ),
    ])

    if (activeJobs.length > 0 || pendingOffers.length > 0) {
      return NextResponse.json(
        {
          error: 'Route cannot be deleted while it has active jobs or pending offers',
          conflicts: {
            activeJobs: activeJobs.length,
            pendingOffers: pendingOffers.length,
          },
          success: false,
        },
        { status: 409 },
      )
    }

    // One transaction (db.batch is transactional on neon-http):
    //  1. cancel this route's open release slots
    //  2. cancel any lingering pending offers in its batches (only
    //     already-expired ones can remain after the 409 guard)
    //  3. delete unclaimed 'available' jobs on the route's blocks (release
    //     shells — completed/disputed jobs are worker/payout history; jobs
    //     have no route_id column, their linkage runs through the block)
    //  4. delete exclusion zones, the linked block, and the route itself
    const [cancelledSlots, cancelledOffers, deletedAvailableJobs, , , deletedRoutes] = await db.batch([
      db
        .update(routeReleaseRoutes)
        .set({ status: 'cancelled', updatedAt: now })
        .where(and(eq(routeReleaseRoutes.routeId, routeId), eq(routeReleaseRoutes.status, 'open')))
        .returning({ id: routeReleaseRoutes.id }),
      db
        .update(routeJobOffers)
        .set({ status: 'cancelled', respondedAt: now, updatedAt: now })
        .where(and(inArray(routeJobOffers.batchId, batchIdsSubquery), eq(routeJobOffers.status, 'pending')))
        .returning({ id: routeJobOffers.id }),
      db
        .delete(jobs)
        .where(and(inArray(jobs.blockId, blockIds), eq(jobs.status, 'available')))
        .returning({ id: jobs.id }),
      db.delete(blockExclusionZones).where(inArray(blockExclusionZones.blockId, blockIds)),
      db.delete(blocks).where(eq(blocks.routeId, routeId)),
      db.delete(routes).where(eq(routes.id, routeId)).returning({ id: routes.id }),
    ])
    const deleted = deletedRoutes[0]

    if (!deleted) {
      return NextResponse.json({ error: 'Route not found' }, { status: 404 })
    }

    await commitPublicDataChanges(
      ['cleanup-impact', 'affected-area'],
      async () => deleted,
    )

    return NextResponse.json({
      data: {
        id: deleted.id,
        deleted: true,
        cancelledReleaseRoutes: cancelledSlots.length,
        cancelledOffers: cancelledOffers.length,
        deletedAvailableJobs: deletedAvailableJobs.length,
      },
      success: true,
    })
  } catch (error) {
    console.error('DELETE /api/admin/routes/[routeId] error:', error)
    return NextResponse.json({ error: 'Failed to delete route' }, { status: 500 })
  }
}
