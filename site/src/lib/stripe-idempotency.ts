import crypto from 'crypto'

const FIVE_MINUTES_MS = 5 * 60 * 1000

/**
 * Stable per-cart idempotency key for Stripe `checkout.sessions.create`.
 *
 * Same inputs within the same 5-minute window → same key → Stripe returns
 * the existing session instead of creating a duplicate. Protects against
 * double-clicks and client retries without locking out a deliberate re-purchase
 * by the same user some time later.
 */
export function stripeIdempotencyKey(cart: Record<string, unknown>): string {
  const bucket = Math.floor(Date.now() / FIVE_MINUTES_MS)
  const payload = JSON.stringify({ cart, bucket })
  return crypto.createHash('sha256').update(payload).digest('hex')
}

export type PayoutIdempotencyStep = 'transfer' | 'payout' | 'payout-instant'

/**
 * Deterministic idempotency key for a cash-out's Stripe calls, keyed on OUR
 * row id: `payout-{payoutId}-transfer` / `payout-{payoutId}-payout`. A retried
 * request (or a crashed-and-replayed handler) for the same payouts row gets
 * Stripe's original object back instead of moving money a second time.
 *
 * The instant attempt gets its own key: when it fails we fall back to a
 * standard payout with different params, and Stripe rejects a reused key
 * whose params differ.
 */
export function payoutIdempotencyKey(
  ref: { kind: 'admin_payout'; payoutId: string } | { kind: 'job'; jobId: string },
  step: PayoutIdempotencyStep,
): string {
  const owner = ref.kind === 'admin_payout' ? `payout-${ref.payoutId}` : `job-${ref.jobId}`
  return `${owner}-${step}`
}
