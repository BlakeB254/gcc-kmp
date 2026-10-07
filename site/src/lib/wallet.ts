/**
 * Worker wallet: credit/debit primitives over workers.walletBalanceCents +
 * the workerWalletEntries audit ledger.
 *
 * Concurrency model (neon-http has NO interactive transactions — every
 * statement is its own transaction):
 *  - The balance column is authoritative. Debits are a single conditional
 *    UPDATE (`WHERE wallet_balance_cents >= amount`), so concurrent cash-outs
 *    cannot overdraw.
 *  - Credits claim an idempotency guard first (unique index on the ledger),
 *    then bump the column. A crash between the two statements leaves a ledger
 *    entry without a balance bump — detectable by comparing SUM(ledger) to the
 *    column, and safe in the sense that it never over-credits.
 *
 * Money semantics: crediting a wallet does NOT move money in Stripe. Funds
 * stay in the platform Stripe balance; the ledger entry is the internal
 * earmark of that liability. Money leaves Stripe only when the worker cashes
 * out (transfer + payout to their Connect account, or a mailed check).
 */
import { eq, sql, and } from 'drizzle-orm'
import { db } from '@/db'
import { workers, workerWalletEntries } from '@/db/schema'
import type { DbExecutor } from '@/lib/job-lifecycle'

export type WalletEntryKind =
  | 'job_earning'
  | 'admin_credit'
  | 'withdrawal'
  | 'withdrawal_reversal'
  | 'adjustment'

export interface CreditArgs {
  workerId: number
  amountCents: number // positive
  kind: WalletEntryKind
  jobId?: number
  payoutId?: number
  note?: string | null
  createdByAdminId?: number
  idempotencyKey?: string
}

export interface CreditResult {
  /** False when the idempotency/uniqueness guard says this credit already happened. */
  credited: boolean
  entryId: number | null
  balanceCents: number
}

/**
 * Credit a worker's wallet. The ledger INSERT is the atomic claim: when a
 * unique guard applies (idempotencyKey, or kind='job_earning' per job, or
 * kind='withdrawal_reversal' per payout) a duplicate call inserts nothing and
 * the balance is not bumped twice.
 */
export async function creditWallet(args: CreditArgs, exec: DbExecutor = db): Promise<CreditResult> {
  if (!Number.isInteger(args.amountCents) || args.amountCents <= 0) {
    throw new Error(`creditWallet: amountCents must be a positive integer, got ${args.amountCents}`)
  }

  const inserted = await exec
    .insert(workerWalletEntries)
    .values({
      workerId: args.workerId,
      amountCents: args.amountCents,
      kind: args.kind,
      jobId: args.jobId ?? null,
      payoutId: args.payoutId ?? null,
      note: args.note ?? null,
      createdByAdminId: args.createdByAdminId ?? null,
      idempotencyKey: args.idempotencyKey ?? null,
    })
    .onConflictDoNothing()
    .returning({ id: workerWalletEntries.id })

  if (inserted.length === 0) {
    const balanceCents = await getWalletBalance(args.workerId, exec)
    return { credited: false, entryId: null, balanceCents }
  }

  const [updated] = await exec
    .update(workers)
    .set({
      walletBalanceCents: sql`${workers.walletBalanceCents} + ${args.amountCents}`,
      updatedAt: new Date().toISOString(),
    })
    .where(eq(workers.id, args.workerId))
    .returning({ balanceCents: workers.walletBalanceCents })

  return {
    credited: true,
    entryId: inserted[0].id,
    balanceCents: updated?.balanceCents ?? (await getWalletBalance(args.workerId, exec)),
  }
}

export interface DebitArgs {
  workerId: number
  amountCents: number // positive; stored negated in the ledger
  kind: WalletEntryKind
  jobId?: number
  payoutId?: number
  note?: string | null
  createdByAdminId?: number
  /**
   * When true (job reassignment corrections), the debit is applied
   * unconditionally and the balance may go negative — the worker may have
   * already cashed out money that turned out not to be theirs.
   */
  allowNegative?: boolean
}

export interface DebitResult {
  /** False only when allowNegative=false and the balance was insufficient. */
  debited: boolean
  balanceCents: number
}

/**
 * Debit a worker's wallet. The conditional UPDATE on the balance column is
 * the atomic claim; the ledger entry is written after it succeeds.
 */
export async function debitWallet(args: DebitArgs): Promise<DebitResult> {
  if (!Number.isInteger(args.amountCents) || args.amountCents <= 0) {
    throw new Error(`debitWallet: amountCents must be a positive integer, got ${args.amountCents}`)
  }

  const updated = await db
    .update(workers)
    .set({
      walletBalanceCents: sql`${workers.walletBalanceCents} - ${args.amountCents}`,
      updatedAt: new Date().toISOString(),
    })
    .where(
      args.allowNegative
        ? eq(workers.id, args.workerId)
        : and(
            eq(workers.id, args.workerId),
            sql`${workers.walletBalanceCents} >= ${args.amountCents}`
          )
    )
    .returning({ balanceCents: workers.walletBalanceCents })

  if (updated.length === 0) {
    const balanceCents = await getWalletBalance(args.workerId)
    return { debited: false, balanceCents }
  }

  await db.insert(workerWalletEntries).values({
    workerId: args.workerId,
    amountCents: -args.amountCents,
    kind: args.kind,
    jobId: args.jobId ?? null,
    payoutId: args.payoutId ?? null,
    note: args.note ?? null,
    createdByAdminId: args.createdByAdminId ?? null,
  })

  return { debited: true, balanceCents: updated[0].balanceCents }
}

export async function getWalletBalance(workerId: number, exec: DbExecutor = db): Promise<number> {
  const [row] = await exec
    .select({ balanceCents: workers.walletBalanceCents })
    .from(workers)
    .where(eq(workers.id, workerId))
    .limit(1)
  return row?.balanceCents ?? 0
}
