/**
 * Shared helper: find or create an active Job for (route, worker).
 *
 * Used by:
 *  - POST /api/scans  — when an admin creates a scan on behalf of a verified worker
 *  - POST /api/admin/routes/[routeId]/assign — admin direct route assignment
 *
 * Returns { job, created: true } when a new job was inserted,
 * or { job, created: false } when an existing active job was found.
 * Callers that need 409-on-duplicate (e.g. the assign endpoint) should
 * check `created === false` and return 409 themselves.
 */
import { db } from '@/db'
import { blocks, jobs, jobStatusEnum, routes } from '@/db/schema'
import { and, eq, inArray } from 'drizzle-orm'

type JobStatus = (typeof jobStatusEnum.enumValues)[number]

const activeJobStatuses = ['claimed', 'in_progress', 'pending_review'] as const

export async function ensureJobForRouteWorker(
  routeId: number,
  workerId: number,
  opts: {
    isTest?: boolean
    performedByAdminId?: number | null
    jobStatus?: JobStatus
  } = {}
): Promise<{ job: typeof jobs.$inferSelect; created: boolean }> {
  const { isTest = false, performedByAdminId = null, jobStatus = 'in_progress' } = opts

  const [route] = await db.select().from(routes).where(eq(routes.id, routeId)).limit(1)
  if (!route) {
    throw new Error(`Route ${routeId} not found`)
  }

  const routePayout = route.payoutAmount ? Math.round(parseFloat(route.payoutAmount)) : 0
  const payoutAmount = isTest ? 0 : routePayout

  let blockRows = await db.select({ id: blocks.id }).from(blocks).where(eq(blocks.routeId, routeId))

  if (blockRows.length === 0) {
    const [block] = await db
      .insert(blocks)
      .values({
        code: null,
        name: route.displayName,
        serviceAreaId: route.serviceAreaId,
        routeId: route.id,
        status: isTest ? 'available' : 'funded',
        fundingGoal: String(payoutAmount),
        fundingCurrent: isTest ? '0' : String(payoutAmount),
      })
      .returning({ id: blocks.id })
    blockRows = [block]
  }

  // Return existing active job if one exists (find-or-create semantics)
  const [existing] = await db
    .select()
    .from(jobs)
    .where(
      and(
        inArray(jobs.blockId, blockRows.map((b) => b.id)),
        eq(jobs.workerId, workerId),
        inArray(jobs.status, activeJobStatuses),
      )
    )
    .limit(1)

  if (existing) {
    return { job: existing, created: false }
  }

  const now = new Date().toISOString()
  const [job] = await db
    .insert(jobs)
    .values({
      blockId: blockRows[0].id,
      workerId,
      status: jobStatus,
      claimedAt: now,
      payoutAmount: String(payoutAmount),
      payoutStatus: isTest ? null : 'pending',
      performedByAdminId,
      createdAt: now,
      updatedAt: now,
    })
    .returning()

  return { job, created: true }
}
