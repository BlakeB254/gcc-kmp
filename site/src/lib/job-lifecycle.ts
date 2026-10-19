/**
 * Job lifecycle state transitions — the ONE standard money flow:
 *
 *   available ──claim──▶ claimed ──start──▶ in_progress ──complete──▶ pending_review ──approve──▶ completed
 *                                                         (paid job)            (wallet credited here, only here)
 *                                                       └─complete──▶ completed  ($0 test job, never paid)
 *
 *   pending_review ──deny──▶ in_progress  (rework: needs a NEW scan/evidence made after the denial)
 *   claimed | in_progress ──admin release──▶ available (paid) | disputed ($0)
 *
 * The block follows its job: claim flips it available → in_progress; approval
 * of a paid job closes it as `completed` (completedById/At); a $0 test
 * completion or a release puts an `in_progress` block back to its route's
 * eligible status once no other active job holds it.
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
import { SQL, and, eq, getTableColumns, inArray, is, isNull, ne, notInArray, or, sql } from 'drizzle-orm'
import type { PgAsyncDatabase } from 'drizzle-orm/pg-core'
import { blocks, jobs, routeReleaseBatches, routeReleaseRoutes, routes, workers } from '@/db/schema'
import { eligibleBlockStatusForRoute, type EligibleBlockStatus } from '@/lib/admin-route-status'
import { ACTIVE_JOB_STATUSES, isReleasableJobStatus, releaseJobPatch } from '@/lib/release-route-jobs'

export type DbExecutor = PgAsyncDatabase<any, any, any>

export type JobRow = typeof jobs.$inferSelect
export type BlockRow = typeof blocks.$inferSelect

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
  opts: { completionScanId?: number | null } = {},
): Promise<JobRow | null> {
  const nextStatus = completionStatusFor(existing.payoutAmount)
  // A $0 test run pays nothing and cleans no sponsored work, so it never
  // closes the block: a block the claim flipped to in_progress goes back to
  // its route's eligible status in the SAME statement (CTE `reopened`), so a
  // crash can't leave it in_progress with nobody on it.
  const reopenStatus = nextStatus === 'completed' ? await eligibleBlockStatusForJob(exec, existing.id) : null
  const update = exec
    .update(jobs)
    .set({
      status: nextStatus,
      completedAt: now,
      // $0 test jobs have nothing to pay — mark payout settled so they never
      // show up as owed. Paid jobs keep whatever they had (normally NULL);
      // approve is what settles them.
      payoutStatus: nextStatus === 'completed' ? 'completed' : existing.payoutStatus,
      // The verified scan that proved the work (lib/scan-completion.ts). The
      // unique index on this column makes a second job using it fail.
      ...(opts.completionScanId != null ? { completionScanId: opts.completionScanId } : {}),
      // A resubmission after a denial goes back into the review queue
      // (GET /api/admin/jobs/pending-review lists review_status IS NULL).
      // deniedByAdminId / denyReason stay as the record of the last denial.
      ...(nextStatus === 'pending_review' ? { reviewStatus: null } : {}),
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
  if (reopenStatus == null) {
    const [row] = await update.returning()
    return row ?? null
  }
  const done = exec.$with('done', { id: jobs.id, blockId: jobs.blockId }).as(
    update.returning({ id: jobs.id, blockId: jobs.blockId }).getSQL(),
  )
  const reopened = exec.$with('reopened', { id: blocks.id }).as(
    reopenBlockUpdate(exec, sql`(select "block_id" from "done")`, sql`(select "id" from "done")`, reopenStatus, now).getSQL(),
  )
  const [doneRow] = await exec.with(done, reopened).select({ id: done.id }).from(done)
  if (!doneRow) return null
  const [row] = await exec.select().from(jobs).where(eq(jobs.id, Number(doneRow.id))).limit(1)
  return row ?? null
}

/** The route-eligible block status for this job's block (see eligibleBlockStatusForBlock). */
async function eligibleBlockStatusForJob(exec: DbExecutor, jobId: number): Promise<EligibleBlockStatus> {
  const [route] = await exec
    .select({ routeStatus: routes.status, claimStatus: routes.claimStatus })
    .from(jobs)
    .innerJoin(blocks, eq(jobs.blockId, blocks.id))
    .leftJoin(routes, eq(blocks.routeId, routes.id))
    .where(eq(jobs.id, jobId))
    .limit(1)
  return route?.routeStatus
    ? eligibleBlockStatusForRoute({ status: route.routeStatus, claimStatus: route.claimStatus })
    : 'funded'
}

/**
 * UPDATE: the block (`blockId`, a value or a CTE subquery) in_progress → its
 * route's eligible status, once no job other than `jobId` is active on it.
 */
