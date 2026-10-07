/**
 * Admin route assignment API
 * GET /api/admin/routes/:routeId/assign
 * POST /api/admin/routes/:routeId/assign
 * DELETE /api/admin/routes/:routeId/assign?jobId=:jobId
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { blocks, jobs, routes, workers } from '@/db/schema'
import { and, desc, eq, isNotNull } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'
import { toMobileJob } from '@/lib/mobile-job-response'
import { ensureJobForRouteWorker } from '@/lib/ensure-job-for-route-worker'
import { eligibleBlockStatusForRoute } from '@/lib/admin-route-status'
import { isReleasableJobStatus, releaseJobPatch } from '@/lib/release-route-jobs'
import { reopenReleaseSlotSafely } from '@/lib/route-release-slot'
import {
  commitPublicDataMutation,
  type PublicDataInvalidator,
} from '@/lib/public-data/invalidation'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

/** Creating/cancelling claimed jobs changes impact jobs.total/active counts. */
export function commitRouteAssignmentMutation<T>(
  mutation: () => Promise<T>,
  invalidate?: PublicDataInvalidator,
): Promise<T> {
  return commitPublicDataMutation('impact-stats', mutation, invalidate)
}

type RouteContext = { params: Promise<{ routeId: string }> }

function parseRouteId(routeIdStr: string) {
  const routeId = parseInt(routeIdStr, 10)
  return Number.isNaN(routeId) ? null : routeId
}

function toAssignmentResponse(row: {
  job: typeof jobs.$inferSelect
  worker: { id: number | null; name: string | null; email: string | null } | null
  routeId: number
}) {
  const job = toMobileJob(row.job, row.routeId)
  const payoutAmount = job.payoutAmount ?? 0
  return {
    jobId: row.job.id,
    routeId: row.routeId,
    workerId: row.job.workerId,
    workerName: row.worker?.name ?? 'Worker',
    workerEmail: row.worker?.email ?? '',
    status: row.job.status ?? 'available',
    isTest: payoutAmount === 0,
    payoutAmount,
    claimedAt: job.timing.claimedAt,
    startedAt: job.timing.startedAt,
    createdAt: job.createdAt,
    updatedAt: job.updatedAt,
  }
}

export async function GET(request: NextRequest, context: RouteContext) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const { routeId: routeIdStr } = await context.params
    const routeId = parseRouteId(routeIdStr)
    if (routeId == null) {
      return NextResponse.json({ error: 'Invalid route ID', success: false }, { status: 400 })
    }

    const rows = await db
      .select({
        job: jobs,
        worker: {
          id: workers.id,
          name: workers.name,
          email: workers.email,
        },
        routeId: blocks.routeId,
      })
      .from(jobs)
      .innerJoin(blocks, eq(jobs.blockId, blocks.id))
      .leftJoin(workers, eq(jobs.workerId, workers.id))
      .where(and(eq(blocks.routeId, routeId), isNotNull(jobs.workerId)))
      .orderBy(desc(jobs.updatedAt))
      .limit(50)

    return NextResponse.json({
      data: rows.map((row) => toAssignmentResponse({ ...row, routeId: routeId })),
      success: true,
    })
  } catch (error) {
    console.error('GET /api/admin/routes/[routeId]/assign error:', error)
    return NextResponse.json({ error: 'Failed to load route assignments', success: false }, { status: 500 })
  }
}

