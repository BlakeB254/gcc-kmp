/**
 * POST /api/admin/routes/:routeId/claim-status
 * Set only the claim switch: body { claimStatus: 'open' | 'closed' | 'scheduled' }.
 * Linked blocks sync via the state matrix (block becomes 'available' only for
 * published + open; 'funded' otherwise).
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { routes } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'
import { eligibleBlockStatusForRoute, parseAdminClaimStatus } from '@/lib/admin-route-status'
import { applyRouteTransition } from '@/lib/admin-route-sync'
import { toRouteResponse } from '@/lib/admin-route-response'
import { commitPublicDataChanges } from '@/lib/public-data/invalidation'

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

    const body = await request.json().catch(() => null)
    const claimStatus = parseAdminClaimStatus(body?.claimStatus)
    if (claimStatus === null) {
      return NextResponse.json({ error: 'claimStatus must be closed, open, or scheduled' }, { status: 400 })
    }

    const [route] = await db.select().from(routes).where(eq(routes.id, routeId)).limit(1)
    if (!route) {
      return NextResponse.json({ error: 'Route not found' }, { status: 404 })
    }

    const now = new Date().toISOString()
    const updated = await applyRouteTransition(
      routeId,
      { claimStatus, updatedAt: now },
      eligibleBlockStatusForRoute({ status: route.status ?? 'draft', claimStatus }),
      now,
    )
    if (!updated) {
      return NextResponse.json({ error: 'Route not found' }, { status: 404 })
    }

    if (route.status === 'published') {
      await commitPublicDataChanges(['cleanup-impact', 'affected-area'], async () => updated)
    }

    return NextResponse.json({ data: toRouteResponse(updated) })
  } catch (error) {
    console.error('POST /api/admin/routes/[routeId]/claim-status error:', error)
    return NextResponse.json({ error: 'Failed to update claim status' }, { status: 500 })
  }
}