function reopenBlockUpdate(exec: DbExecutor, blockId: number | SQL, jobId: number | SQL, status: EligibleBlockStatus, now: string) {
  return exec
    .update(blocks)
    .set({ status, updatedAt: now })
    .where(
      and(
        typeof blockId === 'number' ? eq(blocks.id, blockId) : inArray(blocks.id, blockId),
        eq(blocks.status, 'in_progress'),
        sql`not ${otherActiveJobOnBlock(jobId)}`,
      ),
    )
    .returning({ id: blocks.id })
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
 *
 * The same statement closes the job's block (CTE `closed`): status
 * `completed`, completedById = the job's worker, completedAt = when the work
 * was submitted. Only a block in APPROVAL_CLOSABLE_BLOCK_STATUSES with no
 * OTHER active job on it can be closed, and the job is approved only if its
 * block can be: the job UPDATE carries the same condition, and if the block
 * UPDATE still matches nothing (it changed under us), the statement aborts —
 * nothing is approved, null is returned, the route answers 409 and credits
 * no wallet.
 */
export async function claimJobForApproval(
  exec: DbExecutor,
  jobId: number,
  adminWorkerId: number,
  now: string,
): Promise<{ id: number } | null> {
  const approvedSql = exec
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
        // Its block can be closed (same rule as the `closed` CTE below).
        sql`exists (select 1 from blocks cb where cb.id = ${jobs.blockId}
          and cb.status in (${sql.join(APPROVAL_CLOSABLE_BLOCK_STATUSES.map((s) => sql`${s}`), sql`, `)})
          and not exists (select 1 from jobs o where o.block_id = cb.id and o.id <> ${jobs.id}
            and o.status in ('claimed', 'in_progress', 'pending_review')))`,
      ),
    )
    .returning({ id: jobs.id, blockId: jobs.blockId, workerId: jobs.workerId, completedAt: jobs.completedAt })
    .getSQL()
  const approved = exec
    .$with('approved', { id: jobs.id, blockId: jobs.blockId, workerId: jobs.workerId, completedAt: jobs.completedAt })
    .as(approvedSql)
  const closed = exec
    .$with('closed', { id: blocks.id })
    .as(
      exec
        .update(blocks)
        .set({
          status: 'completed',
          completedById: sql`(select "worker_id" from "approved")`,
          completedAt: sql`coalesce((select "completed_at" from "approved"), ${now}::timestamptz)`,
          updatedAt: now,
        })
        .where(
          and(
            inArray(blocks.id, sql`(select "block_id" from "approved")`),
            inArray(blocks.status, [...APPROVAL_CLOSABLE_BLOCK_STATUSES]),
            sql`not ${otherActiveJobOnBlock(sql`(select "id" from "approved")`)}`,
          ),
        )
        .returning({ id: blocks.id })
        .getSQL(),
    )
  try {
    const [row] = await exec
      .with(approved, closed)
      .select({
        // If the job was approved but its block was not closed, this cast
        // fails and the whole statement — the approval with it — rolls back.
        id: sql<number>`case when exists (select 1 from "closed") then "approved"."id"
          else (${APPROVAL_ABORT} || "approved"."id")::int end`,
      })
      .from(approved)
    return row ? { id: Number(row.id) } : null
  } catch (error) {
    if (isApprovalAbort(error)) return null
    throw error
  }
}

const APPROVAL_ABORT = 'approval aborted: block not closable, job '

/**
 * The database error raised by the abort cast. Read from the driver error
 * (`cause`), never the "Failed query: … params: …" wrapper — that echoes the
 * statement's params, which always contain APPROVAL_ABORT.
 */
function isApprovalAbort(error: unknown): boolean {
  const e = error as { message?: unknown; cause?: { message?: unknown } } | null
  const driverMessage = e?.cause ? e.cause.message : e?.message
  return (
    typeof driverMessage === 'string' &&
    !driverMessage.startsWith('Failed query') &&
    driverMessage.includes(APPROVAL_ABORT)
  )
}

/**
 * Block statuses an approval may close (see claimJobForApproval): the block
 * is being worked (in_progress — every claim path flips it) or was never
 * flipped (available / funded: admin-assigned or pre-existing jobs). A
 * `completed` block is not: whatever cleaned it was already approved, and a
 * job that re-cleans it must first take it (claim / offer accept flip it).
 */
export const APPROVAL_CLOSABLE_BLOCK_STATUSES = ['in_progress', 'available', 'funded'] as const

/**
 * SQL: a job other than `jobId` is still active on the block being updated.
 * References "blocks"."id", so it belongs inside an UPDATE of blocks.
 */
function otherActiveJobOnBlock(jobId: number | SQL): SQL {
  return sql`exists (select 1 from jobs j where j.block_id = ${blocks.id} and j.id <> ${jobId}
    and j.status in ('claimed', 'in_progress', 'pending_review'))`
}

/** The status a block of this route sits in when nobody is working it ('funded' with no route). */
export async function eligibleBlockStatusForBlock(exec: DbExecutor, blockId: number): Promise<EligibleBlockStatus> {
  const [route] = await exec
    .select({ routeStatus: routes.status, claimStatus: routes.claimStatus })
    .from(blocks)
    .leftJoin(routes, eq(blocks.routeId, routes.id))
    .where(eq(blocks.id, blockId))
    .limit(1)
  return route?.routeStatus
    ? eligibleBlockStatusForRoute({ status: route.routeStatus, claimStatus: route.claimStatus })
    : 'funded'
}

