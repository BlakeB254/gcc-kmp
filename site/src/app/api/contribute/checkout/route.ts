import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { sponsors, blocks } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { stripe, isStripeConfigured, STRIPE_CONFIG } from '@/lib/stripe/client'
import { hashPassword } from '@/lib/auth'

export const runtime = 'nodejs'

/**
 * Unified Contribution Checkout API
 *
 * Handles both:
 * - General donations (any amount, no block selection)
 * - Block sponsorships ($100/block, specific block selection)
 * - Area sponsorships ($100/block, auto-assigned blocks)
 *
 * The distinction is made by allocationType and blockIds:
 * - blockIds provided → Block sponsorship (specific blocks)
 * - allocationType = 'area' → Area sponsorship (auto-assign)
 * - Neither → General donation
 */

interface ContributeRequest {
  // Contribution details
  amount?: number // In dollars (for donations) - ignored for block sponsorships
  blockIds?: number[] // Array of block IDs to sponsor (each $100)
  frequency: 'one_time' | 'monthly'

  // Area mode (auto-assign blocks)
  allocationType?: 'block' | 'area' | 'general'
  blockCount?: number // For area mode - how many blocks to auto-assign

  // Supporter info
  name: string
  email: string
  company?: string

  // Display preferences
  displayName?: string // Name to show publicly (defaults to name)
  isAnonymous?: boolean // Hide name on support wall
  message?: string // Optional supporter message

  // Cleaning schedule (optional)
  preferredCleaningDate?: string // ISO date
  preferredCleaningTimeStart?: string // HH:MM
  preferredCleaningTimeEnd?: string // HH:MM

  // Optional auth
  password?: string
}

