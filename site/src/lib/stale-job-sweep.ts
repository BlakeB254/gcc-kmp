/**
 * DB side of the stale $0 test-job sweep (rules in lib/stale-test-jobs.ts).
 * Called from `/api/cron/route-releases` inside its `withCronLease`.
 */
import { db } from '@/db'
import { blocks, jobs, routes } from '@/db/schema'
import { and, eq, inArray, sql } from 'drizzle-orm'
import { eligibleBlockStatusForRoute } from '@/lib/admin-route-status'
import {
  staleJobCondition,
  sweepStaleTestJobs,
  type StaleJobRelease,
  type StaleJobSweepResult,
} from '@/lib/stale-test-jobs'

/** Bounds one tick; anything left over is picked up 15 minutes later. */
const SWEEP_LIMIT = 100

async function findCandidates() {
  return db
    .select({
      id: jobs.id,
      blockId: jobs.blockId,
      workerId: jobs.workerId,
      status: jobs.status,
      payoutAmount: jobs.payoutAmount,
      claimedAt: jobs.claimedAt,
      startedAt: jobs.startedAt,
    })
    .from(jobs)
    .where(staleJobCondition())
    .orderBy(jobs.id)
    .limit(SWEEP_LIMIT)
}

/**
 * Release each job with the canonical `releaseJobPatch`, and put its block
 * back the way `DELETE …/assign` does — the block follows the job, or the
 * map keeps showing it as being worked. The UPDATE is guarded on the status
 * and worker we read, so a job the worker advanced meanwhile is left alone.
 */
async function applyReleases(releases: StaleJobRelease[]): Promise<number[]> {
  const blockIds = [...new Set(releases.map((r) => r.job.blockId))]
  const blockRoutes = await db
    .select({ blockId: blocks.id, routeStatus: routes.status, claimStatus: routes.claimStatus })
    .from(blocks)
    .leftJoin(routes, eq(blocks.routeId, routes.id))
    .where(inArray(blocks.id, blockIds))
  const routeByBlock = new Map(blockRoutes.map((b) => [b.blockId, b]))

  const updatedIds: number[] = []
  for (const { job, patch } of releases) {
    const route = routeByBlock.get(job.blockId)
    const blockStatus = route?.routeStatus
      ? eligibleBlockStatusForRoute({ status: route.routeStatus, claimStatus: route.claimStatus })
      : 'funded'
    const workerGuard = job.workerId == null ? undefined : eq(jobs.workerId, job.workerId)
    const [updatedRows] = await db.batch([
      db
        .update(jobs)
        .set(patch)
        .where(and(eq(jobs.id, job.id), eq(jobs.status, job.status as 'claimed' | 'in_progress'), workerGuard))
        .returning({ id: jobs.id }),
      db
        .update(blocks)
        .set({ status: blockStatus, updatedAt: patch.updatedAt })
        .where(
          and(
            eq(blocks.id, job.blockId),
            eq(blocks.status, 'in_progress'),
            // Runs after the job UPDATE in the same transaction: if that UPDATE
            // was skipped (worker advanced the job) or another job holds the
            // block, an active job still exists and the block is left alone.
            sql`not exists (select 1 from jobs j where j.block_id = ${blocks.id}
              and j.status in ('claimed', 'in_progress', 'pending_review'))`,
          ),
        ),
    ])
    if (updatedRows.length > 0) updatedIds.push(job.id)
  }
  return updatedIds
}

export function sweepStaleTestJobsNow(): Promise<StaleJobSweepResult> {
  return sweepStaleTestJobs({ findCandidates, applyReleases }, Date.now())
}
