/**
 * POST /api/workers/me/withdraw
 *
 * Worker cashes out their in-app wallet balance.
 *
 * Body: { amountCents: number, method: 'stripe' | 'check', idempotencyKey: string }
 *
 *  - 'stripe': requires a payouts-ready Stripe Connect account (live-checked
 *    and persisted via syncConnectStatus, so a stale DB status can't block a
 *    genuinely onboarded worker). Transfer + payout, same failure semantics
 *    as the old admin payout route.
 *  - 'check': requires a complete mailing address; lands in the payouts table
 *    as 'check_pending' (the admin "checks to mail" queue) and alerts admins.
 *
 * Ordering / atomicity (neon-http, no interactive transactions):
 *  1. INSERT payouts row (unique idempotency key = atomic claim; retries get
 *     the original row back and never move money twice)
 *  2. Conditional balance debit (single UPDATE ... WHERE balance >= amount —
 *     concurrent withdrawals cannot overdraw). Insufficient → payout row
 *     marked 'failed', 409.
 *  3. Ledger debit entry
 *  4. Stripe transfer+payout (stripe method only). On failure the row stays
 *     'processing' and the debit stays held — the payout.failed webhook (or
 *     manual reconciliation) refunds the wallet via a 'withdrawal_reversal'.
 */
import { NextRequest, NextResponse } from 'next/server'
import { eq } from 'drizzle-orm'
import { db } from '@/db'
import { payouts, workers } from '@/db/schema'
import { verifyMobileToken } from '@/lib/mobile-auth'
import { debitWallet } from '@/lib/wallet'
import { isStripeConfigured } from '@/lib/stripe/client'
import { processAdminPayout } from '@/lib/stripe/connect'
import { syncConnectStatus } from '@/lib/stripe/sync-connect-status'
import { resolveMailingAddress } from '@/lib/payouts'
import { notifyAdmins, notificationTemplates } from '@/lib/notifications'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

// (Not exported — Next.js route files may only export handlers/config.)
/** Paper checks cost real money to cut and mail — keep them meaningful. */
const MIN_CHECK_WITHDRAWAL_CENTS = 2500
/** Stripe transfers are cheap; $1 floor just avoids dust. */
const MIN_STRIPE_WITHDRAWAL_CENTS = 100

