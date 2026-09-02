/**
 * POST /api/admin/routes/:routeId/publish
 * Publish a route: status -> 'published'. Body may include
 * { claimStatus?, publishAt?, unpublishAt? }; claimStatus defaults to 'open'
 * (legacy publish == activation). Linked blocks sync via the state matrix.
 */
import { NextRequest, NextResponse } from 'next/server'
import { requireAdmin } from '@/lib/admin-auth'
import { eligibleBlockStatusForRoute, parseAdminClaimStatus } from '@/lib/admin-route-status'
import { applyRouteTransition, parseOptionalTimestamp } from '@/lib/admin-route-sync'
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

    const body = await request.json().catch(() => ({}))

    let claimStatus = 'open' as 'closed' | 'open' | 'scheduled'
    // Treat an absent OR explicitly-null claimStatus as "not supplied" and keep
    // the default. A mobile "publish this route" call carries no claim status,
    // and the client serializes that as `"claimStatus": null`; rejecting null
    // here (via `!== undefined`) turned every plain publish into a 400.
    if (body?.claimStatus != null) {
      const parsed = parseAdminClaimStatus(body.claimStatus)
      if (parsed === null) {
        return NextResponse.json({ error: 'claimStatus must be closed, open, or scheduled' }, { status: 400 })
      }
      claimStatus = parsed
    }

    let publishAt: string | null
    let unpublishAt: string | null
    try {
      publishAt = parseOptionalTimestamp(body?.publishAt, 'publishAt')
      unpublishAt = parseOptionalTimestamp(body?.unpublishAt, 'unpublishAt')
    } catch (e) {
      return NextResponse.json({ error: (e as Error).message }, { status: 400 })
    }

    const now = new Date().toISOString()
    const updateData: Record<string, unknown> = {
      status: 'published',
      claimStatus,
      publishedAt: now,
      updatedAt: now,
    }
    if (publishAt !== null) updateData.publishAt = publishAt
    if (unpublishAt !== null) updateData.unpublishAt = unpublishAt

    const updated = await applyRouteTransition(
      routeId,
      updateData,
      eligibleBlockStatusForRoute({ status: 'published', claimStatus }),
      now,
    )
    if (!updated) {
      return NextResponse.json({ error: 'Route not found' }, { status: 404 })
    }

    await commitPublicDataChanges(['cleanup-impact', 'affected-area'], async () => updated)

    return NextResponse.json({ data: toRouteResponse(updated) })
  } catch (error) {
    console.error('POST /api/admin/routes/[routeId]/publish error:', error)
    return NextResponse.json({ error: 'Failed to publish route' }, { status: 500 })
  }
}
