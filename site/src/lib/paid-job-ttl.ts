/**
 * TTL policy for PAID jobs, run by the `/api/cron/route-releases` sweep
 * (lib/stale-job-sweep.ts) under the same lease as the $0 test-job sweep.
 * The $0 rules (lib/stale-test-jobs.ts) are unchanged.
 *
 *   claimed, not started   4h  → push the worker once (per claim)
 *                          24h → release (releaseJobPatch) + reopen the
 *                                route-queue slot
 *   in_progress           12h  → push the worker once (per run)
 *                          24h → a qualifying verified scan exists → move to
 *                                pending_review + notify admins;
 *                                none → flag `stalled_at` + notify admins.
 *                                NEVER released: the worker may be mid-clean,
 *                                and only an admin may take paid work away.
 *
 * "Once" is per claim/run, not per job: a stamp counts only when it is at or
 * after the claim (start reminder) or the start (progress reminder, stall
 * flag). A job released and claimed by someone else reminds the new worker
 * without anything having to reset the stamps.
 *
 * Pure module — no `@/db` import. The DB side is injected (`PaidJobSweepDeps`).
 */
import { sql, type SQL } from 'drizzle-orm'
import { jobs } from '@/db/schema'

export const PAID_CLAIM_REMINDER_HOURS = 4
export const PAID_CLAIM_RELEASE_HOURS = 24
export const PAID_IN_PROGRESS_REMINDER_HOURS = 12
export const PAID_IN_PROGRESS_REVIEW_HOURS = 24

const HOUR_MS = 3_600_000

export type PaidJobCandidate = {
  id: number
  blockId: number
  workerId: number | null
  status: string | null
  payoutAmount: number | string | null
  /** Carried through unchanged when the sweep moves the job to review. */
  payoutStatus?: string | null
  claimedAt: string | null
  startedAt: string | null
  startReminderSentAt: string | null
  progressReminderSentAt: string | null
  stalledAt: string | null
  /** A denied job back in rework only auto-submits on a scan made after the denial. */
  reviewStatus?: string | null
  reviewedAt?: string | null
}

export type PaidJobAction =
  | { kind: 'remind-start'; job: PaidJobCandidate }
  | { kind: 'release'; job: PaidJobCandidate }
  | { kind: 'remind-progress'; job: PaidJobCandidate }
  | { kind: 'review-or-flag'; job: PaidJobCandidate; alreadyFlagged: boolean }

function ms(ts: string | null | undefined): number | null {
  if (!ts) return null
  const t = Date.parse(ts)
  return Number.isNaN(t) ? null : t
}

function cents(payoutAmount: number | string | null | undefined): number {
  if (payoutAmount == null) return 0
  const n = typeof payoutAmount === 'number' ? payoutAmount : parseFloat(payoutAmount)
  return Number.isFinite(n) ? Math.round(n) : 0
}

/** A stamp counts for this claim/run only when it is at or after `since`. */
function stampedSince(stamp: string | null, since: number): boolean {
  const t = ms(stamp)
  return t != null && t >= since
}

/** What the sweep should do with one paid job at `nowMs`, or null for nothing. */
export function paidJobAction(job: PaidJobCandidate, nowMs: number): PaidJobAction | null {
  if (cents(job.payoutAmount) <= 0 || job.workerId == null) return null

  if (job.status === 'claimed' && !job.startedAt) {
    const claimed = ms(job.claimedAt)
    if (claimed == null) return null
    const age = nowMs - claimed
    if (age >= PAID_CLAIM_RELEASE_HOURS * HOUR_MS) return { kind: 'release', job }
    if (age >= PAID_CLAIM_REMINDER_HOURS * HOUR_MS && !stampedSince(job.startReminderSentAt, claimed)) {
      return { kind: 'remind-start', job }
    }
    return null
  }

  if (job.status === 'in_progress') {
    const started = ms(job.startedAt) ?? ms(job.claimedAt)
    if (started == null) return null
    const age = nowMs - started
    if (age >= PAID_IN_PROGRESS_REVIEW_HOURS * HOUR_MS) {
      return { kind: 'review-or-flag', job, alreadyFlagged: stampedSince(job.stalledAt, started) }
    }
    if (age >= PAID_IN_PROGRESS_REMINDER_HOURS * HOUR_MS && !stampedSince(job.progressReminderSentAt, started)) {
      return { kind: 'remind-progress', job }
    }
    return null
  }

  return null
}

/**
 * SQL pre-filter for candidates (the earliest threshold of each branch);
 * `paidJobAction` makes the real decision against the same clock.
 */
