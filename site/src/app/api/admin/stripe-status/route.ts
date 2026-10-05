import { NextResponse } from 'next/server'
import { requireAdmin } from '@/lib/admin-auth'
import { stripe, isStripeConfigured } from '@/lib/stripe/client'
import { stripeKeyMode } from '@/lib/stripe/key-mode'

export const runtime = 'nodejs'

/**
 * Which Stripe account is this deployment taking money on?
 *
 * Every sponsor and donation checkout uses STRIPE_SECRET_KEY. This asks Stripe
 * who that key belongs to, so an admin can confirm the site is on the
 * organization's own account, in live mode, able to charge — without anyone
 * opening the hosting dashboard. It returns names and flags only; no key or
 * part of a key ever leaves the server.
 */
export async function GET(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const secretMode = stripeKeyMode(process.env.STRIPE_SECRET_KEY)
  const publishableMode = stripeKeyMode(process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY)
  const base = {
    configured: isStripeConfigured,
    secretKeyMode: secretMode,
    publishableKeyMode: publishableMode,
    // A live secret key with a test publishable key (or the reverse) breaks checkout.
    keysMatch: secretMode === publishableMode || publishableMode === 'missing',
    webhookSecretSet: !!process.env.STRIPE_WEBHOOK_SECRET,
  }

  if (!isStripeConfigured) {
    return NextResponse.json({ ...base, account: null, error: 'STRIPE_SECRET_KEY is not set' })
  }

  try {
    // With no id, this returns the account the secret key belongs to.
    const account = await stripe.accounts.retrieve()
    return NextResponse.json({
      ...base,
      account: {
        id: account.id,
        name:
          account.settings?.dashboard?.display_name ||
          account.business_profile?.name ||
          null,
        email: account.email ?? null,
        country: account.country ?? null,
        chargesEnabled: account.charges_enabled,
        payoutsEnabled: account.payouts_enabled,
        detailsSubmitted: account.details_submitted,
      },
      error: null,
    })
  } catch (err) {
    return NextResponse.json({
      ...base,
      account: null,
      error: err instanceof Error ? err.message : 'Could not reach Stripe',
    })
  }
}
