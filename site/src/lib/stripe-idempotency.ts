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