/**
 * Put a block the claim flipped to in_progress back to its route's eligible
 * status, once no other active job holds it. True when the block moved.
 */
export async function reopenBlockAfterJob(
  exec: DbExecutor,
  blockId: number,
  jobId: number,
  now: string,
): Promise<boolean> {
  const status = await eligibleBlockStatusForBlock(exec, blockId)
  const rows = await reopenBlockUpdate(exec, blockId, jobId, status, now)
  return rows.length > 0
}

// ── Deny → rework, and admin release ────────────────────────────────────────

/**
 * POST /api/admin/jobs/[id]/deny — pending_review → in_progress for rework.
 * The worker keeps the job and must re-scan (or, on a routeless block, add new
 * evidence) and resubmit; anything made before the denial no longer counts
 * (workFloor). reviewStatus 'denied' + denyReason tell the worker why.
 * startedAt restarts so the TTL sweep's reminder/stall clocks run from the
 * denial. Same money guards as approval: never a job whose payout is settled
 * or in flight. Null when the job was not (or is no longer) deniable.
 */
export async function denyJobForRework(
  exec: DbExecutor,
  jobId: number,
  adminWorkerId: number,
  reason: string,
  now: string,
): Promise<JobRow | null> {
  const [row] = await exec
    .update(jobs)
    .set({
      status: 'in_progress',
      startedAt: now,
      completedAt: null,
      reviewStatus: 'denied',
      deniedByAdminId: adminWorkerId,
      denyReason: reason,
      reviewedAt: now,
      updatedAt: now,
    })
    .where(
      and(
        eq(jobs.id, jobId),
        eq(jobs.status, 'pending_review'),
        or(isNull(jobs.payoutStatus), notInArray(jobs.payoutStatus, ['completed', 'processing'])),
        isNull(jobs.payoutStripeTransferId),
      ),
    )
    .returning()
  return row ?? null
}

export type ReleaseResult =
  | { ok: true; job: { id: number; status: string | null; blockId: number }; blockReopened: boolean; slotReopened: boolean }
  | { ok: false; status: 404 | 409; message: string }

/**
 * POST /api/admin/jobs/[id]/release — an admin takes a claimed or in-progress
 * job (stalled, denied and not reworked, abandoned) away from its worker and
 * returns it to the pool: the canonical releaseJobPatch (paid → available,
 * $0 → disputed), and in the same statement the block back from in_progress
 * to its route's eligible status when no other active job holds it. Then the
 * route-queue slot, if the job came from a release wave. Also releases a job
 * denied before deny-means-rework (pending_review + 'denied').
 */
export async function releaseJobToPool(exec: DbExecutor, jobId: number, now: string): Promise<ReleaseResult> {
  const [job] = await exec
    .select({
      id: jobs.id,
      blockId: jobs.blockId,
      workerId: jobs.workerId,
      status: jobs.status,
      payoutAmount: jobs.payoutAmount,
      reviewStatus: jobs.reviewStatus,
    })
    .from(jobs)
    .where(eq(jobs.id, jobId))
    .limit(1)
  if (!job) return { ok: false, status: 404, message: 'Job not found' }
  // A job denied before deny-means-rework sits in pending_review with
  // review_status 'denied': nobody will review it again, so it is releasable.
  const legacyDenied = job.status === 'pending_review' && job.reviewStatus === 'denied'
  if (!isReleasableJobStatus(job.status) && !legacyDenied) {
    return {
      ok: false,
      status: 409,
      message:
        job.status === 'pending_review'
          ? 'Job is awaiting review: approve or deny it first'
          : `Only a claimed or in-progress job can be released (status: ${job.status})`,
    }
  }

  const result = await releaseInOneStatement(
    exec,
    job,
    and(
      eq(jobs.status, job.status!),
      job.workerId == null ? isNull(jobs.workerId) : eq(jobs.workerId, job.workerId),
      legacyDenied ? eq(jobs.reviewStatus, 'denied') : undefined,
    ),
    now,
  )
  if (!result) return { ok: false, status: 409, message: 'Job changed while releasing it; reload and try again' }
  return { ok: true, job: { id: result.id, status: result.status, blockId: job.blockId }, ...result.effects }
}

/**
 * PATCH /api/jobs/[id]/decline — the worker (or an admin) hands back a job
 * claimed but not started: same single statement as an admin release
 * (job, block, queue slot), guarded on `claimed` and the worker who holds it.
 */
export async function declineClaimedJob(
  exec: DbExecutor,
  job: { id: number; blockId: number; workerId: number | null; payoutAmount: string | number | null },
  now: string,
): Promise<{ id: number; status: string | null } | null> {
  const result = await releaseInOneStatement(
    exec,
    job,
    and(eq(jobs.status, 'claimed'), job.workerId == null ? isNull(jobs.workerId) : eq(jobs.workerId, job.workerId)),
    now,
  )
  return result ? { id: result.id, status: result.status } : null
}

