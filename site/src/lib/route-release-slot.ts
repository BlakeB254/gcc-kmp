/**
 * Give a released route-queue job its slot back.
 *
 * Accepting an offer flips the job to `claimed` AND its route_release_routes
 * slot to `claimed` (workers/me/route-offers/[offerId]/accept). Nothing ever
 * flipped the slot back (audit): a job released by the stale sweep, a worker
 * decline or an admin unassign went back to `available` while its slot stayed
 * `claimed`, so the queue never offered that route again.
 *
 * Call AFTER the job UPDATE that released it. The slot reopens only when the
 * job really is `available` again — a $0 job's release tombstones it as
 * `disputed`, and an `open` slot pointing at it would only produce "That
 * route was just taken" on accept. A batch that had completed because every
 * slot was taken goes back to `active` with a wave due now, so the next
 * route-releases tick offers the slot; a cancelled batch stays cancelled.
 *
 * No `@/db` import; takes the executor so tests can record the SQL.
 */
import { and, eq, sql } from 'drizzle-orm'
import { routeReleaseBatches, routeReleaseRoutes } from '@/db/schema'
import type { DbExecutor } from '@/lib/job-lifecycle'

export type ReopenedSlot = { slotId: number; batchId: number }

export async function reopenReleaseSlot(exec: DbExecutor, jobId: number, now: string): Promise<ReopenedSlot | null> {
  const [slot] = await exec
    .update(routeReleaseRoutes)
    .set({
      status: 'open',
      acceptedByWorkerId: null,
      acceptedOfferId: null,
      acceptedAt: null,
      updatedAt: now,
    })
    .where(
      and(
        eq(routeReleaseRoutes.jobId, jobId),
        eq(routeReleaseRoutes.status, 'claimed'),
        sql`exists (select 1 from jobs j where j.id = ${jobId} and j.status = 'available')`,
      ),
    )
    .returning({ slotId: routeReleaseRoutes.id, batchId: routeReleaseRoutes.batchId })
  if (!slot) return null

  await exec
    .update(routeReleaseBatches)
    .set({ status: 'active', nextWaveAt: now, updatedAt: now })
    .where(and(eq(routeReleaseBatches.id, slot.batchId), eq(routeReleaseBatches.status, 'completed')))

  return slot
}

/** Best-effort wrapper for request paths where the release itself already succeeded. */
export async function reopenReleaseSlotSafely(exec: DbExecutor, jobId: number, now: string): Promise<ReopenedSlot | null> {
  try {
    return await reopenReleaseSlot(exec, jobId, now)
  } catch (error) {
    console.error(`[route-release-slot] reopening the slot for job ${jobId} failed:`, error)
    return null
  }
}