export function paidJobTtlCondition(): SQL {
  return sql`(
    coalesce(${jobs.payoutAmount}, 0) > 0 and ${jobs.workerId} is not null and (
      (${jobs.status} = 'claimed' and ${jobs.startedAt} is null
        and ${jobs.claimedAt} < now() - make_interval(hours => ${PAID_CLAIM_REMINDER_HOURS}::int))
      or
      (${jobs.status} = 'in_progress'
        and coalesce(${jobs.startedAt}, ${jobs.claimedAt}) < now() - make_interval(hours => ${PAID_IN_PROGRESS_REMINDER_HOURS}::int))
    )
  )`
}

export type PushPayload = { title: string; body: string; url?: string; tag?: string; data?: Record<string, unknown> }

export type PaidJobSweepDeps = {
  findCandidates: () => Promise<PaidJobCandidate[]>
  /** Stamp start_reminder_sent_at iff not yet stamped for this claim; true = this tick owns the push. */
  claimStartReminder: (job: PaidJobCandidate, now: string) => Promise<boolean>
  claimProgressReminder: (job: PaidJobCandidate, now: string) => Promise<boolean>
  /** Release the claimed job; reports whether it was released and whether a queue slot reopened. */
  releaseClaim: (job: PaidJobCandidate, now: string) => Promise<{ released: boolean; slotReopened: boolean }>
  findQualifyingScanId: (job: PaidJobCandidate) => Promise<number | null>
  /** in_progress → pending_review with the scan recorded; false if the job moved meanwhile. */
  moveToReview: (job: PaidJobCandidate, scanId: number, now: string) => Promise<boolean>
  /** Stamp stalled_at iff not yet stamped for this run; true = newly flagged. */
  flagStalled: (job: PaidJobCandidate, now: string) => Promise<boolean>
  pushWorker: (workerId: number, payload: PushPayload) => Promise<unknown>
  notifyAdmins: (payload: PushPayload) => Promise<unknown>
  templates: {
    startReminder: (jobId: number, hoursLeft: number) => PushPayload
    progressReminder: (jobId: number) => PushPayload
    autoReview: (jobId: number) => PushPayload
    stalled: (jobId: number, workerLabel: string) => PushPayload
  }
}

export type PaidJobSweepResult = {
  examined: number
  startReminders: number[]
  released: number[]
  slotsReopened: number[]
  progressReminders: number[]
  movedToReview: number[]
  flagged: number[]
  failed: Array<{ id: number; action: PaidJobAction['kind']; error: string }>
}

export async function sweepPaidJobs(deps: PaidJobSweepDeps, nowMs: number): Promise<PaidJobSweepResult> {
  const now = new Date(nowMs).toISOString()
  const candidates = await deps.findCandidates()
  const result: PaidJobSweepResult = {
    examined: candidates.length,
    startReminders: [],
    released: [],
    slotsReopened: [],
    progressReminders: [],
    movedToReview: [],
    flagged: [],
    failed: [],
  }

  for (const candidate of candidates) {
    const action = paidJobAction(candidate, nowMs)
    if (!action) continue
    const { job } = action
    const workerId = job.workerId as number
    try {
      switch (action.kind) {
        case 'remind-start': {
          if (await deps.claimStartReminder(job, now)) {
            const claimed = ms(job.claimedAt) as number
            const hoursLeft = Math.max(1, Math.ceil((claimed + PAID_CLAIM_RELEASE_HOURS * HOUR_MS - nowMs) / HOUR_MS))
            await deps.pushWorker(workerId, deps.templates.startReminder(job.id, hoursLeft))
            result.startReminders.push(job.id)
          }
          break
        }
        case 'release': {
          const { released, slotReopened } = await deps.releaseClaim(job, now)
          if (released) result.released.push(job.id)
          if (slotReopened) result.slotsReopened.push(job.id)
          break
        }
        case 'remind-progress': {
          if (await deps.claimProgressReminder(job, now)) {
            await deps.pushWorker(workerId, deps.templates.progressReminder(job.id))
            result.progressReminders.push(job.id)
          }
          break
        }
        case 'review-or-flag': {
          const scanId = await deps.findQualifyingScanId(job)
          if (scanId != null) {
            if (await deps.moveToReview(job, scanId, now)) {
              await deps.notifyAdmins(deps.templates.autoReview(job.id))
              result.movedToReview.push(job.id)
            }
          } else if (!action.alreadyFlagged && (await deps.flagStalled(job, now))) {
            await deps.notifyAdmins(deps.templates.stalled(job.id, `worker #${workerId}`))
            result.flagged.push(job.id)
          }
          break
        }
      }
    } catch (error) {
      result.failed.push({ id: job.id, action: action.kind, error: error instanceof Error ? error.message : String(error) })
    }
  }
  return result
}
