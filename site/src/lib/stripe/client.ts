import Stripe from 'stripe'

// Use placeholder for demo/build - real key required for production
const stripeKey = process.env.STRIPE_SECRET_KEY || 'sk_test_placeholder'

export const stripe = new Stripe(stripeKey)

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
