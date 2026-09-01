import Stripe from 'stripe'

// Fails fast in prod when missing (same doctrine as lib/env.ts); the dev
// placeholder only lets env-less local/CI work import this module — any
// actual Stripe call with it still fails at the API. `next build` evaluates
// route modules with NODE_ENV=production while collecting page data, so the
// build phase (NEXT_PHASE) is exempt — the throw fires at production
// runtime module load (lambda cold start), not during an env-less build.
function readStripeKey(): string {
  const value = process.env.STRIPE_SECRET_KEY
  if (value) return value
  const isBuildPhase = process.env.NEXT_PHASE === 'phase-production-build'
  if (process.env.NODE_ENV === 'production' && !isBuildPhase) {
    throw new Error('STRIPE_SECRET_KEY environment variable must be set in production')
  }
  if (process.env.NODE_ENV !== 'test') {
    console.warn('[env] STRIPE_SECRET_KEY is not set — using dev placeholder')
  }
  return 'sk_test_placeholder'
}

export const stripe = new Stripe(readStripeKey())

// Flag to check if Stripe is properly configured
export const isStripeConfigured = !!process.env.STRIPE_SECRET_KEY

/**
 * Stripe Connect configuration for GCC
 *
 * We use Express accounts because:
 * 1. Workers complete identity verification through Stripe (less admin burden)
 * 2. Instant payouts are supported
 * 3. Workers can manage their own banking details
 * 4. Stripe handles compliance
 */
export const STRIPE_CONFIG = {
  // Platform fee is 0% for nonprofit - all money goes to workers
  applicationFeePercent: 0,

  // Default payout speed
  defaultPayoutSpeed: 'instant' as const,

  // Instant payout fee (charged to platform, not worker)
  instantPayoutFeePercent: 1,

  // Standard job payout amount in cents ($100)
  standardJobAmount: 10000,
} as const