/**
 * ONE statement that releases a job (releaseJobPatch, guarded by `guard`),
 * puts its block from in_progress back to the route's eligible status when
 * no other active job holds it, and reopens its route-queue slot (and the
 * slot's batch, if it had completed) when the job went back to `available`
 * — the writes reopenReleaseSlot does, as CTEs reading `released` instead of
 * re-reading jobs (a later CTE can't see this statement's job update). All
 * commit together or not at all. Null when the guard matched nothing.
 */
export async function releaseInOneStatement(
  exec: DbExecutor,
  job: { id: number; blockId: number; payoutAmount: string | number | null },
  guard: SQL | undefined,
  now: string,
): Promise<{ id: number; status: string | null; effects: { blockReopened: boolean; slotReopened: boolean } } | null> {
  const blockStatus = await eligibleBlockStatusForBlock(exec, job.blockId)
  const released = exec
    .$with('released', { id: jobs.id, status: jobs.status })
    .as(
      exec
        .update(jobs)
        .set(releaseJobPatch(job, now))
        .where(and(eq(jobs.id, job.id), guard))
        .returning({ id: jobs.id, status: jobs.status })
        .getSQL(),
    )
  const reopened = exec
    .$with('reopened', { id: blocks.id })
    .as(
      exec
        .update(blocks)
        .set({ status: blockStatus, updatedAt: now })
        .where(
          and(
            eq(blocks.id, job.blockId),
            sql`exists (select 1 from "released")`,
            eq(blocks.status, 'in_progress'),
            // The snapshot still shows the released job as active: exclude it.
            sql`not ${otherActiveJobOnBlock(job.id)}`,
          ),
        )
        .returning({ id: blocks.id })
        .getSQL(),
    )
  const slot = exec
    .$with('slot', { id: routeReleaseRoutes.id, batchId: routeReleaseRoutes.batchId })
    .as(
      exec
        .update(routeReleaseRoutes)
        .set({ status: 'open', acceptedByWorkerId: null, acceptedOfferId: null, acceptedAt: null, updatedAt: now })
        .where(
          and(
            eq(routeReleaseRoutes.jobId, job.id),
            eq(routeReleaseRoutes.status, 'claimed'),
            sql`exists (select 1 from "released" where "status" = 'available')`,
          ),
        )
        .returning({ id: routeReleaseRoutes.id, batchId: routeReleaseRoutes.batchId })
        .getSQL(),
    )
  const batch = exec
    .$with('batch', { id: routeReleaseBatches.id })
    .as(
      exec
        .update(routeReleaseBatches)
        .set({ status: 'active', nextWaveAt: now, updatedAt: now })
        .where(
          and(
            inArray(routeReleaseBatches.id, sql`(select "batch_id" from "slot")`),
            eq(routeReleaseBatches.status, 'completed'),
          ),
        )
        .returning({ id: routeReleaseBatches.id })
        .getSQL(),
    )
  const [row] = await exec
    .with(released, reopened, slot, batch)
    .select({
      id: released.id,
      status: released.status,
      reopenedCount: sql<number>`(select count(*) from "reopened")`,
      slotCount: sql<number>`(select count(*) from "slot")`,
    })
    .from(released)
  if (!row) return null
  return {
    id: Number(row.id),
    status: row.status,
    effects: { blockReopened: Number(row.reopenedCount) > 0, slotReopened: Number(row.slotCount) > 0 },
  }
}

// ── Completion evidence floor ───────────────────────────────────────────────

/** When a job was last sent back for rework (null unless its review status is 'denied'). */
export function reworkSince(job: { reviewStatus: string | null; reviewedAt: string | null }): string | null {
  return job.reviewStatus === 'denied' ? job.reviewedAt : null
}

function ms(ts: string | null | undefined): number | null {
  if (!ts) return null
  const t = Date.parse(ts)
  return Number.isNaN(t) ? null : t
}

/**
 * The earliest moment completion evidence may date from: the claim (falling
 * back to the start), or the last denial when that is later.
 */
export function workFloor(job: {
  claimedAt: string | null
  startedAt: string | null
  reviewStatus: string | null
  reviewedAt: string | null
}): string | null {
  const base = job.claimedAt ?? job.startedAt
  const denied = reworkSince(job)
  if (base == null || denied == null) return base ?? denied
  return (ms(denied) ?? 0) > (ms(base) ?? 0) ? denied : base
}

/**
 * The one spelling of an evidence photo URL: lowercase scheme + host, then
 * the pathname — no port, credentials, query or fragment. Every evidence
 * comparison here (before ≠ after, upload freshness, the same-job "already
 * held" check) reads URLs through this, and isOwnEvidenceUrl only accepts URLs
 * already in this form, so `…/a.jpg?x=1`, `…/a.jpg#a` and an upper-case host
 * are the SAME photo as `…/a.jpg`, not a new one. Cross-job reuse is refused
 * by photo fingerprints (sha256, 409) in PR #321. Null when it doesn't parse.
 */
export function canonicalEvidenceUrl(value: string): string | null {
  let url: URL
  try {
    url = new URL(value)
  } catch {
    return null
  }
  return `${url.protocol.toLowerCase()}//${url.hostname.toLowerCase()}${url.pathname}`
}

