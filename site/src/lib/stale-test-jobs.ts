/**
 * Expiry rules for abandoned $0 test jobs — one definition shared by the
 * `/api/cron/route-releases` sweep (which releases them) and
 * `GET /api/workers/me/jobs` (which stops returning them before the sweep
 * runs). The Kotlin client mirrors the same TTLs in
 * `shared/.../model/Job.kt` (`TEST_ASSIGNMENT_START_WINDOW_MINUTES`,
 * `TEST_ASSIGNMENT_IN_PROGRESS_TTL_HOURS`) — change both together.
 *
 * Nothing expired jobs before this: production job 24 ($0, `in_progress`
 * since 2026-07-30) stayed on its worker's dashboard map, guidance line and
 * all, for over two months.
 *
 * Paid jobs are NEVER expired here; they follow their own, longer policy in
 * lib/paid-job-ttl.ts (remind, then release only claims that were never
 * started; in-progress paid work is reviewed or flagged, never released).
 * This sweep still counts paid rows past the $0 TTLs in `paidStale`.
 *
 * No `@/db` import — the `tsx --test` suite loads this without a DATABASE_URL.
 */
import { sql, type SQL } from 'drizzle-orm'
import { jobs } from '@/db/schema'
import { releaseJobPatch, type ReleaseJobPatch } from '@/lib/release-route-jobs'

/** A claimed $0 job must be started within this window (matches the app's start countdown). */
export const TEST_JOB_CLAIM_TTL_MINUTES = 60
/** A started $0 job is abandoned once it has been in progress this long. */
export const TEST_JOB_IN_PROGRESS_TTL_HOURS = 24

const CLAIM_TTL_MS = TEST_JOB_CLAIM_TTL_MINUTES * 60_000
const IN_PROGRESS_TTL_MS = TEST_JOB_IN_PROGRESS_TTL_HOURS * 3_600_000

export type StaleJobCandidate = {
  id: number
  blockId: number
  workerId: number | null
  status: string | null
  payoutAmount: number | string | null
  claimedAt: string | null
  startedAt: string | null
}

export type StaleReason = 'claim-not-started' | 'in-progress-abandoned'

function cents(payoutAmount: number | string | null | undefined): number {
  if (payoutAmount == null) return 0
  const n = typeof payoutAmount === 'number' ? payoutAmount : parseFloat(payoutAmount)
  return Number.isFinite(n) ? Math.round(n) : 0
}

/** Mirrors `coalesce(payout_amount, 0) = 0`. */
export function isZeroPayout(payoutAmount: number | string | null | undefined): boolean {
  return cents(payoutAmount) === 0
}

function ms(ts: string | null | undefined): number | null {
  if (!ts) return null
  const t = Date.parse(ts)
  return Number.isNaN(t) ? null : t
}

/**
 * Why a job is past its TTL, regardless of payout — or null if it is not.
 * `in_progress` ages from `started_at`, falling back to `claimed_at` so a
 * row with a missing start stamp cannot dodge expiry forever.
 */
export function staleReason(
  job: Pick<StaleJobCandidate, 'status' | 'claimedAt' | 'startedAt'>,
  nowMs: number,
): StaleReason | null {
  if (job.status === 'claimed' && !job.startedAt) {
    const claimed = ms(job.claimedAt)
    return claimed != null && nowMs - claimed > CLAIM_TTL_MS ? 'claim-not-started' : null
  }
  if (job.status === 'in_progress') {
    const started = ms(job.startedAt) ?? ms(job.claimedAt)
    return started != null && nowMs - started > IN_PROGRESS_TTL_MS ? 'in-progress-abandoned' : null
  }
  return null
}

/** A $0 job past its TTL — the only kind the sweep releases and me/jobs hides. */
export function isExpiredTestJob(job: StaleJobCandidate, nowMs: number): boolean {
  return isZeroPayout(job.payoutAmount) && staleReason(job, nowMs) != null
}

/**
 * SQL twin of `staleReason(...) != null` against the live clock. Payout is
 * left to the caller so the sweep can also find (and count) stale paid jobs.
 */
export function staleJobCondition(): SQL {
  return sql`(
    (${jobs.status} = 'claimed' and ${jobs.startedAt} is null
      and ${jobs.claimedAt} < now() - make_interval(mins => ${TEST_JOB_CLAIM_TTL_MINUTES}::int))
    or
    (${jobs.status} = 'in_progress'
      and coalesce(${jobs.startedAt}, ${jobs.claimedAt}) < now() - make_interval(hours => ${TEST_JOB_IN_PROGRESS_TTL_HOURS}::int))
  )`
}

/** SQL twin of `isExpiredTestJob`. */
export function expiredTestJobCondition(): SQL {
  return sql`(coalesce(${jobs.payoutAmount}, 0) = 0 and ${staleJobCondition()})`
}

/** What me/jobs keeps: everything except expired $0 jobs. */
export function notExpiredTestJobCondition(): SQL {
  // A NULL timestamp makes the comparison unknown; `not unknown` is still unknown and
  // would hide the row. Unknown means not expired.
  return sql`not coalesce(${expiredTestJobCondition()}, false)`
}

export type StaleJobRelease = {
  job: StaleJobCandidate
  reason: StaleReason
  patch: ReleaseJobPatch
}

export type StaleJobSweepPlan = {
  release: StaleJobRelease[]
  /** Stale but paid — left alone pending an owner decision, reported only. */
  paidStale: Array<{ id: number; workerId: number | null; status: string | null; reason: StaleReason }>
}

/**
 * Split stale candidates into $0 jobs to release (each with the canonical
 * `releaseJobPatch`) and paid jobs to leave alone. Rows that are not
 * actually stale at `nowMs` are dropped, so a race with a worker starting
 * the job between the SELECT and here cannot release it.
 */
export function planStaleJobSweep(candidates: StaleJobCandidate[], nowMs: number): StaleJobSweepPlan {
  const now = new Date(nowMs).toISOString()
  const plan: StaleJobSweepPlan = { release: [], paidStale: [] }
  for (const job of candidates) {
    const reason = staleReason(job, nowMs)
    if (!reason) continue
    if (isZeroPayout(job.payoutAmount)) {
      plan.release.push({ job, reason, patch: releaseJobPatch(job, now) })
    } else {
      plan.paidStale.push({ id: job.id, workerId: job.workerId, status: job.status, reason })
    }
  }
  return plan
}

export type StaleJobSweepDeps = {
  /** Jobs matching `staleJobCondition()` — $0 and paid. */
  findCandidates: () => Promise<StaleJobCandidate[]>
  /** Apply the planned releases; returns the ids actually updated. */
  applyReleases: (releases: StaleJobRelease[]) => Promise<number[]>
}

export type StaleJobSweepResult = {
  examined: number
  released: Array<{ id: number; workerId: number | null; reason: StaleReason; status: string }>
  paidStale: StaleJobSweepPlan['paidStale']
}

export async function sweepStaleTestJobs(deps: StaleJobSweepDeps, nowMs: number): Promise<StaleJobSweepResult> {
  const candidates = await deps.findCandidates()
  const plan = planStaleJobSweep(candidates, nowMs)
  const updated = new Set(plan.release.length > 0 ? await deps.applyReleases(plan.release) : [])
  return {
    examined: candidates.length,
    released: plan.release
      .filter((r) => updated.has(r.job.id))
      .map((r) => ({ id: r.job.id, workerId: r.job.workerId, reason: r.reason, status: r.patch.status })),
    paidStale: plan.paidStale,
  }
}
