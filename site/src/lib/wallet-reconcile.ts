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
import { eq, sql } from 'drizzle-orm'
import { db as defaultDb } from '@/db'
import { workers, workerWalletEntries } from '@/db/schema'
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

/** True when this worker's wallet column and ledger agree. */
export async function walletIsReconciled(workerId: number, exec: DbExecutor = defaultDb): Promise<boolean> {
  const mismatches = await findWalletMismatches({ workerId }, exec)
  return mismatches.length === 0
}
