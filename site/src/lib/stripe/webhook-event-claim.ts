/**
 * At-most-once-SUCCESSFUL processing for Stripe webhook events.
 *
 * The event id is claimed (INSERT … ON CONFLICT DO NOTHING) before the
 * handler runs, so concurrent/duplicate deliveries skip. If the handler
 * throws, the claim is released so Stripe's retry of the same event is
 * processed instead of being acked as a "duplicate" of a run that failed —
 * previously only invoice.paid released its claim; every other handler's
 * failure was swallowed permanently.
 */
import { eq } from 'drizzle-orm'
import { stripeEvents } from '@/db/schema'
import type { DbExecutor } from '@/lib/job-lifecycle'

export interface EventClaimStore {
  /** True when this call won the claim (first time we've seen the id). */
  claim(eventId: string): Promise<boolean>
  release(eventId: string): Promise<void>
}

export function dbEventClaimStore(exec: DbExecutor): EventClaimStore {
  return {
    async claim(eventId) {
      const rows = await exec
        .insert(stripeEvents)
        .values({ id: eventId })
        .onConflictDoNothing()
        .returning({ id: stripeEvents.id })
      return rows.length > 0
    },
    async release(eventId) {
      await exec.delete(stripeEvents).where(eq(stripeEvents.id, eventId))
    },
  }
}

export type EventOutcome = 'processed' | 'duplicate'

/**
 * Run `handler` once per event id. Throws the handler's error after releasing
 * the claim (the caller answers 500 so Stripe retries).
 */
export async function processEventOnce(
  eventId: string,
  store: EventClaimStore,
  handler: () => Promise<void>,
): Promise<EventOutcome> {
  const won = await store.claim(eventId)
  if (!won) return 'duplicate'
  try {
    await handler()
  } catch (err) {
    try {
      await store.release(eventId)
    } catch (releaseErr) {
      // Leave the original error as the one that surfaces; the stuck claim
      // is logged so it can be deleted by hand for a manual replay.
      console.error(`[Webhook] Failed to release claim for event ${eventId} after handler error`, releaseErr)
    }
    throw err
  }
  return 'processed'
}