/** Canonical URLs of an evidence column's entries (`{url}` objects or bare strings). */
function evidenceEntryUrls(raw: unknown): string[] {
  if (!Array.isArray(raw)) return []
  return raw.flatMap((entry) => {
    const url = typeof entry === 'string' ? entry : entry && typeof entry === 'object' ? (entry as { url?: unknown }).url : null
    const canonical = typeof url === 'string' && url.length > 0 ? canonicalEvidenceUrl(url) : null
    return canonical ? [canonical] : []
  })
}

/**
 * Evidence for a paid job on a block with no route (nothing to scan), checked
 * at completion: a before photo and a DIFFERENT after photo from this run of
 * the job (`since` = workFloor: the claim, or the last denial). Every URL is
 * compared in canonical form, so `?query`, `#fragment` and host-case variants
 * of one photo are the same photo.
 *
 * "From this run" is NOT the entry's `capturedAt` (PATCH …/evidence stamps
 * that on every call) and NOT digits in the path (a worker can type any). It
 * is:
 *  - one of this worker's photos on OUR blob store (isOwnEvidenceUrl, which
 *    fails closed when the store isn't configured);
 *  - that EXISTS there, uploaded at/after `since` — `uploadedAtOf` asks the
 *    store (lib/evidence-upload-time.ts: @vercel/blob head().uploadedAt;
 *    null when the object doesn't exist);
 *  - and not a URL this job already held as evidence before `since`.
 * An unknown floor never passes. Reuse of a photo across jobs is refused by
 * photo fingerprints (sha256, 409) in PR #321, not here.
 */
export async function hasEvidencePairSince(
  before: unknown,
  after: unknown,
  since: string | null,
  opts: { workerId: number; uploadedAtOf: (url: string) => Promise<number | null>; storeHost?: string | null },
): Promise<boolean> {
  const floor = ms(since)
  if (floor == null) return false
  const heldBefore = new Set(
    [before, after].flatMap((raw) =>
      Array.isArray(raw)
        ? raw.flatMap((entry) => {
            if (!entry || typeof entry !== 'object') return []
            const { url, capturedAt } = entry as { url?: unknown; capturedAt?: unknown }
            const at = typeof capturedAt === 'string' ? ms(capturedAt) : null
            const canonical = typeof url === 'string' ? canonicalEvidenceUrl(url) : null
            return canonical != null && at != null && at < floor ? [canonical] : []
          })
        : [],
    ),
  )
  const storeHost = opts.storeHost === undefined ? ownBlobHost() : opts.storeHost
  const uploaded = new Map<string, Promise<number | null>>()
  const uploadedAt = (url: string) => {
    if (!uploaded.has(url)) uploaded.set(url, opts.uploadedAtOf(url))
    return uploaded.get(url)!
  }
  const fresh = async (raw: unknown) => {
    const candidates = [...new Set(evidenceEntryUrls(raw))].filter(
      (url) => !heldBefore.has(url) && isOwnEvidenceUrl(url, opts.workerId, storeHost),
    )
    const times = await Promise.all(candidates.map(uploadedAt))
    return candidates.filter((_, i) => times[i] != null && (times[i] as number) >= floor)
  }
  const [befores, afters] = await Promise.all([fresh(before), fresh(after)])
  return befores.some((b) => afters.some((a) => a !== b))
}

/**
 * Manual compensation helper: put a block back to `available` — only while
 * NO active job holds it, so it can never reopen a block someone else is
 * working. The claim paths no longer need it (takeBlockForJob is one
 * statement); kept for admin tooling with that guard.
 */
export async function releaseBlockClaim(exec: DbExecutor, blockId: number, now: string): Promise<void> {
  await exec
    .update(blocks)
    .set({ status: 'available', updatedAt: now })
    .where(
      and(
        eq(blocks.id, blockId),
        eq(blocks.status, 'in_progress'),
        sql`not exists (select 1 from jobs j where j.block_id = ${blocks.id}
          and j.status in ('claimed', 'in_progress', 'pending_review'))`,
      ),
    )
}

// ── Taking a block (one statement for every path that starts work) ──────────

/** The one-active-paid-job-per-block index (migration 0055). */
export const ONE_ACTIVE_PAID_JOB_PER_BLOCK = 'jobs_one_active_paid_job_per_block'
/** The one-active-paid-job-per-worker index (migration 0055). */
export const ONE_ACTIVE_PAID_JOB_PER_WORKER = 'jobs_one_active_paid_job_per_worker'

function uniqueViolationConstraint(error: unknown): string | null | undefined {
  const e = error as { code?: unknown; constraint?: unknown; cause?: { code?: unknown; constraint?: unknown } } | null
  const d = e?.cause ?? e
  if (d?.code !== '23505') return undefined
  return typeof d.constraint === 'string' ? d.constraint : null
}

/** Postgres unique_violation on the per-block index (or an unnamed one). */
export function isBlockTakenViolation(error: unknown): boolean {
  const c = uniqueViolationConstraint(error)
  return c === null || c === ONE_ACTIVE_PAID_JOB_PER_BLOCK
}

