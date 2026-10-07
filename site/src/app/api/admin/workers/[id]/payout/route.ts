/**
 * POST /api/admin/workers/[id]/payout
 *
 * Admin credits a worker's in-app wallet (bonus / correction). NOT for job
 * money: a job earning is credited only by approving the job
 * (POST /api/admin/jobs/[id]/approve). Refused (see lib/admin-credit.ts):
 * crediting your own wallet, a body carrying jobId, a reason naming a job,
 * and a missing reason. Since the wallet-ledger rework this NEVER touches Stripe and has
 * no Stripe-Connect or mailing-address prerequisites: the credit lands in the
 * worker's wallet balance immediately, and the worker cashes out on their own
 * schedule (Stripe direct deposit or mailed check) from the app.
 *
 * Body: { amountCents: number, reason: string, idempotencyKey: string }
 * (`note` is accepted as the reason for older admin builds.) The ledger row
 * records the reason and the initiating admin (createdByAdminId).
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
import { validateAdminCredit, type AdminCreditBody } from '@/lib/admin-credit'
import { payoutsPausedResponse } from '@/lib/payouts-enabled'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  // PAYOUTS_ENABLED master switch (lib/payouts-enabled.ts): 503 before anything else.
  const paused = payoutsPausedResponse()
  if (paused) return paused

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

  let body: AdminCreditBody
  try {
    body = await request.json()
  } catch {
    return NextResponse.json(
      { data: null, success: false, message: 'Body must be valid JSON' },
      { status: 400 }
    )
  }
  if (!body || typeof body !== 'object') {
    return NextResponse.json(
      { data: null, success: false, message: 'Body must be a JSON object' },
      { status: 400 }
    )
  }

  const decision = validateAdminCredit({ adminWorkerId: auth.workerId, targetWorkerId, body })
  if (!decision.ok) {
    return NextResponse.json(
      { data: null, success: false, message: decision.message },
      { status: decision.status }
    )
  }
  const { amountCents, reason: note, idempotencyKey } = decision

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
    note,
    createdByAdminId: auth.workerId,
    idempotencyKey,
  })

  // Legacy-compatible payout object (old builds read data.payout.status).
  const payout = {
    id: result.entryId ?? 0,
    workerId: targetWorkerId,
    amountCents,
    note,
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
        note
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
