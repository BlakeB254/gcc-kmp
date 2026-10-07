/**
 * DB statements behind the paid-job TTL policy (rules: lib/paid-job-ttl.ts),
 * plus the stale-record expiries the same cron tick runs. Each takes the
 * executor so tests can assert on the SQL (lib/testing/recording-db.ts).
 * No `@/db` import; lib/stale-job-sweep.ts binds the real db.
 *
 * Every write is a conditional UPDATE … RETURNING guarded on the state the
 * sweep read (neon-http has no interactive transactions — see
 * lib/job-lifecycle.ts), so a worker who started, completed or declined the
 * job between the SELECT and the UPDATE is left alone.
 */
import { and, asc, eq, inArray, isNull, lt, sql } from 'drizzle-orm'
import { blocks, communityReports, jobs, routeScans, routes } from '@/db/schema'
import { eligibleBlockStatusForRoute } from '@/lib/admin-route-status'
import { completeJob, type DbExecutor } from '@/lib/job-lifecycle'
import { PAID_CLAIM_RELEASE_HOURS, paidJobTtlCondition, type PaidJobCandidate } from '@/lib/paid-job-ttl'
import { releaseJobPatch } from '@/lib/release-route-jobs'
import { reopenReleaseSlot } from '@/lib/route-release-slot'
import { findQualifyingScanForJob, isUniqueViolation } from '@/lib/scan-completion'
import { ROUTE_SCAN_ABANDON_DAYS } from '@/lib/scan-verification'

/** Bounds one tick; the rest is picked up 15 minutes later. */
const PAID_SWEEP_LIMIT = 100

export async function findPaidJobCandidates(exec: DbExecutor): Promise<PaidJobCandidate[]> {
  return exec
    .select({
      id: jobs.id,
      blockId: jobs.blockId,
      workerId: jobs.workerId,
      status: jobs.status,
      payoutAmount: jobs.payoutAmount,
      payoutStatus: jobs.payoutStatus,
      claimedAt: jobs.claimedAt,
      startedAt: jobs.startedAt,
      startReminderSentAt: jobs.startReminderSentAt,
      progressReminderSentAt: jobs.progressReminderSentAt,
      stalledAt: jobs.stalledAt,
    })
    .from(jobs)
    .where(paidJobTtlCondition())
    .orderBy(asc(jobs.id))
    .limit(PAID_SWEEP_LIMIT)
}

function sameWorker(job: PaidJobCandidate) {
  return job.workerId == null ? isNull(jobs.workerId) : eq(jobs.workerId, job.workerId)
}

/** True when this call stamped the reminder (so it owns the push). */
export async function claimStartReminder(exec: DbExecutor, job: PaidJobCandidate, now: string): Promise<boolean> {
  const rows = await exec
    .update(jobs)
    .set({ startReminderSentAt: now })
    .where(
      and(
        eq(jobs.id, job.id),
        eq(jobs.status, 'claimed'),
        sameWorker(job),
        isNull(jobs.startedAt),
        sql`(${jobs.startReminderSentAt} is null or ${jobs.startReminderSentAt} < ${jobs.claimedAt})`,
      ),
    )
    .returning({ id: jobs.id })
  return rows.length > 0
}

export async function claimProgressReminder(exec: DbExecutor, job: PaidJobCandidate, now: string): Promise<boolean> {
  const rows = await exec
    .update(jobs)
    .set({ progressReminderSentAt: now })
    .where(
      and(
        eq(jobs.id, job.id),
        eq(jobs.status, 'in_progress'),
        sameWorker(job),
        sql`(${jobs.progressReminderSentAt} is null
          or ${jobs.progressReminderSentAt} < coalesce(${jobs.startedAt}, ${jobs.claimedAt}))`,
      ),
    )
    .returning({ id: jobs.id })
  return rows.length > 0
}

export async function flagStalled(exec: DbExecutor, job: PaidJobCandidate, now: string): Promise<boolean> {
  const rows = await exec
    .update(jobs)
    .set({ stalledAt: now, updatedAt: now })
    .where(
      and(
        eq(jobs.id, job.id),
        eq(jobs.status, 'in_progress'),
        sameWorker(job),
        sql`(${jobs.stalledAt} is null or ${jobs.stalledAt} < coalesce(${jobs.startedAt}, ${jobs.claimedAt}))`,
      ),
    )
    .returning({ id: jobs.id })
  return rows.length > 0
}

/**
 * Release a paid claim nobody started: the canonical releaseJobPatch, then
 * the block back to its route's eligible status (the block follows the job,
 * as in lib/stale-job-sweep.ts applyReleases), then the queue slot.
 */