/** Postgres unique_violation on the per-worker index. */
export function isWorkerBusyViolation(error: unknown): boolean {
  return uniqueViolationConstraint(error) === ONE_ACTIVE_PAID_JOB_PER_WORKER
}

/**
 * The 409 message when a write that gives a job a worker (or makes it
 * active) hit one of the 0055 indexes; null for any other error. For routes
 * that update a job outside takeBlockForJob: start (an admin start can
 * assign the admin), admin reassign.
 */
export function activePaidJobConflictMessage(error: unknown): string | null {
  const c = uniqueViolationConstraint(error)
  if (c === ONE_ACTIVE_PAID_JOB_PER_WORKER) return 'That worker already has an active paid job'
  if (c === ONE_ACTIVE_PAID_JOB_PER_BLOCK) return 'That block already has an active paid job'
  return null
}

const STATEMENT_ABORT = 'gcc statement aborted: '

/**
 * SQL that fails at run time (cast error) when evaluated, aborting — and so
 * rolling back — the whole statement. neon-http runs every statement as its
 * own transaction, so this is how a multi-table CTE becomes all-or-nothing.
 * `row` must reference a column so Postgres can't constant-fold the cast.
 */
function abortStatement(reason: string, row: SQL): SQL {
  return sql`(${STATEMENT_ABORT + reason + ' '} || ${row})::int`
}

/** The driver error raised by abortStatement (read from `cause`, never the params-echoing wrapper). */
function isStatementAbort(error: unknown, reason: string): boolean {
  const e = error as { message?: unknown; cause?: { message?: unknown } } | null
  const m = e?.cause ? e.cause.message : e?.message
  return typeof m === 'string' && !m.startsWith('Failed query') && m.includes(STATEMENT_ABORT + reason)
}

type BlockStatus = NonNullable<BlockRow['status']>
type ActiveJobStatus = 'claimed' | 'in_progress'

export type TakeBlockInput = {
  blockId: number
  workerId: number
  now: string
  /** Block statuses this path may take the block from. */
  fromStatuses: readonly BlockStatus[]
  /**
   * Also refuse when the worker holds any other active job, $0 test runs
   * included (self-service claims, offer accept). The database enforces one
   * active PAID job per worker and per block on every path (migration 0055);
   * admin assignment skips only this extra app check, so a tester can be
   * given a paid route while running a $0 test.
   */
  requireWorkerFree: boolean
  job:
    | { kind: 'existing'; jobId: number; status: ActiveJobStatus; payoutCents?: number | null }
    | {
        kind: 'new'
        status: ActiveJobStatus
        payoutCents: number
        payoutStatus?: string | null
        performedByAdminId?: number | null
      }
}

export type TakeBlockResult =
  | { ok: true; job: JobRow }
  | { ok: false; reason: 'block_not_takeable' | 'job_taken' | 'worker_busy' | 'block_held' }

/**
 * ONE statement: the block → in_progress (from `fromStatuses`) AND the job →
 * this worker (an existing `available` job, or a new row). Both or neither:
 *  - the job write only runs off the block flip's row (`exists (flipped)`);
 *  - if the block flipped but the job write matched nothing (job taken,
 *    worker busy), abortStatement rolls the flip back;
 *  - a second active paid job on the block, or for the worker, violates
 *    jobs_one_active_paid_job_per_block / _per_worker → 'block_held' /
 *    'worker_busy'. Two concurrent statements can't both see "free" and
 *    both commit: the unique index is the lock.
 * Nothing is ever left to compensate in a later statement.
 */
