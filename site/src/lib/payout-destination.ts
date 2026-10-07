/**
 * A change to where a worker's cash outs go (payout method, check mailing
 * address override, a new Stripe Connect account) is the classic account-
 * takeover move: redirect the money, then withdraw it. Every such change
 * stamps workers.payout_method_changed_at (done by the route making the
 * change) — the withdraw route holds cash outs for 24h after it
 * (lib/withdraw-guards.ts) — and calls this to tell the worker.
 */
import { eq } from 'drizzle-orm'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { notifyWorker, notificationTemplates } from '@/lib/notifications'

/** Best-effort: never throws (the change itself already happened). */
export async function notifyPayoutDestinationChanged(workerId: number): Promise<void> {
  try {
    const [worker] = await db
      .select({
        phone: workers.phone,
        pushEnabled: workers.notificationsPushEnabled,
        pushSubscription: workers.notificationsPushSubscription,
        smsEnabled: workers.notificationsSmsEnabled,
      })
      .from(workers)
      .where(eq(workers.id, workerId))
      .limit(1)
    if (!worker) return
    await notifyWorker(
      {
        phone: worker.phone ?? undefined,
        pushSubscription: (worker.pushSubscription as any) ?? undefined,
        preferences: {
          pushEnabled: worker.pushEnabled ?? undefined,
          smsEnabled: worker.smsEnabled ?? undefined,
        },
      },
      notificationTemplates.payoutMethodChanged(),
    )
  } catch (err) {
    console.error(`[payout-destination] notification failed for worker ${workerId}`, err)
  }
}
