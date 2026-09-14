/**
 * One definition of "release a job from its worker".
 *
 * The jobs enum has no `cancelled` state — the only ways out of
 * `in_progress` are completion or nothing. Assign-DELETE and decline each
 * carried their own copy of the reset, and the unpublish/archive verbs had
 * none at all, so a route could be closed while its job stayed `in_progress`
 * indefinitely (job 24, NL-S01, six weeks). Every caller now goes through
 * here: `DELETE …/assign`, and the close verbs via lib/admin-route-close.ts.
 *
 * Standalone module — no `@/db` import — so the `tsx --test` suite loads it
 * without a DATABASE_URL.
 */

export const ACTIVE_JOB_STATUSES = ['claimed', 'in_progress', 'pending_review'] as const
export type ActiveJobStatus = (typeof ACTIVE_JOB_STATUSES)[number]

/**
 * `pending_review` is deliberately excluded: the worker has submitted work
 * and is waiting on a decision. Releasing it would discard that submission.
 */
export const RELEASABLE_JOB_STATUSES = ['claimed', 'in_progress'] as const
export type ReleasableJobStatus = (typeof RELEASABLE_JOB_STATUSES)[number]

export function isReleasableJobStatus(status: string | null | undefined): status is ReleasableJobStatus {
  return (RELEASABLE_JOB_STATUSES as readonly string[]).includes(status ?? '')
}

function toCents(payoutAmount: number | string | null | undefined): number {
  if (payoutAmount == null) return 0
  const n = typeof payoutAmount === 'number' ? payoutAmount : parseFloat(payoutAmount)
  return Number.isFinite(n) ? Math.round(n) : 0
}

/**
 * Where a released job lands. A paid job goes back to `available` for the
 * next worker. A $0 job (admin test run) becomes `disputed` — the tombstone
 * assign-DELETE and decline already used, because re-listing a $0 job as
 * claimable would be wrong and the enum has no better state.
 */
export function releasedJobStatus(payoutAmount: number | string | null | undefined): 'available' | 'disputed' {
  return toCents(payoutAmount) > 0 ? 'available' : 'disputed'
}

export type ReleaseJobPatch = {
  workerId: null
  status: 'available' | 'disputed'
  claimedAt: null
  startedAt: null
  payoutStatus: null
  updatedAt: string
}

/** The column patch that detaches a worker from a job. */
export function releaseJobPatch(
  job: { payoutAmount: number | string | null | undefined },
  now: string,
): ReleaseJobPatch {
  return {
    workerId: null,
    status: releasedJobStatus(job.payoutAmount),
    claimedAt: null,
    startedAt: null,
    payoutStatus: null,
    updatedAt: now,
  }
}

export type RouteCloseVerb = 'unpublish' | 'archive'

const VERB_PAST: Record<RouteCloseVerb, string> = { unpublish: 'unpublished', archive: 'archived' }

/**
 * 409 body for a close verb refused by active jobs. `conflicts` is the same
 * shape route-DELETE returns, which the admin "blocked" dialog and the KMP
 * `RouteDeleteSummary` already parse. `releasable` tells the caller whether
 * a retry with `releaseActiveJobs: true` can succeed.
 */
export function routeCloseConflict(
  verb: RouteCloseVerb,
  activeJobs: ReadonlyArray<{ id: number; status: string | null }>,
) {
  const count = activeJobs.filter((j) => isReleasableJobStatus(j.status)).length
  const heldForReview = activeJobs.length - count
  const n = activeJobs.length
  const held =
    heldForReview > 0
      ? ` ${heldForReview} ${heldForReview === 1 ? 'is' : 'are'} awaiting review and must be reviewed first.`
      : ''
  return {
    error:
      `Route cannot be ${VERB_PAST[verb]} while it has ${n} active job${n === 1 ? '' : 's'}.` +
      ` Release them first, or ${verb} with releaseActiveJobs: true.${held}`,
    conflicts: { activeJobs: n, pendingOffers: 0 },
    releasable: { count, heldForReview },
    success: false as const,
  }
}

/** Only a literal `true` opts in; strings and truthy junk do not. */
export function parseReleaseFlag(body: unknown): boolean {
  return (
    typeof body === 'object' &&
    body !== null &&
    (body as Record<string, unknown>).releaseActiveJobs === true
  )
}
