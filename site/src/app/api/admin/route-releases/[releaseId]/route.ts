/**
 * Admin route-release batch detail.
 * GET   /api/admin/route-releases/:releaseId — batch + its routes (position ASC) + offers
 * PATCH /api/admin/route-releases/:releaseId — edit name/releaseAt/offerWindowMinutes/
 *        cleanup window/targetSlots, or { action: 'cancel' } to cancel the batch
 *        (cancels open slots + pending offers in the same transaction).
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { routes, routeJobOffers, routeReleaseBatches, routeReleaseRoutes } from '@/db/schema'
import { and, asc, eq } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'
import { toRouteReleaseBatch } from '@/lib/route-queue'
import { parseOptionalTimestamp } from '@/lib/admin-route-sync'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

type RouteContext = { params: Promise<{ releaseId: string }> }

const iso = (v: unknown): string | null => (v ? new Date(v as string).toISOString() : null)

async function batchRoutesWithPosition(releaseId: number) {
  const rows = await db
    .select({
      slotId: routeReleaseRoutes.id,
      position: routeReleaseRoutes.position,
      slotStatus: routeReleaseRoutes.status,
      jobId: routeReleaseRoutes.jobId,
      acceptedByWorkerId: routeReleaseRoutes.acceptedByWorkerId,
      acceptedOfferId: routeReleaseRoutes.acceptedOfferId,
      acceptedAt: routeReleaseRoutes.acceptedAt,
      routeId: routes.id,
      code: routes.code,
      displayName: routes.displayName,
      routeStatus: routes.status,
      claimStatus: routes.claimStatus,
      payoutAmount: routes.payoutAmount,
    })
    .from(routeReleaseRoutes)
    .innerJoin(routes, eq(routeReleaseRoutes.routeId, routes.id))
    .where(eq(routeReleaseRoutes.batchId, releaseId))
    .orderBy(asc(routeReleaseRoutes.position), asc(routeReleaseRoutes.id))

  return rows.map((row) => ({
    slotId: row.slotId,
    position: row.position,
    status: row.slotStatus,
    jobId: row.jobId,
    acceptedByWorkerId: row.acceptedByWorkerId ?? null,
    acceptedOfferId: row.acceptedOfferId ?? null,
    acceptedAt: iso(row.acceptedAt),
    route: {
      id: row.routeId,
      code: row.code,
      displayName: row.displayName,
      status: row.routeStatus,
      claimStatus: row.claimStatus ?? 'closed',
      payoutAmount: Math.round(parseFloat(row.payoutAmount || '10000')),
    },
  }))
}

async function batchOffers(releaseId: number) {
  const rows = await db
    .select({
      id: routeJobOffers.id,
      workerId: routeJobOffers.workerId,
      waveNumber: routeJobOffers.waveNumber,
      status: routeJobOffers.status,
      offeredAt: routeJobOffers.offeredAt,
      expiresAt: routeJobOffers.expiresAt,
      respondedAt: routeJobOffers.respondedAt,
      selectedRouteId: routeJobOffers.selectedRouteId,
      declineReason: routeJobOffers.declineReason,
      notificationStatus: routeJobOffers.notificationStatus,
    })
    .from(routeJobOffers)
    .where(eq(routeJobOffers.batchId, releaseId))
    .orderBy(asc(routeJobOffers.id))

  return rows.map((row) => ({
    id: row.id,
    workerId: row.workerId,
    waveNumber: row.waveNumber,
    status: row.status,
    offeredAt: iso(row.offeredAt),
    expiresAt: iso(row.expiresAt),
    respondedAt: iso(row.respondedAt),
    selectedRouteId: row.selectedRouteId ?? null,
    declineReason: row.declineReason ?? null,
    notificationStatus: row.notificationStatus,
  }))
}

async function parseReleaseId(context: RouteContext): Promise<number | null> {
  const { releaseId: releaseIdStr } = await context.params
  const releaseId = parseInt(releaseIdStr, 10)
  return Number.isNaN(releaseId) ? null : releaseId
}

export async function GET(request: NextRequest, context: RouteContext) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth
  try {
    const releaseId = await parseReleaseId(context)
    if (releaseId === null) {
      return NextResponse.json({ error: 'Invalid release ID', success: false }, { status: 400 })
    }

    const [batch] = await db.select().from(routeReleaseBatches).where(eq(routeReleaseBatches.id, releaseId)).limit(1)
    if (!batch) {
      return NextResponse.json({ error: 'Release batch not found', success: false }, { status: 404 })
    }

    const [batchRoutes, offers] = await Promise.all([batchRoutesWithPosition(releaseId), batchOffers(releaseId)])

    return NextResponse.json({
      data: { ...toRouteReleaseBatch(batch), routes: batchRoutes, offers },
      success: true,
    })
  } catch (error) {
    console.error('GET /api/admin/route-releases/[releaseId] error:', error)
    return NextResponse.json({ error: 'Failed to fetch release batch', success: false }, { status: 500 })
  }
}

export async function PATCH(request: NextRequest, context: RouteContext) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth
  try {
    const releaseId = await parseReleaseId(context)
    if (releaseId === null) {
      return NextResponse.json({ error: 'Invalid release ID', success: false }, { status: 400 })
    }

    const [batch] = await db.select().from(routeReleaseBatches).where(eq(routeReleaseBatches.id, releaseId)).limit(1)
    if (!batch) {
      return NextResponse.json({ error: 'Release batch not found', success: false }, { status: 404 })
    }

    const body = await request.json().catch(() => null)
    if (typeof body !== 'object' || body === null || Array.isArray(body)) {
      return NextResponse.json({ error: 'Request body must be a JSON object', success: false }, { status: 400 })
    }

    const now = new Date().toISOString()

    if (body.action === 'cancel') {
      if (batch.status === 'cancelled' || batch.status === 'completed') {
        return NextResponse.json({ error: `Batch is already ${batch.status}`, success: false }, { status: 409 })
      }
      // One transaction: batch cancelled, open slots cancelled, pending offers cancelled.
      const [updatedBatches, cancelledSlots, cancelledOffers] = await db.batch([
        db
          .update(routeReleaseBatches)
          .set({ status: 'cancelled', nextWaveAt: null, updatedAt: now })
          .where(eq(routeReleaseBatches.id, releaseId))
          .returning(),
        db
          .update(routeReleaseRoutes)
          .set({ status: 'cancelled', updatedAt: now })
          .where(and(eq(routeReleaseRoutes.batchId, releaseId), eq(routeReleaseRoutes.status, 'open')))
          .returning({ id: routeReleaseRoutes.id }),
        db
          .update(routeJobOffers)
          .set({ status: 'cancelled', respondedAt: now, updatedAt: now })
          .where(and(eq(routeJobOffers.batchId, releaseId), eq(routeJobOffers.status, 'pending')))
          .returning({ id: routeJobOffers.id }),
      ])
      return NextResponse.json({
        data: {
          ...toRouteReleaseBatch(updatedBatches[0]),
          cancelledRoutes: cancelledSlots.length,
          cancelledOffers: cancelledOffers.length,
        },
        success: true,
      })
    }

    // Plain edit of the batch fields.
    const updateData: Record<string, unknown> = { updatedAt: now }
    if (body.name !== undefined) {
      if (typeof body.name !== 'string' || !body.name.trim()) {
        return NextResponse.json({ error: 'name must be a non-empty string', success: false }, { status: 400 })
      }
      updateData.name = body.name.trim()
    }
    if (body.releaseAt !== undefined) {
      const releaseAt = parseOptionalTimestamp(body.releaseAt, 'releaseAt')
      if (releaseAt === null) {
        return NextResponse.json({ error: 'releaseAt must be an ISO-8601 timestamp', success: false }, { status: 400 })
      }
      updateData.releaseAt = releaseAt
    }
    if (body.offerWindowMinutes !== undefined) {
      const minutes = Number(body.offerWindowMinutes)
      if (!Number.isInteger(minutes) || minutes < 15 || minutes > 1440) {
        return NextResponse.json({ error: 'offerWindowMinutes must be between 15 and 1440', success: false }, { status: 400 })
      }
      updateData.offerWindowMinutes = minutes
    }
    if (body.targetSlots !== undefined) {
      const targetSlots = Number(body.targetSlots)
      if (!Number.isInteger(targetSlots) || targetSlots < 0) {
        return NextResponse.json({ error: 'targetSlots must be a non-negative integer', success: false }, { status: 400 })
      }
      updateData.targetSlots = targetSlots
    }
    try {
      if (Object.prototype.hasOwnProperty.call(body, 'cleanupStartsAt')) {
        updateData.cleanupStartsAt = parseOptionalTimestamp(body.cleanupStartsAt, 'cleanupStartsAt')
      }
      if (Object.prototype.hasOwnProperty.call(body, 'cleanupEndsAt')) {
        updateData.cleanupEndsAt = parseOptionalTimestamp(body.cleanupEndsAt, 'cleanupEndsAt')
      }
    } catch (e) {
      return NextResponse.json({ error: (e as Error).message, success: false }, { status: 400 })
    }

    const [updated] = await db
      .update(routeReleaseBatches)
      .set(updateData)
      .where(eq(routeReleaseBatches.id, releaseId))
      .returning()

    return NextResponse.json({ data: toRouteReleaseBatch(updated), success: true })
  } catch (error) {
    console.error('PATCH /api/admin/route-releases/[releaseId] error:', error)
    return NextResponse.json({ error: 'Failed to update release batch', success: false }, { status: 500 })
  }
}
