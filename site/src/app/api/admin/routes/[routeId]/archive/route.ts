/**
 * POST /api/admin/routes/:routeId/archive
 * Archive a route: status -> 'archived', claim_status -> 'closed'; linked
 * blocks revert to 'funded' via the state matrix.
 */
import { NextRequest, NextResponse } from 'next/server'
import { requireAdmin } from '@/lib/admin-auth'
import { eligibleBlockStatusForRoute } from '@/lib/admin-route-status'
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

    const now = new Date().toISOString()
    const updated = await applyRouteTransition(
      routeId,
      { status: 'archived', claimStatus: 'closed', updatedAt: now },
      eligibleBlockStatusForRoute({ status: 'archived', claimStatus: 'closed' }),
      now,
    )
    if (!updated) {
      return NextResponse.json({ error: 'Route not found' }, { status: 404 })
    }

    await commitPublicDataChanges(['cleanup-impact', 'affected-area'], async () => updated)

    return NextResponse.json({ data: toRouteResponse(updated) })
  } catch (error) {
    console.error('POST /api/admin/routes/[routeId]/archive error:', error)
    return NextResponse.json({ error: 'Failed to archive route' }, { status: 500 })
  }
}
