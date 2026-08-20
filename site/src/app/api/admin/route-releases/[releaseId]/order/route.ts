/**
 * PUT /api/admin/route-releases/:releaseId/order
 * Reorder a batch's routes. Body: { routeIds: number[] } — must be exactly the
 * batch's route id set; positions are rewritten 1..N in one transaction.
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { routes, routeReleaseBatches, routeReleaseRoutes } from '@/db/schema'
import { and, asc, eq } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

type RouteContext = { params: Promise<{ releaseId: string }> }

export async function PUT(request: NextRequest, context: RouteContext) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth
  try {
    const { releaseId: releaseIdStr } = await context.params
    const releaseId = parseInt(releaseIdStr, 10)
    if (Number.isNaN(releaseId)) {
      return NextResponse.json({ error: 'Invalid release ID', success: false }, { status: 400 })
    }

    const [batch] = await db
      .select({ id: routeReleaseBatches.id, status: routeReleaseBatches.status })
      .from(routeReleaseBatches)
      .where(eq(routeReleaseBatches.id, releaseId))
      .limit(1)
    if (!batch) {
      return NextResponse.json({ error: 'Release batch not found', success: false }, { status: 404 })
    }

    const body = await request.json().catch(() => null)
    const routeIds = body?.routeIds
    if (!Array.isArray(routeIds) || routeIds.length === 0 || !routeIds.every((id) => Number.isInteger(id) && id > 0)) {
      return NextResponse.json({ error: 'routeIds must be a non-empty array of route ids', success: false }, { status: 400 })
    }

    const slots = await db
      .select({ routeId: routeReleaseRoutes.routeId })
      .from(routeReleaseRoutes)
      .where(eq(routeReleaseRoutes.batchId, releaseId))
    const existingIds = new Set(slots.map((s) => s.routeId))
    const requestedIds = new Set(routeIds as number[])

    if (requestedIds.size !== routeIds.length) {
      return NextResponse.json({ error: 'routeIds contains duplicates', success: false }, { status: 400 })
    }
    if (requestedIds.size !== existingIds.size || [...requestedIds].some((id) => !existingIds.has(id))) {
      return NextResponse.json(
        { error: 'routeIds must be exactly the set of routes in this batch', success: false },
        { status: 409 },
      )
    }

    const now = new Date().toISOString()
    // Rewrite position 1..N in one transaction (db.batch is transactional on neon-http).
    const statements = (routeIds as number[]).map((routeId, index) =>
      db
        .update(routeReleaseRoutes)
        .set({ position: index + 1, updatedAt: now })
        .where(and(eq(routeReleaseRoutes.batchId, releaseId), eq(routeReleaseRoutes.routeId, routeId))),
    )
    await db.batch([statements[0], ...statements.slice(1)])

    const reordered = await db
      .select({
        position: routeReleaseRoutes.position,
        status: routeReleaseRoutes.status,
        routeId: routes.id,
        code: routes.code,
        displayName: routes.displayName,
      })
      .from(routeReleaseRoutes)
      .innerJoin(routes, eq(routeReleaseRoutes.routeId, routes.id))
      .where(eq(routeReleaseRoutes.batchId, releaseId))
      .orderBy(asc(routeReleaseRoutes.position), asc(routeReleaseRoutes.id))

    return NextResponse.json({ data: reordered, success: true })
  } catch (error) {
    console.error('PUT /api/admin/route-releases/[releaseId]/order error:', error)
    return NextResponse.json({ error: 'Failed to reorder release routes', success: false }, { status: 500 })
  }
}
