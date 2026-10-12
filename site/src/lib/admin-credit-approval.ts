/**
 * Second-admin approval for large ad-hoc admin wallet credits.
 *
 * A credit above ADMIN_CREDIT_SECOND_APPROVAL_THRESHOLD_CENTS does not land in
 * the wallet when requested: POST /api/admin/workers/[id]/payout writes a
 * pending_admin_credits row instead, and a DIFFERENT admin approves it with
 * POST /api/admin/wallet-credits/[id]/approve. Modelled on job approval
 * (lib/job-lifecycle.ts claimJobForApproval):
 *
 *  - read-only guards first (pendingCreditGuard) for a precise error,
 *  - then ONE conditional UPDATE … WHERE status = 'pending' AND the approver
 *    is neither the requester nor the target. Zero rows → somebody else
 *    decided it first (or the guard raced) → 409.
 *  - only the winner writes the admin_credit ledger row, under the request's
 *    own idempotency key, so a retried approve can never double-credit.
 *
 * Functions take the db as a parameter so tests can drive them with the
 * recording pg-proxy instance and assert on the SQL that would run.
 */
import { and, eq, ne } from 'drizzle-orm'
import { pendingAdminCredits } from '@/db/schema'
import type { DbExecutor } from '@/lib/job-lifecycle'

export type PendingAdminCreditRow = typeof pendingAdminCredits.$inferSelect
export type PendingAdminCreditStatus = 'pending' | 'approved' | 'rejected'

export interface CreatePendingArgs {
  targetWorkerId: number
  amountCents: number
  reason: string
  idempotencyKey: string
  createdByAdminId: number
  now: string
}

export type CreatePendingResult =
  | { created: true; id: number }
  | { created: false; existing: { id: number; status: string } | null }

/**
 * Record the request. The unique index on idempotency_key is the atomic
 * claim: a retry inserts nothing and gets the existing row's state back.
 */
export async function createPendingAdminCredit(exec: DbExecutor, args: CreatePendingArgs): Promise<CreatePendingResult> {
  const inserted = await exec
    .insert(pendingAdminCredits)
    .values({
      targetWorkerId: args.targetWorkerId,
      amountCents: args.amountCents,
      reason: args.reason,
      idempotencyKey: args.idempotencyKey,
      createdByAdminId: args.createdByAdminId,
      status: 'pending',
      createdAt: args.now,
      updatedAt: args.now,
    })
    .onConflictDoNothing()
    .returning({ id: pendingAdminCredits.id })
  if (inserted.length > 0) return { created: true, id: inserted[0].id }

  const [existing] = await exec
    .select({ id: pendingAdminCredits.id, status: pendingAdminCredits.status })
    .from(pendingAdminCredits)
    .where(eq(pendingAdminCredits.idempotencyKey, args.idempotencyKey))
    .limit(1)
  return { created: false, existing: existing ?? null }
}

/**
 * Read-only guards for approve, checked BEFORE the claim so the caller gets a
 * precise answer. The atomic UPDATE re-checks every one of them.
 */
export function pendingCreditGuard(
  row: Pick<PendingAdminCreditRow, 'status' | 'createdByAdminId' | 'targetWorkerId'>,
  adminWorkerId: number,
): { status: number; error: string } | null {
  if (row.status !== 'pending') {
    return { status: 409, error: `This credit was already ${row.status}.` }
  }
  if (row.createdByAdminId === adminWorkerId) {
    return { status: 403, error: 'You requested this credit — a different admin must approve it.' }
  }
  if (row.targetWorkerId === adminWorkerId) {
    return { status: 403, error: 'You cannot approve a credit to your own wallet.' }
  }
  return null
}

/**
 * Atomic approve claim: flips pending → approved only if the row is still
 * pending and the approver is neither its requester nor its target. Returns
 * the approved row (the caller credits the wallet from it) or null.
 */
export async function claimPendingCreditForApproval(
  exec: DbExecutor,
  pendingId: number,
  adminWorkerId: number,
  now: string,
): Promise<PendingAdminCreditRow | null> {
  const [row] = await exec
    .update(pendingAdminCredits)
    .set({ status: 'approved', decidedByAdminId: adminWorkerId, decidedAt: now, updatedAt: now })
    .where(
      and(
        eq(pendingAdminCredits.id, pendingId),
        eq(pendingAdminCredits.status, 'pending'),
        ne(pendingAdminCredits.createdByAdminId, adminWorkerId),
        ne(pendingAdminCredits.targetWorkerId, adminWorkerId),
      ),
    )
    .returning()
  return row ?? null
}

/** Record which ledger entry an approved request produced. */
export async function linkPendingCreditToEntry(
  exec: DbExecutor,
  pendingId: number,
  walletEntryId: number,
  now: string,
): Promise<void> {
  await exec
    .update(pendingAdminCredits)
    .set({ walletEntryId, updatedAt: now })
    .where(eq(pendingAdminCredits.id, pendingId))
}

/**
 * Withdraw a pending request. Any admin may — the requester included (they
 * noticed a mistake). Only from 'pending'; a decided row is left alone.
 */
export async function rejectPendingCredit(
  exec: DbExecutor,
  pendingId: number,
  adminWorkerId: number,
  now: string,
  note?: string | null,
): Promise<PendingAdminCreditRow | null> {
  const [row] = await exec
    .update(pendingAdminCredits)
    .set({ status: 'rejected', decidedByAdminId: adminWorkerId, decidedAt: now, decisionNote: note ?? null, updatedAt: now })
    .where(and(eq(pendingAdminCredits.id, pendingId), eq(pendingAdminCredits.status, 'pending')))
    .returning()
  return row ?? null
}

/** One pending request by id (the approve/reject routes' read-only guard read). */
export async function getPendingAdminCredit(exec: DbExecutor, pendingId: number): Promise<PendingAdminCreditRow | null> {
  const [row] = await exec.select().from(pendingAdminCredits).where(eq(pendingAdminCredits.id, pendingId)).limit(1)
  return row ?? null
}
