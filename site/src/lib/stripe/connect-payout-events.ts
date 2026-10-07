/**
 * Webhook handling for a worker cash out that went wrong AFTER the money left
 * the platform.
 *
 * A Stripe cash out is two steps: transfer (platform balance → worker's
 * connected account) then payout (connected account → worker's bank). When the
 * PAYOUT fails, Stripe returns the funds to the CONNECTED ACCOUNT's balance —
 * not to the platform. Re-crediting the in-app wallet at that point (what this
 * handler used to do) left the worker holding the same money twice: once in
 * the wallet (cash-out-able again) and once in their connected balance.
 *
 * So:
 *  - payout.failed   → mark the payouts row 'failed' and record where the
 *                      money is. The wallet is NOT credited. An admin either
 *                      retries the payout from the connected balance, or
 *                      reverses the transfer.
 *  - transfer.reversed (full reversal) → the money is back on the platform;
 *                      only now does the wallet get its withdrawal_reversal
 *                      credit (once per payout — unique index).
 *  - payout.paid       → the cash out (or a legacy job payout) landed; the
 *                      status flips once and earnings stats count only on
 *                      that flip.
 *
 * Every function here is safe to re-run: the webhook releases its event claim
 * when a handler throws, so Stripe's retry re-runs the whole handler.
 */
import { and, eq, sql } from 'drizzle-orm'
import { jobs, payouts, workers, workerWalletEntries } from '@/db/schema'
import type { DbExecutor } from '@/lib/job-lifecycle'

export interface FailedConnectPayout {
  /** Our payouts.id, from the Stripe payout's metadata.payoutId. */
  payoutRowId: number
  stripePayoutId: string
  /** Connected account the funds now sit in (event.account). */
  connectedAccountId: string | null
  failureCode: string | null
}

export interface FailedPayoutRecord {
  workerId: number
  amountCents: number
  stripeTransferId: string | null
  note: string
}

export function failedPayoutNote(p: FailedConnectPayout, stripeTransferId: string | null): string {
  const where = p.connectedAccountId ? `connected account ${p.connectedAccountId}` : 'the worker’s connected account'
  const transfer = stripeTransferId ? ` or reverse transfer ${stripeTransferId}` : ' or reverse the transfer'
  return (
    `Bank payout ${p.stripePayoutId} failed${p.failureCode ? ` (${p.failureCode})` : ''}. ` +
    `Funds remain in ${where}, NOT in the wallet. Retry the payout from the connected balance${transfer}.`
  ).slice(0, 500)
}

/** payout.failed for a wallet cash out. Never credits the wallet. */
export async function recordConnectPayoutFailed(
  exec: DbExecutor,
  p: FailedConnectPayout,
): Promise<FailedPayoutRecord | null> {
  const [existing] = await exec
    .select({
      workerId: payouts.workerId,
      amountCents: payouts.amountCents,
      stripeTransferId: payouts.stripeTransferId,
    })
    .from(payouts)
    .where(eq(payouts.id, p.payoutRowId))
    .limit(1)
  if (!existing) return null

  const note = failedPayoutNote(p, existing.stripeTransferId)
  await exec
    .update(payouts)
    .set({
      status: 'failed',
      stripePayoutId: p.stripePayoutId,
      note,
      updatedAt: new Date().toISOString(),
    })
    .where(eq(payouts.id, p.payoutRowId))

  return { ...existing, note }
}

export interface ReversedTransfer {
  payoutRowId: number
  stripeTransferId: string
  amountCents: number
  amountReversedCents: number
}

export type TransferReversalOutcome =
  | { credited: true; workerId: number; amountCents: number }
  | { credited: false; reason: 'no_payout' | 'partial' | 'not_a_withdrawal' | 'already_credited' }

/**
 * transfer.reversed: the platform got the money back from the connected
 * account. Credit the wallet only for a FULL reversal of a wallet withdrawal;
 * partial reversals are left for an admin to reconcile by hand.
 */
