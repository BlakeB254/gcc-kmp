import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { sponsors } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { stripe, isStripeConfigured } from '@/lib/stripe/client'
import { hashPassword } from '@/lib/auth'
import {
  calculatePackagePrice,
  formatPrice,
  type SponsorPackageConfig,
} from '@/lib/sponsor-pricing'
import type Stripe from 'stripe'

export const runtime = 'nodejs'

/**
 * Custom Sponsor Package Checkout API
 *
 * Handles large custom sponsorship packages through Stripe.
 * Can process amounts from $100 to $999,999 per transaction.
 *
 * Key principles:
 * - $100 per block ALWAYS goes to worker (never discounted)
 * - Volume discounts only apply to add-on services
 * - Stripe handles secure payment processing
 */

interface PackageCheckoutRequest {
  // Package configuration
  config: SponsorPackageConfig

  // Sponsor info
  name: string
  email: string
  phone?: string
  company?: string
  notes?: string

  // Optional auth for account creation
  password?: string
}

export async function POST(request: NextRequest) {
  // Return demo response if Stripe is not configured
  if (!isStripeConfigured) {
    return NextResponse.json({
      checkoutUrl: '/sponsor/success?demo=true',
      sessionId: 'demo_session',
      demo: true,
    })
  }

  try {
    const body: PackageCheckoutRequest = await request.json()
    const { config, name, email, phone, company, notes, password } = body

    // Validation
    if (!name || !email) {
      return NextResponse.json(
        { error: 'Name and email are required' },
        { status: 400 }
      )
    }

    if (!config || !config.blockCount || config.blockCount < 1) {
      return NextResponse.json(
        { error: 'Invalid package configuration' },
        { status: 400 }
      )
    }

    // Calculate pricing
    const priceBreakdown = calculatePackagePrice(config)

    // Stripe has a max of $999,999.99 per transaction
    // For amounts over this, we'd need to split into multiple payments
    const MAX_STRIPE_AMOUNT = 99999999 // $999,999.99 in cents
    if (priceBreakdown.total > MAX_STRIPE_AMOUNT) {
      return NextResponse.json(
        {
          error: `Amount exceeds single transaction limit. Please contact us for packages over ${formatPrice(MAX_STRIPE_AMOUNT)}.`,
          contactRequired: true,
          total: priceBreakdown.total,
        },
        { status: 400 }
      )
    }

    // Create or find Stripe customer
    const customers = await stripe.customers.list({ email, limit: 1 })
    let customer = customers.data[0]

    if (!customer) {
      customer = await stripe.customers.create({
        email,
        name,
        phone: phone || undefined,
        metadata: {
          company: company || '',
          source: 'gcc-sponsor-package',
        },
      })
    }

    // Check if sponsor already exists
    const [existingSponsor] = await db
      .select({ id: sponsors.id })
      .from(sponsors)
      .where(eq(sponsors.email, email))
      .limit(1)

    let sponsorId: number

    if (existingSponsor) {
      sponsorId = existingSponsor.id
      // Update Stripe customer ID if not set
      await db
        .update(sponsors)
        .set({
          stripeCustomerId: customer.id,
          updatedAt: new Date().toISOString(),
        })
        .where(eq(sponsors.id, sponsorId))
    } else {
      // Create new sponsor
      const authFields = password
        ? hashPassword(password)
        : { hash: null, salt: null }

      const [newSponsor] = await db
        .insert(sponsors)
        .values({
          name,
          email,
          businessName: company,
          type: company ? 'business' : 'individual',
          stripeCustomerId: customer.id,
          preferencesIsAnonymous: false,
          hash: authFields.hash,
          salt: authFields.salt,
        })
        .returning({ id: sponsors.id })

      sponsorId = newSponsor.id
    }

    const appUrl = process.env.NEXT_PUBLIC_APP_URL || 'http://localhost:7300'

    // Build product name and description
    const isEnterprise = priceBreakdown.isEnterprise
    const tierName = priceBreakdown.enterpriseTier
      ? priceBreakdown.enterpriseTier.charAt(0).toUpperCase() +
        priceBreakdown.enterpriseTier.slice(1).replace(/([A-Z])/g, ' $1')
      : 'Custom'

    const productName = isEnterprise
      ? `${tierName} Sponsorship Package`
      : `Sponsorship Package - ${config.blockCount} Block${config.blockCount !== 1 ? 's' : ''}`

    // Build detailed description
    const descriptionParts = [
      `${config.blockCount} block${config.blockCount !== 1 ? 's' : ''} (${formatPrice(priceBreakdown.workerPayoutTotal)} to workers)`,
    ]

    if (priceBreakdown.addonsTotal > 0) {
      descriptionParts.push(`Add-ons: ${formatPrice(priceBreakdown.addonsTotal)}`)
    }

    if (config.isRecurring) {
      descriptionParts.push(`Recurring: ${config.recurringFrequency}`)
    }

    const productDescription = descriptionParts.join(' • ')

    // Serialize config for metadata (Stripe has 500 char limit per key)
    // Store essential info, full config can be retrieved via session ID
    const configSummary = JSON.stringify({
      blocks: config.blockCount,
      recurring: config.isRecurring ? config.recurringFrequency : null,
      hasAddons: priceBreakdown.addonsTotal > 0,
      workerPayout: priceBreakdown.workerPayoutTotal,
    })

    // Build success URL
    const successParams = new URLSearchParams({
      session_id: '{CHECKOUT_SESSION_ID}',
      type: 'package',
      blocks: String(config.blockCount),
    })

    // Determine recurring interval
    const getRecurringInterval = (): Stripe.Checkout.SessionCreateParams.LineItem.PriceData.Recurring.Interval => {
      if (config.recurringFrequency === 'annual') return 'year'
      return 'month'
    }

    // Build line items
    const lineItems: Stripe.Checkout.SessionCreateParams.LineItem[] = [
      {
        price_data: {
          currency: 'usd',
          unit_amount: priceBreakdown.total,
          product_data: {
            name: productName,
            description: productDescription,
            metadata: {
              type: 'sponsor_package',
              blockCount: String(config.blockCount),
              workerPayout: String(priceBreakdown.workerPayoutTotal),
            },
          },
          ...(config.isRecurring
            ? {
                recurring: {
                  interval: getRecurringInterval(),
                  interval_count: config.recurringFrequency === 'quarterly' ? 3 : 1,
                },
              }
            : {}),
        },
        quantity: 1,
      },
    ]

    // Create Stripe checkout session
    const sessionConfig: Stripe.Checkout.SessionCreateParams = {
      customer: customer.id,
      mode: config.isRecurring ? 'subscription' : 'payment',
      line_items: lineItems,
      success_url: `${appUrl}/sponsor/success?${successParams.toString()}`,
      cancel_url: `${appUrl}/sponsor/calculator?canceled=true`,
      metadata: {
        sponsorId: String(sponsorId),
        type: 'sponsor_package',
        blockCount: String(config.blockCount),
        workerPayoutTotal: String(priceBreakdown.workerPayoutTotal),
        addonsTotal: String(priceBreakdown.addonsTotal),
        configSummary,
        isRecurring: config.isRecurring ? 'true' : 'false',
        recurringFrequency: config.recurringFrequency || '',
        notes: notes || '',
        isEnterprise: isEnterprise ? 'true' : 'false',
        enterpriseTier: priceBreakdown.enterpriseTier || '',
      },
    }

    // For one-time payments, add payment_intent_data
    if (!config.isRecurring) {
      sessionConfig.payment_intent_data = {
        metadata: {
          sponsorId: String(sponsorId),
          type: 'sponsor_package',
          blockCount: String(config.blockCount),
          workerPayoutTotal: String(priceBreakdown.workerPayoutTotal),
        },
      }
    }

    const session = await stripe.checkout.sessions.create(sessionConfig)

    return NextResponse.json({
      checkoutUrl: session.url,
      sessionId: session.id,
      total: priceBreakdown.total,
      totalFormatted: formatPrice(priceBreakdown.total),
      workerPayout: priceBreakdown.workerPayoutTotal,
      workerPayoutFormatted: formatPrice(priceBreakdown.workerPayoutTotal),
      blockCount: config.blockCount,
      isEnterprise,
      enterpriseTier: priceBreakdown.enterpriseTier,
    })
  } catch (error) {
    console.error('Sponsor package checkout error:', error)
    return NextResponse.json(
      { error: 'Failed to create checkout session' },
      { status: 500 }
    )
  }
}