export async function takeBlockForJob(exec: DbExecutor, input: TakeBlockInput): Promise<TakeBlockResult> {
  const { blockId, workerId, now, job } = input
  const flipped = exec.$with('flipped', { id: blocks.id }).as(
    exec
      .update(blocks)
      .set({ status: 'in_progress', updatedAt: now })
      .where(and(eq(blocks.id, blockId), inArray(blocks.status, [...input.fromStatuses])))
      .returning({ id: blocks.id })
      .getSQL(),
  )
  const workerFree = input.requireWorkerFree
    ? sql`and not exists (select 1 from jobs w where w.worker_id = ${workerId}
        ${job.kind === 'existing' ? sql`and w.id <> ${job.jobId}` : sql``}
        and w.status in ('claimed', 'in_progress', 'pending_review'))`
    : sql``

  let written: SQL
  if (job.kind === 'existing') {
    written = exec
      .update(jobs)
      .set({
        workerId,
        status: job.status,
        claimedAt: now,
        ...(job.status === 'in_progress' ? { startedAt: now } : {}),
        // Completion pays from this column: a released or route-queue row
        // can carry a stale amount, so the current route payout wins.
        ...(job.payoutCents != null ? { payoutAmount: String(job.payoutCents) } : {}),
        updatedAt: now,
      })
      .where(
        and(
          eq(jobs.id, job.jobId),
          eq(jobs.blockId, blockId),
          eq(jobs.status, 'available'),
          sql`exists (select 1 from "flipped") ${workerFree}`,
        ),
      )
      .returning({ id: jobs.id })
      .getSQL()
  } else {
    const ours: Partial<Record<string, SQL>> = {
      blockId: sql`${blockId}::int`,
      workerId: sql`${workerId}::int`,
      status: sql`${job.status}::enum_jobs_status`,
      claimedAt: sql`${now}::timestamptz`,
      payoutAmount: sql`${String(job.payoutCents)}::numeric`,
      payoutStatus: job.payoutStatus == null ? undefined : sql`${job.payoutStatus}::varchar`,
      performedByAdminId: job.performedByAdminId == null ? undefined : sql`${job.performedByAdminId}::int`,
    }
    // drizzle's INSERT … SELECT lists every column in table order, so the
    // SELECT supplies each one: ours, else the column's default, else NULL.
    const columns = Object.entries(getTableColumns(jobs)).map(([key, col]) => {
      const value = ours[key]
      if (value) return value
      if (key === 'id') return sql`nextval(pg_get_serial_sequence('jobs', 'id'))`
      if (is(col.default, SQL)) return col.default
      return col.default === undefined ? sql`null` : sql`${col.default}`
    })
    written = exec
      .insert(jobs)
      .select(sql`select ${sql.join(columns, sql`, `)} from "flipped" where true ${workerFree}`)
      .returning({ id: jobs.id })
      .getSQL()
  }
  const taken = exec.$with('taken', { id: jobs.id }).as(written)

  let row: { flipped: number; jobId: number | null } | undefined
  try {
    ;[row] = await exec
      .with(flipped, taken)
      .select({
        flipped: sql<number>`(select count(*) from "flipped")`.mapWith(Number),
        jobId: sql<number | null>`case
          when exists (select 1 from "taken") then (select "id" from "taken")
          when exists (select 1 from "flipped") then ${abortStatement('take', sql`(select "id" from "flipped")`)}
          else null end`.mapWith((v) => (v == null ? null : Number(v))),
      })
      .from(sql`(select 1) as "one"`)
  } catch (error) {
    if (isWorkerBusyViolation(error)) return { ok: false, reason: 'worker_busy' }
    if (isBlockTakenViolation(error)) return { ok: false, reason: 'block_held' }
    if (isStatementAbort(error, 'take')) {
      // The block flipped but the job write didn't: say why (read-only).
      if (input.requireWorkerFree) {
        const [busy] = await exec
          .select({ id: jobs.id })
          .from(jobs)
          .where(and(eq(jobs.workerId, workerId), inArray(jobs.status, [...ACTIVE_JOB_STATUSES])))
          .limit(1)
        if (busy) return { ok: false, reason: 'worker_busy' }
      }
      return { ok: false, reason: 'job_taken' }
    }
    throw error
  }
  if (!row || row.jobId == null) return { ok: false, reason: 'block_not_takeable' }
  const [created] = await exec.select().from(jobs).where(eq(jobs.id, row.jobId)).limit(1)
  return created ? { ok: true, job: created } : { ok: false, reason: 'job_taken' }
}

/**
 * POST /api/workers/me/route-offers/[offerId]/accept — the offered job →
 * claimed for this worker AND its block → in_progress, one statement
 * (takeBlockForJob). Route-queue blocks sit `funded` (or `completed`, a
 * cleaned route offered again) while the release runs. Same one-active-job
 * app rule as a claim.
 */
export async function acceptOfferedJob(
  exec: DbExecutor,
  input: { jobId: number; blockId: number; workerId: number; now: string },
): Promise<TakeBlockResult> {
  return takeBlockForJob(exec, {
    blockId: input.blockId,
    workerId: input.workerId,
    now: input.now,
    fromStatuses: ['available', 'funded', 'completed'],
    requireWorkerFree: true,
    job: { kind: 'existing', jobId: input.jobId, status: 'claimed' },
  })
}

// ── Claim (one set of rules for every claim path) ───────────────────────────

/** What the worker is claiming: a block (web, POST /api/jobs/claim) or an open job row (app, POST /api/jobs/[id]/claim). */
export type ClaimTarget = { blockId: number } | { jobId: number }

export type ClaimRefusal = { ok: false; status: 403 | 404 | 409; message: string }
export type ClaimResult = { ok: true; job: JobRow; block: BlockRow } | ClaimRefusal

const refuse = (status: ClaimRefusal['status'], message: string): ClaimRefusal => ({ ok: false, status, message })

const ACTIVE_JOB_MESSAGE = 'You already have an active job. Complete it first.'
const BLOCK_TAKEN_MESSAGE = 'This block has already been claimed by another worker'

/** Why a worker can't claim this block right now; null when they can. */
export function blockClaimRefusal(block: Pick<BlockRow, 'status'>): string | null {
  if (block.status === 'available') return null
  if (block.status === 'in_progress') return BLOCK_TAKEN_MESSAGE
  if (block.status === 'funded') {
    return 'This block is funded but not yet available for work. Please wait for admin to release it.'
  }
  return 'This block is not available for claiming'
}

