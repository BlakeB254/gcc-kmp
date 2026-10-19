/**
 * POST /api/workers/me/route-offers/:offerId/accept   body: { routeId }
 * Worker accepts an offer by picking one still-open route in the batch. Claims
 * that route's job for the worker and closes the slot.
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { jobs, routeCommunityQueue, routeJobOffers, routeReleaseBatches, routeReleaseRoutes } from '@/db/schema'
import { and, eq, gt } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { toMobileJob } from '@/lib/mobile-job-response'
import { acceptOfferedJob, takeRefusalMessage } from '@/lib/job-lifecycle'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

export async function POST(request: NextRequest, context: { params: Promise<{ offerId: string }> }) {
  const auth = await requireMobileAuth(request)
  if (!auth) return NextResponse.json({ error: 'Unauthorized', success: false }, { status: 401 })
  try {
    const { offerId: offerIdStr } = await context.params
    const offerId = parseInt(offerIdStr, 10)
    if (Number.isNaN(offerId)) {
      return NextResponse.json({ error: 'Invalid offer ID', success: false }, { status: 400 })
    }
    const body = await request.json().catch(() => null)
    const routeId = Number(body?.routeId)
    if (!Number.isInteger(routeId) || routeId <= 0) {
      return NextResponse.json({ error: 'routeId is required', success: false }, { status: 400 })
    }

    const now = new Date().toISOString()
    const [offer] = await db
      .select()
      .from(routeJobOffers)
      .where(and(eq(routeJobOffers.id, offerId), eq(routeJobOffers.workerId, auth.workerId)))
      .limit(1)
    if (!offer) return NextResponse.json({ error: 'Offer not found', success: false }, { status: 404 })
    if (offer.status !== 'pending' || new Date(offer.expiresAt).getTime() <= Date.now()) {
      return NextResponse.json({ error: 'Offer is no longer active', success: false }, { status: 409 })
    }

    const [slot] = await db
      .select()
      .from(routeReleaseRoutes)
      .where(
        and(
          eq(routeReleaseRoutes.batchId, offer.batchId),
          eq(routeReleaseRoutes.routeId, routeId),
          eq(routeReleaseRoutes.status, 'open'),
        ),
      )
      .limit(1)
    if (!slot) return NextResponse.json({ error: 'That route is no longer available', success: false }, { status: 409 })

    // Claim the open job (guarded so two workers can't grab the same slot)
    // and flip its block to in_progress in the same statement, so the block
    // shows who holds it and a web claim can't reopen it underneath.
    const [offeredJob] = await db.select({ blockId: jobs.blockId }).from(jobs).where(eq(jobs.id, slot.jobId)).limit(1)
    if (!offeredJob) return NextResponse.json({ error: 'That route is no longer available', success: false }, { status: 409 })
    const taken = await acceptOfferedJob(db, { jobId: slot.jobId, blockId: offeredJob.blockId, workerId: auth.workerId, now })
    if (!taken.ok) {
      const error = taken.reason === 'worker_busy' ? takeRefusalMessage(taken.reason) : 'That route was just taken'
      return NextResponse.json({ error, success: false }, { status: 409 })
    }
    const claimed = taken.job

    await db
      .update(routeReleaseRoutes)
      .set({ status: 'claimed', acceptedByWorkerId: auth.workerId, acceptedOfferId: offerId, acceptedAt: now, updatedAt: now })
      .where(eq(routeReleaseRoutes.id, slot.id))

    await db
      .update(routeJobOffers)
      .set({ status: 'accepted', respondedAt: now, selectedRouteId: routeId, selectedJobId: claimed.id, updatedAt: now })
      .where(eq(routeJobOffers.id, offerId))

    const [batch] = await db.select({ serviceAreaId: routeReleaseBatches.serviceAreaId }).from(routeReleaseBatches).where(eq(routeReleaseBatches.id, offer.batchId)).limit(1)
    if (batch) {
      await db
        .update(routeCommunityQueue)
        .set({ lastOfferStatus: 'accepted', carryoverReleaseAfter: null, carryoverAttempts: 0, updatedAt: now })
        .where(and(eq(routeCommunityQueue.serviceAreaId, batch.serviceAreaId), eq(routeCommunityQueue.workerId, auth.workerId)))
    }

    return NextResponse.json({ data: toMobileJob(claimed, routeId), success: true }, { status: 201 })
  } catch (error) {
    console.error('POST /api/workers/me/route-offers/[offerId]/accept error:', error)
    return NextResponse.json({ error: 'Failed to accept offer', success: false }, { status: 500 })
  }
}