export async function creditWalletForReversedTransfer(
  exec: DbExecutor,
  t: ReversedTransfer,
): Promise<TransferReversalOutcome> {
  const [row] = await exec
    .select({ workerId: payouts.workerId, amountCents: payouts.amountCents })
    .from(payouts)
    .where(eq(payouts.id, t.payoutRowId))
    .limit(1)
  if (!row) return { credited: false, reason: 'no_payout' }

  if (t.amountReversedCents < t.amountCents || t.amountReversedCents < row.amountCents) {
    console.error(
      `[webhook] transfer ${t.stripeTransferId} partially reversed (${t.amountReversedCents}/${t.amountCents}) ` +
        `for payout ${t.payoutRowId} — wallet not credited; reconcile manually`,
    )
    return { credited: false, reason: 'partial' }
  }

  // Legacy ad-hoc admin payouts never debited a wallet — nothing to return.
  const [withdrawal] = await exec
    .select({ id: workerWalletEntries.id })
    .from(workerWalletEntries)
    .where(and(eq(workerWalletEntries.payoutId, t.payoutRowId), eq(workerWalletEntries.kind, 'withdrawal')))
    .limit(1)
  if (!withdrawal) return { credited: false, reason: 'not_a_withdrawal' }

  // Not gated by PAYOUTS_ENABLED on purpose: a full reversal means the money
  // came back to the platform, so this only restores the worker's balance;
  // withdrawals themselves are paused by the switch. Gating here would mark the
  // event handled and lose the credit.
  if (!Number.isInteger(row.amountCents) || row.amountCents <= 0) {
    throw new Error(`transfer.reversed: payout ${t.payoutRowId} has a non-positive amount ${row.amountCents}`)
  }
  // ONE statement (neon-http has no interactive transactions): the ledger
  // entry, the balance bump and the payout status commit together or not at
  // all. The entry's guard is the partial unique index
  // worker_wallet_entries_withdrawal_reversal_unique (payout_id) WHERE
  // kind = 'withdrawal_reversal' (migration 0038), so a re-run inserts nothing
  // and therefore bumps nothing. lib/wallet.ts creditWallet() is two
  // statements, and a retry after a failure between them would leave the
  // ledger credited and the balance not.
  const now = new Date().toISOString()
  const ins = exec
    .$with('ins', { id: workerWalletEntries.id })
    .as(exec
      .insert(workerWalletEntries)
      .values({
        workerId: row.workerId,
        amountCents: row.amountCents,
        kind: 'withdrawal_reversal',
        payoutId: t.payoutRowId,
        note: `Cash out reversed (transfer ${t.stripeTransferId}) — funds returned to wallet`,
      })
      .onConflictDoNothing()
      .returning({ id: workerWalletEntries.id })
      .getSQL())
  const bump = exec
    .$with('bump', { id: workers.id })
    .as(exec
      .update(workers)
      .set({ walletBalanceCents: sql`${workers.walletBalanceCents} + ${row.amountCents}`, updatedAt: now })
      .where(and(eq(workers.id, row.workerId), sql`exists (select 1 from "ins")`))
      .returning({ id: workers.id })
      .getSQL())
  // Unconditional and idempotent: a retry after the credit still marks it.
  const mark = exec
    .$with('mark', { id: payouts.id })
    .as(exec
      .update(payouts)
      .set({ status: 'failed', updatedAt: now })
      .where(eq(payouts.id, t.payoutRowId))
      .returning({ id: payouts.id })
      .getSQL())
  const created = await exec.with(ins, bump, mark).select({ id: ins.id }).from(ins)
  if (created.length === 0) return { credited: false, reason: 'already_credited' }

  return { credited: true, workerId: row.workerId, amountCents: row.amountCents }
}

export interface PaidPayoutRecord {
  workerId: number
  amountCents: number
}

/**
 * payout.paid for a payouts-table row (wallet cash out or legacy ad-hoc admin
 * payout). One statement: the status flips to 'completed' only if it is not
 * already, and the legacy earnings bump (rows with no withdrawal ledger entry
 * — wallet withdrawals were counted when the wallet was credited) rides on
 * that flip. Returns null when the row is unknown or already completed, so the
 * caller notifies the worker once.
 */
export async function recordConnectPayoutPaid(
  exec: DbExecutor,
  p: { payoutRowId: number; stripePayoutId: string },
): Promise<PaidPayoutRecord | null> {
  const now = new Date().toISOString()
  const paid = exec
    .$with('paid', { id: payouts.id, workerId: payouts.workerId, amountCents: payouts.amountCents })
    .as(exec
      .update(payouts)
      .set({ status: 'completed', stripePayoutId: p.stripePayoutId, updatedAt: now })
      .where(and(eq(payouts.id, p.payoutRowId), sql`${payouts.status} <> 'completed'`))
      .returning({ id: payouts.id, workerId: payouts.workerId, amountCents: payouts.amountCents })
      .getSQL())
  const legacyEarnings = exec
    .$with('legacy_earnings', { id: workers.id })
    .as(sql`update "workers" set "stats_total_earnings" = COALESCE("workers"."stats_total_earnings", 0) + "paid"."amount_cents", "updated_at" = ${now} from "paid" where "workers"."id" = "paid"."worker_id" and not exists (select 1 from "worker_wallet_entries" where "worker_wallet_entries"."payout_id" = "paid"."id" and "worker_wallet_entries"."kind" = 'withdrawal') returning "workers"."id"`)
  const [row] = await exec
    .with(paid, legacyEarnings)
    .select({ workerId: paid.workerId, amountCents: paid.amountCents })
    .from(paid)
  return row ? { workerId: Number(row.workerId), amountCents: Number(row.amountCents) } : null
}

/**
 * payout.paid for a legacy per-job Stripe payout (metadata.jobId; nothing
 * creates these since the wallet, but in-flight ones may still land). The job
 * flips to payout 'completed' once; the worker's job/earnings stats ride on
 * that flip in the same statement. Returns the job's payout amount (cents)
 * when this call flipped it, else null.
 */
export async function recordJobPayoutPaid(
  exec: DbExecutor,
  p: { jobId: number; workerId: number | null },
): Promise<{ payoutCents: number } | null> {
  const now = new Date().toISOString()
  const done = exec
    .$with('done', { id: jobs.id, payoutAmount: jobs.payoutAmount })
    .as(exec
      .update(jobs)
      .set({ payoutStatus: 'completed', payoutCompletedAt: now, updatedAt: now })
      .where(and(eq(jobs.id, p.jobId), sql`${jobs.payoutStatus} IS DISTINCT FROM 'completed'`))
      .returning({ id: jobs.id, payoutAmount: jobs.payoutAmount })
      .getSQL())
  const stats = p.workerId == null
    ? null
    : exec
      .$with('stats', { id: workers.id })
      .as(sql`update "workers" set "stats_jobs_completed" = COALESCE("workers"."stats_jobs_completed", 0) + 1, "stats_total_earnings" = COALESCE("workers"."stats_total_earnings", 0) + COALESCE("done"."payout_amount", 10000), "stats_last_job_at" = ${now}, "updated_at" = ${now} from "done" where "workers"."id" = ${p.workerId} returning "workers"."id"`)
  const [row] = await (stats ? exec.with(done, stats) : exec.with(done))
    .select({ payoutAmount: done.payoutAmount })
    .from(done)
  if (!row) return null
  return { payoutCents: Number(row.payoutAmount ?? 10000) }
}