/** The 409 message for a failed take. */
export function takeRefusalMessage(reason: Exclude<TakeBlockResult, { ok: true }>['reason']): string {
  if (reason === 'worker_busy') return ACTIVE_JOB_MESSAGE
  if (reason === 'job_taken') return 'Job was just claimed by someone else'
  return BLOCK_TAKEN_MESSAGE
}

/**
 * The one claim. Both claim routes call this, so the rules are identical:
 *
 *  1. the worker exists and is verified (`approved`)                  404 / 403
 *  2. the worker holds no other active job                                  409
 *  3. (job target) the job exists and is `available`                  404 / 409
 *  4. the block is `available` — published + open route; funded,
 *     in-progress and completed blocks are not claimable                    409
 *  5. the block is linked to a route, the only trustworthy payout source
 *     (routeless blocks — e.g. approved proposals — can't be claimed)       409
 *  6. one statement (takeBlockForJob): block available → in_progress and
 *     the job — the target job, else the block's open unassigned job, else
 *     a new one — at the route payout. 2 and 4 are re-checked inside it, and
 *     the one-active-paid-job-per-block index backs 4 in the database.
 */
export async function claimWork(
  exec: DbExecutor,
  target: ClaimTarget,
  workerId: number,
  now: string,
): Promise<ClaimResult> {
  const [worker] = await exec
    .select({ id: workers.id, verificationStatus: workers.verificationStatus })
    .from(workers)
    .where(eq(workers.id, workerId))
    .limit(1)
  if (!worker) return refuse(404, 'Worker not found')
  if (worker.verificationStatus !== 'approved') return refuse(403, 'Your account is not yet approved for work')

  const [active] = await exec
    .select({ id: jobs.id })
    .from(jobs)
    .where(and(eq(jobs.workerId, workerId), inArray(jobs.status, [...ACTIVE_JOB_STATUSES])))
    .limit(1)
  if (active) return refuse(409, ACTIVE_JOB_MESSAGE)

  let blockId: number
  let jobId: number | null = null
  if ('jobId' in target) {
    const [job] = await exec
      .select({ id: jobs.id, blockId: jobs.blockId, status: jobs.status })
      .from(jobs)
      .where(eq(jobs.id, target.jobId))
      .limit(1)
    if (!job) return refuse(404, 'Job not found')
    if (job.status !== 'available') return refuse(409, 'Job is not available')
    blockId = job.blockId
    jobId = job.id
  } else {
    blockId = target.blockId
  }

  const [block] = await exec.select().from(blocks).where(eq(blocks.id, blockId)).limit(1)
  if (!block) return refuse(404, 'Block not found')
  const notClaimable = blockClaimRefusal(block)
  if (notClaimable) return refuse(409, notClaimable)
  const routePayout = await routePayoutCentsForBlock(exec, block)
  if (routePayout == null) {
    return refuse(409, 'This block is not linked to a route yet, so its payout is not set. Ask an admin.')
  }

  if (jobId == null) {
    // Reuse the block's open job (e.g. one a release put back) rather than
    // leave it orphaned beside a new one.
    const [open] = await exec
      .select({ id: jobs.id })
      .from(jobs)
      .where(and(eq(jobs.blockId, block.id), eq(jobs.status, 'available'), isNull(jobs.workerId)))
      .orderBy(jobs.id)
      .limit(1)
    jobId = open?.id ?? null
  }

  const taken = await takeBlockForJob(exec, {
    blockId: block.id,
    workerId,
    now,
    fromStatuses: ['available'],
    requireWorkerFree: true,
    job:
      jobId != null
        ? { kind: 'existing', jobId, status: 'claimed', payoutCents: routePayout }
        : { kind: 'new', status: 'claimed', payoutCents: routePayout },
  })
  if (!taken.ok) return refuse(409, takeRefusalMessage(taken.reason))
  return { ok: true, job: taken.job, block: { ...block, status: 'in_progress' } }
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
 * Fails closed: when our store host can't be derived (no or malformed
 * BLOB_READ_WRITE_TOKEN) nothing is our evidence — there is no fallback to
 * "any Vercel Blob public host".
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
  // Only the canonical spelling (canonicalEvidenceUrl): no query, no
  // fragment (not even an empty `?` / `#`), lower-case host. A variant of a
  // URL would otherwise count as a different photo.
  if (value !== canonicalEvidenceUrl(value)) return false
  // Fail closed: without OUR store's host (BLOB_READ_WRITE_TOKEN unset or
  // malformed) nothing is our evidence — never "any *.public.blob…" host,
  // which any blob store a worker controls would match.
  if (!storeHost || url.hostname !== storeHost) return false
  // One file directly under the worker's folder, plain characters only (no
  // percent-encoding, which is another way to spell the same object).
  return OWN_EVIDENCE_PATH_RE(workerId).test(url.pathname) && !url.pathname.includes('..')
}

const OWN_EVIDENCE_PATH_RE = (workerId: number) => new RegExp(`^/spots/${workerId}/[A-Za-z0-9._-]+$`)
