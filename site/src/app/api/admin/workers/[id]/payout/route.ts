/**
 * POST /api/admin/workers/[id]/payout
 *
 * Admin credits a worker's in-app wallet (bonus / correction / manual job
 * payment). Since the wallet-ledger rework this NEVER touches Stripe and has
 * no Stripe-Connect or mailing-address prerequisites: the credit lands in the
 * worker's wallet balance immediately, and the worker cashes out on their own
 * schedule (Stripe direct deposit or mailed check) from the app.
 *
 * Body: { amountCents: number, note?: string, idempotencyKey: string }
 *
 * Idempotency: the unique index on workerWalletEntries.idempotency_key makes
 * the ledger INSERT the atomic claim. A retried request with the same key
 * credits nothing and returns alreadyProcessed=true.
 *
 * Response keeps the legacy CreatePayoutResponse shape ({ payout, ... }) so
 * pre-rework mobile builds continue to parse it; `payout.method` is 'wallet'
 * and `payout.status` is 'completed'.
 */
import { NextRequest, NextResponse } from 'next/server'
import { eq } from 'drizzle-orm'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'
import { creditWallet } from '@/lib/wallet'
import { notifyWorker, notificationTemplates } from '@/lib/notifications'
import { MAX_PAYOUT_CENTS, MAX_NOTE_LEN } from '@/lib/payouts'

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
      phone: workers.phone,
      notificationsPushEnabled: workers.notificationsPushEnabled,
      notificationsPushSubscription: workers.notificationsPushSubscription,
      notificationsSmsEnabled: workers.notificationsSmsEnabled,
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

  const result = await creditWallet({
    workerId: targetWorkerId,
    amountCents,
    kind: 'admin_credit',
    note: note || null,
    createdByAdminId: auth.workerId,
    idempotencyKey,
  })

  // Legacy-compatible payout object (old builds read data.payout.status).
  const payout = {
    id: result.entryId ?? 0,
    workerId: targetWorkerId,
    amountCents,
    note: note || null,
    method: 'wallet',
    status: 'completed',
    initiatedByAdminId: auth.workerId,
    createdAt: new Date().toISOString(),
  }

  if (!result.credited) {
    return NextResponse.json({
      data: { payout, alreadyProcessed: true, balanceCents: result.balanceCents },
      success: true,
      message: null,
    })
  }

  // Fire-and-forget-with-logging: the ledger row is the source of truth; a
  // notification failure must not fail the request.
  try {
    await notifyWorker(
      {
        phone: worker.phone ?? undefined,
        pushSubscription: (worker.notificationsPushSubscription as any) ?? undefined,
        preferences: {
          pushEnabled: worker.notificationsPushEnabled ?? undefined,
          smsEnabled: worker.notificationsSmsEnabled ?? undefined,
        },
      },
      notificationTemplates.walletCredited(
        `$${(amountCents / 100).toFixed(2)}`,
        note || 'your work'
      )
    )
  } catch (notifyErr) {
    console.error(
      '[admin-payout] Failed to send walletCredited notification for entry ' + result.entryId,
      notifyErr
    )
  }

  return NextResponse.json({
    data: { payout, alreadyProcessed: false, balanceCents: result.balanceCents },
    success: true,
    message: null,
  })
}
