/**
 * Shared handler for the two "close" verbs — unpublish and archive.
 *
 * Both used to run the route/block transition and nothing else, which is how
 * a route got unpublished while its job stayed `in_progress` for six weeks
 * (job 24). They now behave like route-DELETE: refuse with 409 while active
 * jobs exist, and — new — accept `{ releaseActiveJobs: true }` to release
 * those jobs and close the route in ONE transaction (db.batch is
 * transactional on the neon-http driver). Jobs in `pending_review` are never
 * released; the response says so and the caller reviews them first.
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { blocks, jobs, routes } from '@/db/schema'
import { and, eq, inArray } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'
import { eligibleBlockStatusForRoute, type EligibleBlockStatus } from '@/lib/admin-route-status'
import { linkedBlockStatusUpdate } from '@/lib/admin-route-sync'
import { toRouteResponse } from '@/lib/admin-route-response'
import { commitPublicDataChanges } from '@/lib/public-data/invalidation'
import {
  ACTIVE_JOB_STATUSES,
  isReleasableJobStatus,
  parseReleaseFlag,
  releaseJobPatch,
  routeCloseConflict,
  type RouteCloseVerb,
} from '@/lib/release-route-jobs'

export type ActiveRouteJob = {
  id: number
  blockId: number
  workerId: number | null
  status: string | null
  payoutAmount: string | null
}

/** Jobs on this route's blocks that a worker is currently mid-way through. */
export async function activeJobsForRoute(routeId: number): Promise<ActiveRouteJob[]> {
  return db
    .select({
      id: jobs.id,
      blockId: jobs.blockId,
      workerId: jobs.workerId,
      status: jobs.status,
      payoutAmount: jobs.payoutAmount,
    })
    .from(jobs)
    .innerJoin(blocks, eq(jobs.blockId, blocks.id))
    .where(and(eq(blocks.routeId, routeId), inArray(jobs.status, [...ACTIVE_JOB_STATUSES])))
}

/**
 * Batch items that detach the given jobs and put their `in_progress` blocks
 * back to `blockStatus`. `linkedBlockStatusUpdate` deliberately skips
 * `in_progress` blocks because they belong to a worker's job — once that job
 * is released, the block must follow, or the map keeps showing it as worked.
 */
export function releaseJobStatements(released: ActiveRouteJob[], blockStatus: EligibleBlockStatus, now: string) {
  const jobUpdates = released.map((j) => db.update(jobs).set(releaseJobPatch(j, now)).where(eq(jobs.id, j.id)))
  const blockIds = [...new Set(released.map((j) => j.blockId))]
  const blockUpdates =
    blockIds.length > 0
      ? [
          db
            .update(blocks)
            .set({ status: blockStatus, updatedAt: now })
            .where(and(inArray(blocks.id, blockIds), eq(blocks.status, 'in_progress'))),
        ]
      : []
  return [...jobUpdates, ...blockUpdates]
}

type RouteContext = { params: Promise<{ routeId: string }> }

export type RouteCloseTarget = {
  verb: RouteCloseVerb
  status: 'draft' | 'archived'
  /** Extra route columns for this verb (e.g. unpublish clears publishedAt). */
  routePatch?: Record<string, unknown>
}

export function closeRouteHandler(target: RouteCloseTarget) {
  return async function POST(request: NextRequest, context: RouteContext) {
    const auth = await requireAdmin(request)
    if (auth instanceof NextResponse) return auth

    try {
      const { routeId: routeIdStr } = await context.params
      const routeId = parseInt(routeIdStr, 10)
      if (Number.isNaN(routeId)) {
        return NextResponse.json({ error: 'Invalid route ID', success: false }, { status: 400 })
      }

      const body: unknown = await request.json().catch(() => ({}))
      const release = parseReleaseFlag(body)

      const active = await activeJobsForRoute(routeId)
      const releasable = active.filter((j) => isReleasableJobStatus(j.status))
      const heldForReview = active.length - releasable.length

      if (active.length > 0 && (!release || heldForReview > 0)) {
        return NextResponse.json(routeCloseConflict(target.verb, active), { status: 409 })
      }

      const now = new Date().toISOString()
      const blockStatus = eligibleBlockStatusForRoute({ status: target.status, claimStatus: 'closed' })

      const [updatedRoutes] = await db.batch([
        db
          .update(routes)
          .set({ ...target.routePatch, status: target.status, claimStatus: 'closed', updatedAt: now })
          .where(eq(routes.id, routeId))
          .returning(),
        linkedBlockStatusUpdate(routeId, blockStatus, now),
        ...releaseJobStatements(releasable, blockStatus, now),
      ])
      const updated = updatedRoutes[0]
      if (!updated) {
        return NextResponse.json({ error: 'Route not found', success: false }, { status: 404 })
      }

      // Releasing jobs changes impact jobs.total/active, same as assign-DELETE.
      const tags =
        releasable.length > 0
          ? (['cleanup-impact', 'affected-area', 'impact-stats'] as const)
          : (['cleanup-impact', 'affected-area'] as const)
      await commitPublicDataChanges([...tags], async () => updated)

      return NextResponse.json({
        data: toRouteResponse(updated),
        summary: { releasedJobs: releasable.length },
        success: true,
      })
    } catch (error) {
      console.error(`POST /api/admin/routes/[routeId]/${target.verb} error:`, error)
      return NextResponse.json({ error: `Failed to ${target.verb} route`, success: false }, { status: 500 })
    }
  }
}
