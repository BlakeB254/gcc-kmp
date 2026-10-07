/**
 * POST /api/admin/payouts/[id]/mark-mailed
 *
 * Fulfills a paper-check payout: check_pending → check_mailed via an atomic
 * conditional UPDATE (a concurrent mark-mailed sees 0 rows → 409, so a check
 * can never be recorded as mailed twice). Bumps the worker's earnings stats
 * (the check path has no payout.paid webhook to do it) and notifies them.
 */
import { NextRequest, NextResponse } from 'next/server'
import { and, eq } from 'drizzle-orm'
import { db } from '@/db'
import { payouts, workers } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'
import { notifyWorker, notificationTemplates } from '@/lib/notifications'
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
  const payoutId = parseInt(id, 10)
  if (isNaN(payoutId)) {
    return NextResponse.json(
      { data: null, success: false, message: 'Invalid payout id' },
      { status: 400 }
    )
  }

  // Atomic claim: only a check_pending row can transition to check_mailed.
  const claimed = await db
    .update(payouts)
    .set({
      status: 'check_mailed',
      checkMailedAt: new Date().toISOString(),
      checkMailedByAdminId: auth.workerId,
      updatedAt: new Date().toISOString(),
    })
    .where(and(eq(payouts.id, payoutId), eq(payouts.status, 'check_pending')))
    .returning()

  if (claimed.length === 0) {
    const [existing] = await db
      .select({ id: payouts.id, status: payouts.status })
      .from(payouts)
      .where(eq(payouts.id, payoutId))
      .limit(1)
    if (!existing) {
      return NextResponse.json(
        { data: null, success: false, message: 'Payout not found' },
        { status: 404 }
      )
    }
    return NextResponse.json(
      {
        data: null,
        success: false,
        message: `Payout is not awaiting mailing (status: ${existing.status})`,
      },
      { status: 409 }
    )
  }

  const payoutRow = claimed[0]

  // Earnings bump — mirrors what the payout.paid webhook does for Stripe
  // payouts. Best-effort: the mailed status is already durable above.
  const [worker] = await db
    .select()
    .from(workers)
    .where(eq(workers.id, payoutRow.workerId))
    .limit(1)

  if (worker) {
    try {
      await db
        .update(workers)
        .set({
          statsTotalEarnings: String(
            Number(worker.statsTotalEarnings || 0) + payoutRow.amountCents
          ),
          updatedAt: new Date().toISOString(),
        })
        .where(eq(workers.id, payoutRow.workerId))
    } catch (e) {
      console.error(
        '[mark-mailed] Failed to bump earnings for worker ' + payoutRow.workerId +
          ' (payout ' + payoutId + ')',
        e
      )
    }

    // Fire-and-forget-with-logging: never mask the successful state change.
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
        notificationTemplates.checkMailed(`$${(payoutRow.amountCents / 100).toFixed(2)}`)
      )
    } catch (notifyErr) {
      console.error(
        '[mark-mailed] Failed to send checkMailed notification for payout ' + payoutId,
        notifyErr
      )
    }
  }

  return NextResponse.json({
    data: { payout: payoutRow },
    success: true,
    message: null,
  })
}