export async function POST(request: NextRequest) {
  // Return demo response if Stripe is not configured
  if (!isStripeConfigured) {
    return NextResponse.json({
      checkoutUrl: '/support/success?demo=true',
      sessionId: 'demo_session',
      demo: true,
    })
  }

  try {
    const body: ContributeRequest = await request.json()
    const {
      amount,
      blockIds,
      frequency,
      allocationType: requestedAllocationType,
      blockCount: requestedBlockCount,
      name,
      email,
      company,
      displayName,
      isAnonymous,
      message,
      preferredCleaningDate,
      preferredCleaningTimeStart,
      preferredCleaningTimeEnd,
      password,
    } = body

    // Validation
    if (!name || !email) {
      return NextResponse.json(
        { error: 'Name and email are required' },
        { status: 400 }
      )
    }

    // Determine contribution type
    const isSpecificBlockSponsorship = blockIds && blockIds.length > 0
    const isAreaSponsorship = requestedAllocationType === 'area' && requestedBlockCount && requestedBlockCount > 0

    let allocationType: 'block' | 'area' | 'general'
    if (isSpecificBlockSponsorship) {
      allocationType = 'block'
    } else if (isAreaSponsorship) {
      allocationType = 'area'
    } else {
      allocationType = 'general'
    }

    // Calculate amount and get blocks
    let totalAmountCents: number
    let blocksToFund: typeof blocks.$inferSelect[] = []
    let finalBlockIds: number[] = []

    if (isSpecificBlockSponsorship) {
      // Block sponsorship: $100 per block, specific blocks selected
      const availableBlocks = await db
        .select()
        .from(blocks)
        .where(eq(blocks.status, 'available'))

      // Filter to only requested blocks that are available
      blocksToFund = availableBlocks.filter(b => blockIds.includes(b.id))

      if (blocksToFund.length === 0) {
        return NextResponse.json(
          { error: 'No available blocks found with the provided IDs' },
          { status: 400 }
        )
      }

      if (blocksToFund.length !== blockIds.length) {
        return NextResponse.json(
          { error: 'Some blocks are no longer available for sponsorship' },
          { status: 400 }
        )
      }

      finalBlockIds = blocksToFund.map(b => b.id)
      totalAmountCents = blocksToFund.length * STRIPE_CONFIG.standardJobAmount
    } else if (isAreaSponsorship) {
      // Area sponsorship: $100 per block, auto-assign available blocks
      const availableBlocks = await db
        .select()
        .from(blocks)
        .where(eq(blocks.status, 'available'))

      if (availableBlocks.length < requestedBlockCount) {
        return NextResponse.json(
          { error: `Only ${availableBlocks.length} blocks available. Please select ${availableBlocks.length} or fewer.` },
          { status: 400 }
        )
      }

      // Auto-assign the requested number of blocks (take first N available)
      blocksToFund = availableBlocks.slice(0, requestedBlockCount)
      finalBlockIds = blocksToFund.map(b => b.id)
      totalAmountCents = blocksToFund.length * STRIPE_CONFIG.standardJobAmount
    } else {
      // General donation: use provided amount
      if (!amount || amount < 1) {
        return NextResponse.json(
          { error: 'Amount must be at least $1 for donations' },
          { status: 400 }
        )
      }
      totalAmountCents = Math.round(amount * 100)
    }

    // Create or find Stripe customer
    const customers = await stripe.customers.list({ email, limit: 1 })
    let customer = customers.data[0]

    if (!customer) {
      customer = await stripe.customers.create({
        email,
        name,
        metadata: {
          company: company || '',
          source: 'gcc-contribute',
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
          preferencesIsAnonymous: isAnonymous || false,
          hash: authFields.hash,
          salt: authFields.salt,
        })
        .returning({ id: sponsors.id })

      sponsorId = newSponsor.id
    }

    const appUrl = process.env.NEXT_PUBLIC_APP_URL || 'http://localhost:7300'

    // Build product name and description
    let productName: string
    let productDescription: string

    if (allocationType === 'block' || allocationType === 'area') {
      const blockNames = blocksToFund.map(b => b.name).join(', ')
      const modeLabel = allocationType === 'area' ? 'Area ' : ''
      productName = blocksToFund.length === 1
        ? `${modeLabel}Block Sponsorship: ${blocksToFund[0].name}`
        : `${modeLabel}Block Sponsorship: ${blocksToFund.length} blocks`
      productDescription = `Sponsoring: ${blockNames}`
    } else {
      productName = 'General Fund Donation'
      productDescription = 'Supporting community cleaning where it\'s needed most'
    }

    // Build success URL
    const successParams = new URLSearchParams({
      session_id: '{CHECKOUT_SESSION_ID}',
      type: allocationType,
    })
    if (finalBlockIds.length > 0) {
      successParams.set('blocks', finalBlockIds.join(','))
    }

    // Create Stripe checkout session
    const session = await stripe.checkout.sessions.create({
      customer: customer.id,
      mode: frequency === 'monthly' ? 'subscription' : 'payment',
      line_items: [
        {
          price_data: {
            currency: 'usd',
            unit_amount: totalAmountCents,
            product_data: {
              name: productName,
              description: productDescription,
              metadata: {
                allocationType,
                blockIds: finalBlockIds.join(','),
              },
            },
            ...(frequency === 'monthly' ? { recurring: { interval: 'month' } } : {}),
          },
          quantity: 1,
        },
      ],
      success_url: `${appUrl}/donate/success?${successParams.toString()}`,
      cancel_url: `${appUrl}/donate?canceled=true`,
      metadata: {
        sponsorId: String(sponsorId),
        allocationType,
        blockIds: finalBlockIds.join(','),
        frequency,
        displayName: displayName || name,
        isAnonymous: isAnonymous ? 'true' : 'false',
        message: message || '',
        // Cleaning schedule (optional)
        preferredCleaningDate: preferredCleaningDate || '',
        preferredCleaningTimeStart: preferredCleaningTimeStart || '',
        preferredCleaningTimeEnd: preferredCleaningTimeEnd || '',
      },
      payment_intent_data:
        frequency !== 'monthly'
          ? {
              metadata: {
                sponsorId: String(sponsorId),
                allocationType,
                blockIds: finalBlockIds.join(','),
              },
            }
          : undefined,
    })

    return NextResponse.json({
      checkoutUrl: session.url,
      sessionId: session.id,
      allocationType,
      totalAmount: totalAmountCents / 100,
      blockCount: blocksToFund.length,
      // For area mode, return assigned block info so sponsor knows what they're getting
      assignedBlocks: allocationType === 'area'
        ? blocksToFund.map(b => ({
            id: b.id,
            name: b.name,
            streetName: b.streetName,
            fromCorner: b.fromCorner,
            toCorner: b.toCorner,
          }))
        : undefined,
    })
  } catch (error) {
    console.error('Contribution checkout error:', error)
    return NextResponse.json(
      { error: 'Failed to create checkout session' },
      { status: 500 }
    )
  }
}
