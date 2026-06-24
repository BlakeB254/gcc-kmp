/**
 * GET /api/workers/me/route-offers
 * The worker's currently-open route offers (pending, not expired), each with its
 * batch and the routes still available to pick.
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { routeJobOffers } from '@/db/schema'
import { and, desc, eq, gt } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { toRouteOffer } from '@/lib/route-queue'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

export async function GET(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) return NextResponse.json({ error: 'Unauthorized', success: false }, { status: 401 })
  try {
    const now = new Date().toISOString()
    const offers = await db
      .select()
      .from(routeJobOffers)
      .where(and(eq(routeJobOffers.workerId, auth.workerId), eq(routeJobOffers.status, 'pending'), gt(routeJobOffers.expiresAt, now)))
      .orderBy(desc(routeJobOffers.offeredAt))
    const data = await Promise.all(offers.map((o) => toRouteOffer(o, { includeBatch: true, includeRoutes: true })))
    return NextResponse.json({ data, success: true })
  } catch (error) {
    console.error('GET /api/workers/me/route-offers error:', error)
    return NextResponse.json({ error: 'Failed to load route offers', success: false }, { status: 500 })
  }
}
