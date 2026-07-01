/**
 * PATCH /api/workers/me/route-offers/:offerId/decline   body: { reason? }
 * Worker declines an offer; the slot stays open and the worker is flagged for
 * carryover so they get priority on the next wave.
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { routeCommunityQueue, routeJobOffers, routeReleaseBatches } from '@/db/schema'
import { and, eq } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { toRouteOffer } from '@/lib/route-queue'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

export async function PATCH(request: NextRequest, context: { params: Promise<{ offerId: string }> }) {
  const auth = await requireMobileAuth(request)
  if (!auth) return NextResponse.json({ error: 'Unauthorized', success: false }, { status: 401 })
  try {
    const { offerId: offerIdStr } = await context.params
    const offerId = parseInt(offerIdStr, 10)
    if (Number.isNaN(offerId)) {
      return NextResponse.json({ error: 'Invalid offer ID', success: false }, { status: 400 })
    }
    const body = await request.json().catch(() => null)
    const reason: string | null = typeof body?.reason === 'string' ? body.reason : null

    const now = new Date().toISOString()
    const [offer] = await db
      .select()
      .from(routeJobOffers)
      .where(and(eq(routeJobOffers.id, offerId), eq(routeJobOffers.workerId, auth.workerId)))
      .limit(1)
    if (!offer) return NextResponse.json({ error: 'Offer not found', success: false }, { status: 404 })
    if (offer.status !== 'pending') {
      return NextResponse.json({ error: 'Offer is no longer active', success: false }, { status: 409 })
    }

    const [updated] = await db
      .update(routeJobOffers)
      .set({ status: 'declined', respondedAt: now, declineReason: reason, updatedAt: now })
      .where(eq(routeJobOffers.id, offerId))
      .returning()

    const [batch] = await db.select({ serviceAreaId: routeReleaseBatches.serviceAreaId }).from(routeReleaseBatches).where(eq(routeReleaseBatches.id, offer.batchId)).limit(1)
    if (batch) {
      await db
        .update(routeCommunityQueue)
        .set({ lastOfferStatus: 'declined', carryoverReleaseAfter: now, updatedAt: now })
        .where(and(eq(routeCommunityQueue.serviceAreaId, batch.serviceAreaId), eq(routeCommunityQueue.workerId, auth.workerId)))
    }

    return NextResponse.json({ data: await toRouteOffer(updated, { includeBatch: true }), success: true })
  } catch (error) {
    console.error('PATCH /api/workers/me/route-offers/[offerId]/decline error:', error)
    return NextResponse.json({ error: 'Failed to decline offer', success: false }, { status: 500 })
  }
}
