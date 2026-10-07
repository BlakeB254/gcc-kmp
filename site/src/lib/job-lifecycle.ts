/**
 * Job lifecycle state transitions — the ONE standard money flow:
 *
 *   available ──claim──▶ claimed ──start──▶ in_progress ──complete──▶ pending_review ──approve──▶ completed
 *                                                         (paid job)            (wallet credited here, only here)
 *                                                       └─complete──▶ completed  ($0 test job, never paid)
 *
 * Every transition is a single conditional UPDATE … WHERE status = <expected>
 * … RETURNING. neon-http has no interactive transactions, so a read-then-write
 * check is a TOCTOU race; the WHERE clause is the lock. Zero rows returned
 * means another request won (or the job was never in the expected state) and
 * the route answers 409.
 *
 * Functions take the db as a parameter so tests can drive them with a
 * pg-proxy drizzle instance and assert on the SQL that would run.
 */
import { and, eq, inArray, isNull, ne, notInArray, or, sql } from 'drizzle-orm'
import type { PgAsyncDatabase } from 'drizzle-orm/pg-core'
import { blocks, jobs, routes } from '@/db/schema'

export type DbExecutor = PgAsyncDatabase<any, any, any>

export type JobRow = typeof jobs.$inferSelect

/** jobs.payout_amount is integer cents stored as a numeric string. */
export function payoutCents(payoutAmount: string | number | null | undefined): number {
  if (payoutAmount == null) return 0
  const n = typeof payoutAmount === 'number' ? payoutAmount : parseFloat(payoutAmount)
  return Number.isFinite(n) ? Math.round(n) : 0
}

/**
 * Where a worker's completion lands. A paid job goes to admin review — the
 * approve route is the only thing that credits its earning. A $0 test job has
 * nothing to pay, so it completes outright.
 */
export function completionStatusFor(
  payoutAmount: string | number | null | undefined,
): 'pending_review' | 'completed' {
  return payoutCents(payoutAmount) > 0 ? 'pending_review' : 'completed'
}

/** POST /api/jobs/[id]/claim — only an `available` job can be claimed, by anyone (admins included). */
export async function claimAvailableJob(
  exec: DbExecutor,
  jobId: number,
  workerId: number,
  now: string,
): Promise<JobRow | null> {
  const [row] = await exec
    .update(jobs)
    .set({ workerId, status: 'claimed', claimedAt: now, updatedAt: now })
    .where(and(eq(jobs.id, jobId), eq(jobs.status, 'available')))
    .returning()
  return row ?? null
}

/**
 * PATCH /api/jobs/[id]/start — claimed → in_progress. A worker may only start
 * their own job; an admin may start any claimed job (keeps the assigned worker).
 */
export async function startClaimedJob(
  exec: DbExecutor,
  jobId: number,
  actor: { workerId: number; isAdmin: boolean },
  now: string,
): Promise<JobRow | null> {
  const [row] = await exec
    .update(jobs)
    .set({
      workerId: sql`COALESCE(${jobs.workerId}, ${actor.workerId})`,
      status: 'in_progress',
      startedAt: now,
      updatedAt: now,
    })
    .where(
      and(
        eq(jobs.id, jobId),
        eq(jobs.status, 'claimed'),
        actor.isAdmin ? undefined : eq(jobs.workerId, actor.workerId),
      ),
    )
    .returning()
  return row ?? null
}

/** Statuses an admin may force-complete from (manual recovery). Workers: in_progress only. */
export const ADMIN_COMPLETABLE_STATUSES = ['claimed', 'in_progress'] as const

/**
 * PATCH /api/jobs/[id]/complete — in_progress → pending_review (paid) or
 * completed ($0). The status the row moves to is decided from the row's own
 * payout amount, and the WHERE re-checks the status so two completes (or a
 * complete racing a decline/release) cannot both apply.
 */
export async function completeJob(
  exec: DbExecutor,
  existing: Pick<JobRow, 'id' | 'payoutAmount' | 'payoutStatus'>,
  actor: { workerId: number; isAdmin: boolean },
  now: string,
): Promise<JobRow | null> {
  const nextStatus = completionStatusFor(existing.payoutAmount)
  const [row] = await exec
    .update(jobs)
    .set({
      status: nextStatus,
      completedAt: now,
      // $0 test jobs have nothing to pay — mark payout settled so they never
      // show up as owed. Paid jobs keep whatever they had (normally NULL);
      // approve is what settles them.
      payoutStatus: nextStatus === 'completed' ? 'completed' : existing.payoutStatus,
      updatedAt: now,
    })
    .where(
      and(
        eq(jobs.id, existing.id),
        actor.isAdmin
          ? inArray(jobs.status, [...ADMIN_COMPLETABLE_STATUSES])
          : and(eq(jobs.status, 'in_progress'), eq(jobs.workerId, actor.workerId)),
      ),
    )
    .returning()
  return row ?? null
}

export interface ApprovalCandidate {
  jobStatus: string | null
  reviewStatus: string | null
  workerId: number | null
  payoutAmount: string | null
}

export type GuardFailure = { status: number; error: string }

/** Read-only approve guards (the atomic UPDATE re-checks the money-critical ones). */
export function approvalGuard(row: ApprovalCandidate, adminWorkerId: number): GuardFailure | null {
  if (row.jobStatus !== 'pending_review') {
    return { status: 409, error: `Job is not pending review (status: ${row.jobStatus})` }
  }
  if (row.reviewStatus === 'denied') return { status: 409, error: 'Job was denied' }
  if (!row.workerId) return { status: 409, error: 'Job has no assigned worker' }
  if (row.workerId === adminWorkerId) {
    return { status: 403, error: 'You cannot approve your own job — another admin must review it' }
  }
  if (payoutCents(row.payoutAmount) <= 0) {
    return { status: 409, error: 'Job has no payout amount configured' }
  }
  return null
}

