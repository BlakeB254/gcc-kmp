export type StripeKeyMode = 'live' | 'test' | 'missing' | 'unknown'

/**
 * Whether a Stripe key is a live or a test key, from its public prefix
 * (sk_live_, rk_test_, pk_live_, ...). Reads nothing else of the key.
 */
export function stripeKeyMode(key: string | undefined): StripeKeyMode {
  if (!key) return 'missing'
  if (/^(sk|rk|pk)_live_/.test(key)) return 'live'
  if (/^(sk|rk|pk)_test_/.test(key)) return 'test'
  return 'unknown'
}