export async function POST(request: NextRequest) {
  const authHeader = request.headers.get('authorization')
  const auth = authHeader?.startsWith('Bearer ')
    ? await verifyMobileToken(authHeader.substring(7))
    : null
  if (!auth) {
    return NextResponse.json({ data: null, message: 'Not authenticated' }, { status: 401 })
  }

  let body: { amountCents?: unknown; method?: unknown; idempotencyKey?: unknown }
  try {
    body = await request.json()
  } catch {
    return NextResponse.json(
      { data: null, success: false, message: 'Body must be valid JSON' },
      { status: 400 }
    )
  }

  const method = body.method === 'check' ? 'check' : body.method === 'stripe' ? 'stripe' : null
  if (!method) {
    return NextResponse.json(
      { data: null, success: false, message: "method must be 'stripe' or 'check'" },
      { status: 400 }
    )
  }

  const amountCents = typeof body.amountCents === 'number' ? body.amountCents : NaN
  const minCents = method === 'check' ? MIN_CHECK_WITHDRAWAL_CENTS : MIN_STRIPE_WITHDRAWAL_CENTS
  if (!Number.isInteger(amountCents) || amountCents < minCents) {
    return NextResponse.json(
      {
        data: null,
        success: false,
        message:
          method === 'check'
            ? `Paper check cash outs start at $${MIN_CHECK_WITHDRAWAL_CENTS / 100}`
            : `Cash outs start at $${MIN_STRIPE_WITHDRAWAL_CENTS / 100}`,
      },
      { status: 400 }
    )
  }

  const idempotencyKey =
    typeof body.idempotencyKey === 'string' ? body.idempotencyKey.trim() : ''
  if (idempotencyKey.length < 8 || idempotencyKey.length > 128) {
    return NextResponse.json(
      { data: null, success: false, message: 'idempotencyKey must be 8–128 chars' },
      { status: 400 }
    )
  }

  const [worker] = await db
    .select({
      id: workers.id,
      name: workers.name,
      payoutMailingAddress: workers.payoutMailingAddress,
      addressStreet: workers.addressStreet,
      addressCity: workers.addressCity,
      addressState: workers.addressState,
      addressZip: workers.addressZip,
      stripeAccountId: workers.stripeAccountId,
      walletBalanceCents: workers.walletBalanceCents,
    })
    .from(workers)
    .where(eq(workers.id, auth.workerId))
    .limit(1)

  if (!worker) {
    return NextResponse.json({ data: null, message: 'Worker not found' }, { status: 404 })
  }

  // ── Method-specific read-only guards, BEFORE any write or Stripe call ────
  let mailingAddress: ReturnType<typeof resolveMailingAddress> = null
  let useInstant = false
  if (method === 'stripe') {
    if (!isStripeConfigured) {
      return NextResponse.json(
        { data: null, success: false, message: 'Payments are not configured on this server' },
        { status: 503 }
      )
    }
    if (!worker.stripeAccountId) {
      return NextResponse.json(
        {
          data: null,
          success: false,
          message: 'Set up direct deposit first (Wallet → payout setup), or cash out by paper check.',
        },
        { status: 409 }
      )
    }
    // Live check + persist — never trust a possibly-stale DB status here.
    const status = await syncConnectStatus(worker.id, worker.stripeAccountId)
    if (!status.payoutsReady) {
      return NextResponse.json(
        {
          data: null,
          success: false,
          message:
            'Your direct deposit setup is not finished yet. Finish it in Wallet, or cash out by paper check.',
        },
        { status: 409 }
      )
    }
    useInstant = status.instantPayoutsEnabled
  } else {
    mailingAddress = resolveMailingAddress(worker)
    if (!mailingAddress) {
      return NextResponse.json(
        {
          data: null,
          success: false,
          message: 'Add a mailing address before cashing out by paper check.',
        },
        { status: 409 }
      )
    }
  }

  // ── Step 1: atomic claim — INSERT with unique idempotency key ────────────
  const inserted = await db
    .insert(payouts)
    .values({
      workerId: worker.id,
      amountCents,
      note: 'Wallet cash out',
      method,
      status: method === 'check' ? 'check_pending' : 'processing',
      initiatedByWorkerId: worker.id,
      idempotencyKey,
      mailingAddress: mailingAddress ?? null,
    })
    .onConflictDoNothing({ target: payouts.idempotencyKey })
    .returning()

  if (inserted.length === 0) {
    // Retried request: return the original outcome, never move money twice.
    const [existing] = await db
      .select()
      .from(payouts)
      .where(eq(payouts.idempotencyKey, idempotencyKey))
      .limit(1)
    return NextResponse.json({
      data: {
        payout: existing ?? null,
        alreadyProcessed: true,
        balanceCents: worker.walletBalanceCents,
      },
      success: true,
      message: null,
    })
  }

  const payoutRow = inserted[0]

  // ── Step 2+3: hold the funds (conditional debit + ledger entry) ──────────
  const debit = await debitWallet({
    workerId: worker.id,
    amountCents,
    kind: 'withdrawal',
    payoutId: payoutRow.id,
    note: method === 'check' ? 'Cash out — paper check' : 'Cash out — direct deposit',
  })

  if (!debit.debited) {
    await db
      .update(payouts)
      .set({ status: 'failed', note: 'Insufficient wallet balance', updatedAt: new Date().toISOString() })
      .where(eq(payouts.id, payoutRow.id))
    return NextResponse.json(
      {
        data: { payout: null, alreadyProcessed: false, balanceCents: debit.balanceCents },
        success: false,
        message: `Not enough in your wallet — available balance is $${(debit.balanceCents / 100).toFixed(2)}.`,
      },
      { status: 409 }
    )
  }

  // ── Check path: queued for manual mailing; alert admins ──────────────────
  if (method === 'check') {
    try {
      await notifyAdmins(
        notificationTemplates.checkToMail(
          worker.name,
          (amountCents / 100).toFixed(2),
          payoutRow.id
        )
      )
    } catch (notifyErr) {
      console.error(
        '[withdraw] Failed to send checkToMail alert for payout ' + payoutRow.id,
        notifyErr
      )
    }
    return NextResponse.json({
      data: { payout: payoutRow, alreadyProcessed: false, balanceCents: debit.balanceCents },
      success: true,
      message: null,
    })
  }

  // ── Step 4: Stripe transfer + payout ──────────────────────────────────────
  let transferId: string
  let stripePayoutId: string | undefined
  try {
    const result = await processAdminPayout(
      worker.stripeAccountId!,
      amountCents,
      String(payoutRow.id),
      String(worker.id),
      useInstant
    )
    transferId = result.transfer.id
    stripePayoutId = result.payout.id
  } catch (err) {
    // CRITICAL: do NOT mark 'failed' or refund here — the transfer may have
    // partially completed. 'processing' blocks retries; the payout.failed
    // webhook (or manual reconciliation) refunds the held funds.
    console.error(
      '[withdraw] CRITICAL: Stripe payout failed for payout ' + payoutRow.id +
        ' — transfer may have partially completed; manual reconciliation required',
      err
    )
    return NextResponse.json(
      {
        data: { payout: payoutRow, alreadyProcessed: false, balanceCents: debit.balanceCents },
        success: false,
        message:
          'Cash out is on hold — our payment provider had a problem. Support has been alerted; do not retry.',
      },
      { status: 502 }
    )
  }

  // Money has moved. If persisting IDs fails, still return them to the caller;
  // the payout.paid webhook reconciles status → completed.
  try {
    await db
      .update(payouts)
      .set({
        stripeTransferId: transferId,
        stripePayoutId: stripePayoutId ?? null,
        updatedAt: new Date().toISOString(),
      })
      .where(eq(payouts.id, payoutRow.id))
  } catch (e) {
    console.error(
      '[withdraw] CRITICAL: transfer ' + transferId +
        ' created but failed to persist for payout ' + payoutRow.id,
      e
    )
  }

  return NextResponse.json({
    data: {
      payout: { ...payoutRow, stripeTransferId: transferId, stripePayoutId },
      alreadyProcessed: false,
      balanceCents: debit.balanceCents,
    },
    success: true,
    message: null,
  })
}