export async function POST(request: NextRequest, context: RouteContext) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const { routeId: routeIdStr } = await context.params
    const routeId = parseRouteId(routeIdStr)
    if (routeId == null) {
      return NextResponse.json({ error: 'Invalid route ID', success: false }, { status: 400 })
    }

    const body = await request.json().catch(() => null)
    const workerId = Number(body?.workerId)
    const isTest = body?.isTest === true

    if (!Number.isInteger(workerId) || workerId <= 0) {
      return NextResponse.json({ error: 'workerId is required', success: false }, { status: 400 })
    }

    const [route] = await db
      .select()
      .from(routes)
      .where(eq(routes.id, routeId))
      .limit(1)

    if (!route) {
      return NextResponse.json({ error: 'Route not found', success: false }, { status: 404 })
    }

    const routePayout = route.payoutAmount ? Math.round(parseFloat(route.payoutAmount)) : 0
    const payoutAmount = isTest ? 0 : routePayout

    if (!isTest && payoutAmount <= 0) {
      return NextResponse.json(
        { error: 'Paid routes require a positive payout amount', success: false },
        { status: 400 },
      )
    }

    const result = await ensureJobForRouteWorker(routeId, workerId, { isTest, jobStatus: 'claimed' })

    if (!result.created) {
      return NextResponse.json(
        { error: 'Worker already has an active assignment for this route', success: false },
        { status: 409 },
      )
    }

    await commitRouteAssignmentMutation(async () => result.job)

    return NextResponse.json(
      { data: toMobileJob(result.job, routeId), success: true },
      { status: 201 },
    )
  } catch (error) {
    console.error('POST /api/admin/routes/[routeId]/assign error:', error)
    return NextResponse.json({ error: 'Failed to assign route', success: false }, { status: 500 })
  }
}

export async function DELETE(request: NextRequest, context: RouteContext) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const { routeId: routeIdStr } = await context.params
    const routeId = parseRouteId(routeIdStr)
    if (routeId == null) {
      return NextResponse.json({ error: 'Invalid route ID', success: false }, { status: 400 })
    }

    const jobId = parseInt(request.nextUrl.searchParams.get('jobId') ?? '', 10)
    if (Number.isNaN(jobId)) {
      return NextResponse.json({ error: 'jobId is required', success: false }, { status: 400 })
    }

    const [existing] = await db
      .select({ job: jobs, routeId: blocks.routeId })
      .from(jobs)
      .innerJoin(blocks, eq(jobs.blockId, blocks.id))
      .where(and(eq(jobs.id, jobId), eq(blocks.routeId, routeId)))
      .limit(1)

    if (!existing) {
      return NextResponse.json({ error: 'Assignment not found', success: false }, { status: 404 })
    }

    if (!isReleasableJobStatus(existing.job.status)) {
      return NextResponse.json({ error: 'Only claimed or in-progress assignments can be cancelled', success: false }, { status: 409 })
    }

    // The reset lives in lib/release-route-jobs.ts, shared with the
    // unpublish/archive verbs. The block follows the job: linkedBlockStatusUpdate
    // never touches `in_progress` blocks (they belong to a job), so once the job
    // is released the block must be put back or the map keeps showing it worked.
    const now = new Date().toISOString()
    const [route] = await db
      .select({ status: routes.status, claimStatus: routes.claimStatus })
      .from(routes)
      .where(eq(routes.id, routeId))
      .limit(1)
    const blockStatus = route
      ? eligibleBlockStatusForRoute({ status: route.status ?? 'draft', claimStatus: route.claimStatus })
      : 'funded'
    const [updatedRows] = await commitRouteAssignmentMutation(() =>
      db.batch([
        db.update(jobs).set(releaseJobPatch(existing.job, now)).where(eq(jobs.id, jobId)).returning(),
        db
          .update(blocks)
          .set({ status: blockStatus, updatedAt: now })
          .where(and(eq(blocks.id, existing.job.blockId), eq(blocks.status, 'in_progress'))),
      ]),
    )
    const updated = updatedRows[0]

    // A route-queue job goes back on offer: reopen its release slot.
    await reopenReleaseSlotSafely(db, jobId, now)

    return NextResponse.json({
      data: toMobileJob(updated, routeId),
      success: true,
    })
  } catch (error) {
    console.error('DELETE /api/admin/routes/[routeId]/assign error:', error)
    return NextResponse.json({ error: 'Failed to cancel route assignment', success: false }, { status: 500 })
  }
}
