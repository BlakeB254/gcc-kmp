/**
 * POST /api/admin/workers/[id]/payout
 *
 * Admin sends a one-off payout to a worker. Routing depends on the worker's
 * payout method preference:
 *  - 'stripe': transfer + payout via Stripe Connect (instant w/ standard
 *    fallback), same failure semantics as the job-approve route.
 *  - 'check': lands in the payouts table as 'check_pending' — the admin
 *    "checks to mail" queue — and push-alerts all admins.
 *
 * Body: { amountCents: number, note?: string, idempotencyKey: string }
 *
 * Idempotency: the payouts.idempotency_key unique index makes the INSERT the
 * atomic claim. A retried request with the same key gets the original row
 * back (200, alreadyProcessed) and can never move money twice.
 *
 * Stripe failure: the row stays 'processing' (blocks retries — the transfer
 * may have partially completed), 502, manual reconciliation. Mirrors
 * api/admin/jobs/[id]/approve.
 */
import { NextRequest, NextResponse } from 'next/server'
import { eq } from 'drizzle-orm'
import { db } from '@/db'
import { payouts, workers } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'
import { processAdminPayout } from '@/lib/stripe/connect'
import { notifyAdmins, notificationTemplates } from '@/lib/notifications'
import { MAX_PAYOUT_CENTS, MAX_NOTE_LEN, resolveMailingAddress } from '@/lib/payouts'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const { id } = await params
  const targetWorkerId = parseInt(id, 10)
  if (isNaN(targetWorkerId)) {
    return NextResponse.json(
      { data: null, success: false, message: 'Invalid worker id' },
      { status: 400 }
    )
  }

  let body: { amountCents?: unknown; note?: unknown; idempotencyKey?: unknown }
  try {
    body = await request.json()
  } catch {
    return NextResponse.json(
      { data: null, success: false, message: 'Body must be valid JSON' },
      { status: 400 }
    )
  }

  const amountCents = typeof body.amountCents === 'number' ? body.amountCents : NaN
  if (!Number.isInteger(amountCents) || amountCents <= 0 || amountCents > MAX_PAYOUT_CENTS) {
    return NextResponse.json(
      {
        data: null,
        success: false,
        message: `amountCents must be an integer between 1 and ${MAX_PAYOUT_CENTS} ($${MAX_PAYOUT_CENTS / 100})`,
      },
      { status: 400 }
    )
  }

  const note = typeof body.note === 'string' ? body.note.trim() : ''
  if (note.length > MAX_NOTE_LEN) {
    return NextResponse.json(
      { data: null, success: false, message: `note must be ≤ ${MAX_NOTE_LEN} chars` },
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
      payoutMethod: workers.payoutMethod,
      payoutMailingAddress: workers.payoutMailingAddress,
      addressStreet: workers.addressStreet,
      addressCity: workers.addressCity,
      addressState: workers.addressState,
      addressZip: workers.addressZip,
      stripeAccountId: workers.stripeAccountId,
      stripeOnboardingStatus: workers.stripeOnboardingStatus,
      stripeInstantPayoutsEnabled: workers.stripeInstantPayoutsEnabled,
    })
    .from(workers)
    .where(eq(workers.id, targetWorkerId))
    .limit(1)

  if (!worker) {
    return NextResponse.json(
      { data: null, success: false, message: 'Worker not found' },
      { status: 404 }
    )
  }

  const method = worker.payoutMethod === 'check' ? 'check' : 'stripe'

  // ── Method-specific read-only guards, BEFORE any write or Stripe call ────
  let mailingAddress: ReturnType<typeof resolveMailingAddress> = null
  if (method === 'stripe') {
    if (!worker.stripeAccountId || worker.stripeOnboardingStatus !== 'completed') {
      return NextResponse.json(
        {
          data: null,
          success: false,
          message:
            'Worker has not completed Stripe payout setup. Ask them to finish setup in Wallet, or have them switch to paper checks.',
        },
        { status: 409 }
      )
    }
  } else {
    mailingAddress = resolveMailingAddress(worker)
    if (!mailingAddress) {
      return NextResponse.json(
        {
          data: null,
          success: false,
          message:
            'Worker chose paper checks but has no complete mailing address on file.',
        },
        { status: 409 }
      )
    }
  }

  // ── Atomic claim: INSERT with unique idempotency key ─────────────────────
  const inserted = await db
    .insert(payouts)
    .values({
      workerId: targetWorkerId,
      amountCents,
      note: note || null,
      method,
      status: method === 'check' ? 'check_pending' : 'processing',
      initiatedByAdminId: auth.workerId,
      idempotencyKey,
      mailingAddress: mailingAddress ?? null,
    })
    .onConflictDoNothing({ target: payouts.idempotencyKey })
    .returning()

  if (inserted.length === 0) {
    // Retried request: return the original outcome, never re-send money.
    const [existing] = await db
      .select()
      .from(payouts)
      .where(eq(payouts.idempotencyKey, idempotencyKey))
      .limit(1)
    return NextResponse.json({
      data: { payout: existing ?? null, alreadyProcessed: true },
      success: true,
      message: null,
    })
  }

  const payoutRow = inserted[0]

  // ── Check path: queued for manual mailing; alert admins ──────────────────
  if (method === 'check') {
    // Fire-and-forget-with-logging: the queue row is the source of truth; a
    // notification failure must not fail the request.
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
        '[admin-payout] Failed to send checkToMail alert for payout ' + payoutRow.id,
        notifyErr
      )
    }
    return NextResponse.json({
      data: { payout: payoutRow, alreadyProcessed: false },
      success: true,
      message: null,
    })
  }

  // ── Stripe path: transfer + payout ────────────────────────────────────────
  let transferId: string
  let stripePayoutId: string | undefined
  try {
    const result = await processAdminPayout(
      worker.stripeAccountId!,
      amountCents,
      String(payoutRow.id),
      String(targetWorkerId),
      worker.stripeInstantPayoutsEnabled ?? false
    )
    transferId = result.transfer.id
    stripePayoutId = result.payout.id
  } catch (err) {
    // CRITICAL: do NOT mark 'failed' — the transfer may have partially
    // completed. 'processing' blocks retries (idempotency key + status);
    // reconcile manually or via the payout.paid/failed webhook.
    console.error(
      '[admin-payout] CRITICAL: Stripe payout failed for payout ' + payoutRow.id +
        ' — transfer may have partially completed; manual reconciliation required',
      err
    )
    return NextResponse.json(
      {
        data: { payout: payoutRow, alreadyProcessed: false },
        success: false,
        message:
          'Stripe payout failed — payout recorded but money status unknown. Do NOT retry; manual reconciliation required.',
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
      '[admin-payout] CRITICAL: transfer ' + transferId +
        ' created but failed to persist for payout ' + payoutRow.id,
      e
    )
    return NextResponse.json({
      data: {
        payout: { ...payoutRow, stripeTransferId: transferId },
        alreadyProcessed: false,
        persistFailed: true,
      },
      success: true,
      message: null,
    })
  }

  return NextResponse.json({
    data: {
      payout: { ...payoutRow, stripeTransferId: transferId, stripePayoutId },
      alreadyProcessed: false,
    },
    success: true,
    message: null,
  })
}