/**
 * Atomic approve claim. Re-checks, in the same statement that flips the row:
 *  - status is still pending_review (double approve → 0 rows → 409)
 *  - payout not already settled/in flight. NULL payoutStatus counts as NOT
 *    paid: `NULL NOT IN (...)` is NULL (falsy) in SQL, which is why the
 *    original guard silently refused every job whose payoutStatus had been
 *    reset to NULL by decline/release.
 *  - no legacy Stripe transfer
 *  - not denied
 *  - not the approving admin's own job
 */
export async function claimJobForApproval(
  exec: DbExecutor,
  jobId: number,
  adminWorkerId: number,
  now: string,
): Promise<{ id: number } | null> {
  const [row] = await exec
    .update(jobs)
    .set({
      status: 'completed',
      reviewStatus: 'approved',
      approvedByAdminId: adminWorkerId,
      reviewedAt: now,
      updatedAt: now,
      payoutStatus: 'processing',
    })
    .where(
      and(
        eq(jobs.id, jobId),
        eq(jobs.status, 'pending_review'),
        or(isNull(jobs.payoutStatus), notInArray(jobs.payoutStatus, ['completed', 'processing'])),
        isNull(jobs.payoutStripeTransferId),
        or(isNull(jobs.reviewStatus), ne(jobs.reviewStatus, 'denied')),
        ne(jobs.workerId, adminWorkerId),
      ),
    )
    .returning({ id: jobs.id })
  return row ?? null
}

/**
 * POST /api/jobs/claim — flip the block available → in_progress atomically.
 * Returns null when another worker got there first (or it was never available).
 */
export async function claimAvailableBlock(
  exec: DbExecutor,
  blockId: number,
  now: string,
): Promise<{ id: number } | null> {
  const [row] = await exec
    .update(blocks)
    .set({ status: 'in_progress', updatedAt: now })
    .where(and(eq(blocks.id, blockId), eq(blocks.status, 'available')))
    .returning({ id: blocks.id })
  return row ?? null
}

/** Undo claimAvailableBlock when the job insert that should follow it fails. */
export async function releaseBlockClaim(exec: DbExecutor, blockId: number, now: string): Promise<void> {
  await exec
    .update(blocks)
    .set({ status: 'available', updatedAt: now })
    .where(and(eq(blocks.id, blockId), eq(blocks.status, 'in_progress')))
}

/**
 * The payout (integer cents) for a job on this block: the linked route's
 * payout_amount — the same source ensureJobForRouteWorker and the route-offer
 * accept flow use. NOT blocks.funding_goal: that column has held dollars on
 * some rows (funding_goal=100 → a $1 job). Null when the block has no route,
 * i.e. no trustworthy payout exists.
 */
export async function routePayoutCentsForBlock(
  exec: DbExecutor,
  block: { routeId: number | null },
): Promise<number | null> {
  if (block.routeId == null) return null
  const [route] = await exec
    .select({ payoutAmount: routes.payoutAmount })
    .from(routes)
    .where(eq(routes.id, block.routeId))
    .limit(1)
  if (!route) return null
  return payoutCents(route.payoutAmount)
}

// ── Evidence ────────────────────────────────────────────────────────────────

/** Evidence can be attached only while the work is happening. */
export const EVIDENCE_ALLOWED_STATUSES = ['claimed', 'in_progress'] as const

export function evidenceAllowedForStatus(status: string | null): boolean {
  return (EVIDENCE_ALLOWED_STATUSES as readonly string[]).includes(status ?? '')
}

const VERCEL_BLOB_PUBLIC_SUFFIX = '.public.blob.vercel-storage.com'

/**
 * The public hostname of OUR Vercel Blob store, derived from the read-write
 * token (`vercel_blob_rw_<storeId>_<secret>` → `<storeid>.public.blob.vercel-storage.com`).
 * Null when the token is absent or not in that shape.
 */
export function ownBlobHost(token: string | undefined = process.env.BLOB_READ_WRITE_TOKEN): string | null {
  if (!token) return null
  const m = /^vercel_blob_rw_([A-Za-z0-9]+)_/.exec(token)
  return m ? `${m[1].toLowerCase()}${VERCEL_BLOB_PUBLIC_SUFFIX}` : null
}

/**
 * Evidence photo URLs must be uploads this worker made through
 * /api/spots/upload: https, on our blob store, under `spots/{workerId}/`.
 * When the store host can't be derived (no token configured) the check falls
 * back to "any Vercel Blob public host" — the path prefix still binds it to
 * the worker.
 */
export function isOwnEvidenceUrl(
  value: string,
  workerId: number,
  storeHost: string | null = ownBlobHost(),
): boolean {
  let url: URL
  try {
    url = new URL(value)
  } catch {
    return false
  }
  if (url.protocol !== 'https:' || url.username || url.password || url.port) return false // ggignore
  const host = url.hostname.toLowerCase()
  const hostOk = storeHost ? host === storeHost : host.endsWith(VERCEL_BLOB_PUBLIC_SUFFIX)
  if (!hostOk) return false
  return url.pathname.startsWith(`/spots/${workerId}/`) && !url.pathname.includes('..')
}
