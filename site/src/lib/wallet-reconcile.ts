/**
 * Wallet ledger reconciliation: workers.wallet_balance_cents must equal
 * SUM(worker_wallet_entries.amount_cents) for that worker.
 *
 * The wallet's write path (lib/wallet.ts) is two statements with no
 * transaction, so a crash between them, a hand edit, or a bug can leave the
 * column and the ledger disagreeing. Money must not leave while they disagree:
 * the withdraw route checks the caller first and refuses on mismatch, and
 * GET /api/admin/wallets/reconcile lists every mismatched worker.
 */
import { and, desc, eq, gte, sql } from 'drizzle-orm'
import { db as defaultDb } from '@/db'
import { pendingAdminCredits, workers, workerWalletEntries } from '@/db/schema'
import type { DbExecutor } from '@/lib/job-lifecycle'

export interface WalletMismatch {
  workerId: number
  balanceCents: number
  ledgerCents: number
  /** balance − ledger: positive means the column holds money the ledger can't explain. */
  differenceCents: number
}

/**
 * Workers whose balance column differs from their ledger sum. Pass a
 * workerId to check one worker (the withdraw pre-check); omit it to scan all.
 */
export async function findWalletMismatches(
  opts: { workerId?: number } = {},
  exec: DbExecutor = defaultDb,
): Promise<WalletMismatch[]> {
  const ledgerSum = sql`COALESCE(SUM(${workerWalletEntries.amountCents}), 0)`
  const base = exec
    .select({
      workerId: workers.id,
      balanceCents: workers.walletBalanceCents,
      ledgerCents: sql<string | number>`${ledgerSum}::bigint`,
    })
    .from(workers)
    .leftJoin(workerWalletEntries, eq(workerWalletEntries.workerId, workers.id))

  const rows = await (opts.workerId != null ? base.where(eq(workers.id, opts.workerId)) : base)
    .groupBy(workers.id, workers.walletBalanceCents)
    .having(sql`${ledgerSum} <> ${workers.walletBalanceCents}`)

  return rows.map((r) => {
    const balanceCents = Number(r.balanceCents)
    const ledgerCents = Number(r.ledgerCents)
    return { workerId: r.workerId, balanceCents, ledgerCents, differenceCents: balanceCents - ledgerCents }
  })
}

// ── Admin credits ───────────────────────────────────────────────────────────
//
// Ad-hoc admin credits are the one wallet credit that is not tied to a job, so
// the reconcile report lists them as their own ledger lines (who issued what to
// whom) plus every second-approval request, so an admin can review both.

export interface AdminCreditLine {
  entryId: number
  workerId: number
  amountCents: number
  note: string | null
  createdByAdminId: number | null
  idempotencyKey: string | null
  createdAt: string
}

export interface ListWindow {
  /** ISO timestamp; entries created at or after it. */
  since: string
  limit: number
}

/** Every admin_credit ledger entry in the window, newest first. */
export async function listAdminCredits(window: ListWindow, exec: DbExecutor = defaultDb): Promise<AdminCreditLine[]> {
  const e = workerWalletEntries
  const rows = await exec
    .select({
      entryId: e.id,
      workerId: e.workerId,
      amountCents: e.amountCents,
      note: e.note,
      createdByAdminId: e.createdByAdminId,
      idempotencyKey: e.idempotencyKey,
      createdAt: e.createdAt,
    })
    .from(e)
    .where(and(eq(e.kind, 'admin_credit'), gte(e.createdAt, window.since)))
    .orderBy(desc(e.createdAt))
    .limit(window.limit)
  return rows.map((r) => ({ ...r, amountCents: Number(r.amountCents) }))
}

export type PendingAdminCreditLine = typeof pendingAdminCredits.$inferSelect

/** Every second-approval request in the window, in any state, newest first. */
export async function listPendingAdminCredits(
  window: ListWindow,
  exec: DbExecutor = defaultDb,
): Promise<PendingAdminCreditLine[]> {
  return exec
    .select()
    .from(pendingAdminCredits)
    .where(gte(pendingAdminCredits.createdAt, window.since))
    .orderBy(desc(pendingAdminCredits.createdAt))
    .limit(window.limit)
}

/** True when this worker's wallet column and ledger agree. */
export async function walletIsReconciled(workerId: number, exec: DbExecutor = defaultDb): Promise<boolean> {
  const mismatches = await findWalletMismatches({ workerId }, exec)
  return mismatches.length === 0
}