export async function releasePaidClaim(
  exec: DbExecutor,
  job: PaidJobCandidate,
  now: string,
): Promise<{ released: boolean; slotReopened: boolean }> {
  const released = await exec
    .update(jobs)
    .set(releaseJobPatch(job, now))
    .where(
      and(
        eq(jobs.id, job.id),
        eq(jobs.status, 'claimed'),
        sameWorker(job),
        isNull(jobs.startedAt),
        // Same claim we judged stale — not a fresh re-claim.
        lt(jobs.claimedAt, sql`${now}::timestamptz - make_interval(hours => ${PAID_CLAIM_RELEASE_HOURS}::int)`),
      ),
    )
    .returning({ id: jobs.id })
  if (released.length === 0) return { released: false, slotReopened: false }

  const [route] = await exec
    .select({ routeStatus: routes.status, claimStatus: routes.claimStatus })
    .from(blocks)
    .leftJoin(routes, eq(blocks.routeId, routes.id))
    .where(eq(blocks.id, job.blockId))
    .limit(1)
  const blockStatus = route?.routeStatus
    ? eligibleBlockStatusForRoute({ status: route.routeStatus, claimStatus: route.claimStatus })
    : 'funded'
  await exec
    .update(blocks)
    .set({ status: blockStatus, updatedAt: now })
    .where(
      and(
        eq(blocks.id, job.blockId),
        eq(blocks.status, 'in_progress'),
        sql`not exists (select 1 from jobs j where j.block_id = ${blocks.id}
          and j.status in ('claimed', 'in_progress', 'pending_review'))`,
      ),
    )

  const slot = await reopenReleaseSlot(exec, job.id, now)
  return { released: true, slotReopened: slot != null }
}

export async function findQualifyingScanIdForJob(exec: DbExecutor, job: PaidJobCandidate): Promise<number | null> {
  if (job.workerId == null) return null
  const [block] = await exec
    .select({ routeId: blocks.routeId })
    .from(blocks)
    .where(eq(blocks.id, job.blockId))
    .limit(1)
  if (block?.routeId == null) return null
  const scan = await findQualifyingScanForJob(
    exec,
    { id: job.id, workerId: job.workerId, claimedAt: job.claimedAt, startedAt: job.startedAt },
    block.routeId,
  )
  return scan?.id ?? null
}

/** in_progress → pending_review on the worker's behalf, recording the scan. */
export async function moveToReview(exec: DbExecutor, job: PaidJobCandidate, scanId: number, now: string): Promise<boolean> {
  if (job.workerId == null) return false
  try {
    const row = await completeJob(
      exec,
      { id: job.id, payoutAmount: job.payoutAmount == null ? null : String(job.payoutAmount), payoutStatus: job.payoutStatus ?? null },
      { workerId: job.workerId, isAdmin: false },
      now,
      { completionScanId: scanId },
    )
    return row != null
  } catch (error) {
    if (isUniqueViolation(error)) return false
    throw error
  }
}

// ── Stale records ───────────────────────────────────────────────────────────

/** A route scan still `in_progress` this long after it started is abandoned. */
export { ROUTE_SCAN_ABANDON_DAYS }
/** A pending community sighting this old stops showing on the public map. */
export const SIGHTING_MAP_TTL_DAYS = 30

/**
 * `in_progress` scans older than ROUTE_SCAN_ABANDON_DAYS → `abandoned`
 * (status only; phase and data kept). An abandoned scan can never complete
 * (scanCompletionRefusal requires `in_progress`).
 */
export async function expireAbandonedScans(exec: DbExecutor, now: string): Promise<number[]> {
  const rows = await exec
    .update(routeScans)
    .set({ status: 'abandoned', updatedAt: now })
    .where(
      and(
        eq(routeScans.status, 'in_progress'),
        sql`${routeScans.phase} <> 'complete'`,
        lt(routeScans.createdAt, sql`${now}::timestamptz - make_interval(days => ${ROUTE_SCAN_ABANDON_DAYS}::int)`),
      ),
    )
    .returning({ id: routeScans.id })
  return rows.map((r) => r.id)
}

/**
 * Pending sightings older than SIGHTING_MAP_TTL_DAYS get `map_expires_at =
 * now`. The conservative option: every public map read already hides rows
 * whose map_expires_at has passed (api/spots, api/community-reports/map,
 * api/bounties), while status stays `pending` so admin moderation still sees
 * them, nothing is deleted, and clearing the column restores a row. Rows
 * that already carry an expiry are left alone.
 */
export async function expireStaleSightings(exec: DbExecutor, now: string): Promise<number[]> {
  const rows = await exec
    .update(communityReports)
    .set({ mapExpiresAt: now, updatedAt: now })
    .where(
      and(
        eq(communityReports.type, 'sighting'),
        inArray(communityReports.status, ['pending']),
        isNull(communityReports.mapExpiresAt),
        lt(communityReports.createdAt, sql`${now}::timestamptz - make_interval(days => ${SIGHTING_MAP_TTL_DAYS}::int)`),
      ),
    )
    .returning({ id: communityReports.id })
  return rows.map((r) => r.id)
}
