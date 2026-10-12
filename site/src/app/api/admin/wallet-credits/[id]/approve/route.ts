/**
 * POST /api/admin/wallet-credits/[id]/approve
 *
 * Second-admin approval of an ad-hoc wallet credit above the threshold
 * (ADMIN_CREDIT_SECOND_APPROVAL_THRESHOLD_CENTS). Only this route turns a
 * pending_admin_credits row into an admin_credit ledger entry. Modelled on
 * POST /api/admin/jobs/[id]/approve:
 *
 * Read-only guards (lib/admin-credit-approval.ts pendingCreditGuard):
 *  1. Request exists                                        → 404
 *  2. status === 'pending'                                   → 409
 *  3. The approver is not the admin who requested it         → 403
 *  4. The approver is not the credit's target                → 403
 *
 * Atomic claim (TOCTOU-safe), claimPendingCreditForApproval(): one
 * conditional UPDATE … WHERE status = 'pending' AND created_by <> approver
 * AND target <> approver, RETURNING. No row → 409.
 *
 * On a won claim: creditWallet(kind='admin_credit') under the request's own
 * idempotency key (a retried approve credits nothing twice), then the ledger
 * entry id is written back onto the request.
 */
import { NextResponse } from 'next/server'
import { eq } from 'drizzle-orm'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'
import { creditWallet } from '@/lib/wallet'
import {
  claimPendingCreditForApproval,
  getPendingAdminCredit,
  linkPendingCreditToEntry,
  pendingCreditGuard,
} from '@/lib/admin-credit-approval'
import { notifyWorker, notificationTemplates } from '@/lib/notifications'
import { payoutsPausedResponse } from '@/lib/payouts-enabled'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ id: string }> }

export async function POST(request: Request, context: RouteContext) {
  // PAYOUTS_ENABLED master switch (lib/payouts-enabled.ts): 503 before anything else.
  const paused = payoutsPausedResponse()
  if (paused) return paused

  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const { id } = await context.params
  const pendingId = parseInt(id, 10)
  if (Number.isNaN(pendingId)) {
    return NextResponse.json({ error: 'Invalid credit request id' }, { status: 400 })
  }

  const pending = await getPendingAdminCredit(db, pendingId)
  if (!pending) {
    return NextResponse.json({ error: 'Credit request not found' }, { status: 404 })
  }
  const refusal = pendingCreditGuard(pending, auth.workerId)
  if (refusal) {
    return NextResponse.json({ error: refusal.error }, { status: refusal.status })
  }

  const now = new Date().toISOString()
  const claimed = await claimPendingCreditForApproval(db, pendingId, auth.workerId, now)
  if (!claimed) {
    return NextResponse.json({ error: 'Credit request already decided' }, { status: 409 })
  }

  // The requester stays the ledger's createdByAdminId; the approver is on the request row.
  const credit = await creditWallet({
    workerId: claimed.targetWorkerId,
    amountCents: claimed.amountCents,
    kind: 'admin_credit',
    note: claimed.reason,
    createdByAdminId: claimed.createdByAdminId,
    idempotencyKey: claimed.idempotencyKey,
  })
  if (credit.entryId != null) {
    await linkPendingCreditToEntry(db, pendingId, credit.entryId, now)
  }

  // Notify the worker. A notification failure must never mask the credit.
  try {
    const [worker] = await db
      .select({
        phone: workers.phone,
        notificationsPushEnabled: workers.notificationsPushEnabled,
        notificationsPushSubscription: workers.notificationsPushSubscription,
        notificationsSmsEnabled: workers.notificationsSmsEnabled,
      })
      .from(workers)
      .where(eq(workers.id, claimed.targetWorkerId))
      .limit(1)
    if (worker && credit.credited) {
      await notifyWorker(
        {
          phone: worker.phone ?? undefined,
          pushSubscription: (worker.notificationsPushSubscription as any) ?? undefined,
          preferences: {
            pushEnabled: worker.notificationsPushEnabled ?? undefined,
            smsEnabled: worker.notificationsSmsEnabled ?? undefined,
          },
        },
        notificationTemplates.walletCredited(`$${(claimed.amountCents / 100).toFixed(2)}`, claimed.reason)
      )
    }
  } catch (notifyErr) {
    console.error('[wallet-credits/approve] Failed to send walletCredited notification for request ' + pendingId, notifyErr)
  }

  return NextResponse.json({
    ok: true,
    pendingCreditId: pendingId,
    walletEntryId: credit.entryId,
    balanceCents: credit.balanceCents,
    alreadyCredited: !credit.credited,
  })
}
