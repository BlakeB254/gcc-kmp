import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { sponsors, blocks } from '@/db/schema'
import { eq, inArray } from 'drizzle-orm'
import { stripe, isStripeConfigured, STRIPE_CONFIG } from '@/lib/stripe/client'
import { hashPassword } from '@/lib/auth'
import { stripeIdempotencyKey } from '@/lib/stripe-idempotency'
import { appUrl as getAppUrl } from '@/lib/env'
import { codesWithin, parseReservation, stripeRecurring, type Reservation } from '@/lib/sponsor-checkout'

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
 *
 * A `reservation` is a block sponsorship made while no block is ready to
 * assign: $100 a block, taken through the general-donation branch (no block
 * row is touched) and tagged in the Stripe metadata as `reservedBlockCount` /
 * `reservedBlockCodes` so it can be allocated when blocks are scheduled.
 */

interface ContributeRequest {
  // Contribution details
  amount?: number // In dollars (for donations) - ignored for block sponsorships
  blockIds?: number[] // Array of block IDs to sponsor (each $100)
  frequency: 'one_time' | 'weekly' | 'biweekly' | 'monthly'

  // Area mode (auto-assign blocks)
  allocationType?: 'block' | 'area' | 'general'
  blockCount?: number // For area mode - how many blocks to auto-assign

  // Block sponsorship for the next available cycle (see lib/sponsor-checkout.ts)
  reservation?: Reservation

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
    let reservation: ReturnType<typeof parseReservation> = null

    if (isSpecificBlockSponsorship) {
      // Block sponsorship: $100 per block, specific blocks selected
      const availableBlocks = await db
        .select()
        .from(blocks)
        .where(eq(blocks.status, 'available'))

      // Filter to only requested blocks that are available
      blocksToFund = availableBlocks.filter(b => blockIds.includes(b.id))

      if (blocksToFund.length === blockIds.length) {
        finalBlockIds = blocksToFund.map(b => b.id)
        totalAmountCents = blocksToFund.length * STRIPE_CONFIG.standardJobAmount
      } else {
        // One or more of the chosen blocks is not open right now (it may have
        // closed since the page loaded). Every block is sponsorable at any
        // time, so this is a reservation for those same blocks, not an error.
        const requested = await db
          .select({ id: blocks.id, code: blocks.code })
          .from(blocks)
          .where(inArray(blocks.id, blockIds))
        if (requested.length !== new Set(blockIds).size) {
          return NextResponse.json(
            { error: 'One or more of the selected blocks could not be found' },
            { status: 400 }
          )
        }
        reservation = parseReservation({
          blockCount: requested.length,
          blockCodes: requested.map(b => b.code).filter((c): c is string => !!c),
        })
        if (!reservation) {
          return NextResponse.json({ error: 'Invalid block reservation' }, { status: 400 })
        }
        blocksToFund = []
        allocationType = 'general'
        totalAmountCents = reservation.amountCents
      }
    } else if (isAreaSponsorship) {
      // Area sponsorship: $100 per block, auto-assign available blocks
      const availableBlocks = await db
        .select()
        .from(blocks)
        .where(eq(blocks.status, 'available'))

      if (availableBlocks.length < requestedBlockCount) {
        // Not enough open blocks to assign now: reserve the next ones instead.
        reservation = parseReservation({ blockCount: requestedBlockCount })
        if (!reservation) {
          return NextResponse.json({ error: 'Invalid block reservation' }, { status: 400 })
        }
        allocationType = 'general'
        totalAmountCents = reservation.amountCents
      } else {
        // Auto-assign the requested number of blocks (take first N available)
        blocksToFund = availableBlocks.slice(0, requestedBlockCount)
        finalBlockIds = blocksToFund.map(b => b.id)
        totalAmountCents = blocksToFund.length * STRIPE_CONFIG.standardJobAmount
      }
    } else if (body.reservation !== undefined) {
      // Reservation: priced here from the block count, never from the client
      reservation = parseReservation(body.reservation)
      if (!reservation) {
        return NextResponse.json(
          { error: 'Invalid block reservation' },
          { status: 400 }
        )
      }
      totalAmountCents = reservation.amountCents
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

    const appUrl = getAppUrl()

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
    } else if (reservation) {
      const n = reservation.blockCount
      productName = `Block Sponsorship: ${n} block${n === 1 ? '' : 's'} (next available)`
      productDescription = reservation.blockCodes.length > 0
        ? `Reserved: ${reservation.blockCodes.join(', ')}`.slice(0, 300)
        : 'GCC assigns the next blocks scheduled for cleaning'
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

    const idempotencyKey = stripeIdempotencyKey({
      endpoint: 'contribute/checkout',
      sponsorId,
      allocationType,
      blockIds: finalBlockIds,
      // A reservation has no block ids, so without this a reservation for
      // block A, one for block B, and a plain $100 donation all share a key —
      // and Stripe rejects the second with an idempotency error.
      reservation: reservation ? { n: reservation.blockCount, codes: reservation.blockCodes } : null,
      totalAmountCents,
      frequency,
    })

    // Weekly, every-two-weeks and monthly sponsorships are subscriptions on
    // that schedule. (Weekly and biweekly used to be charged once.)
    const recurring = stripeRecurring(frequency)

    // Create Stripe checkout session
    const session = await stripe.checkout.sessions.create({
      customer: customer.id,
      mode: recurring ? 'subscription' : 'payment',
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
            ...(recurring ? { recurring } : {}),
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
        // Set only for a reservation; Stripe metadata values cap at 500 chars
        reservedBlockCount: reservation ? String(reservation.blockCount) : '',
        reservedBlockCodes: reservation ? codesWithin(reservation.blockCodes, 480) : '',
        // Cleaning schedule (optional)
        preferredCleaningDate: preferredCleaningDate || '',
        preferredCleaningTimeStart: preferredCleaningTimeStart || '',
        preferredCleaningTimeEnd: preferredCleaningTimeEnd || '',
      },
      // A renewal invoice carries the subscription's metadata, not the
      // session's — this is what lets the webhook record each later payment.
      subscription_data: recurring
        ? {
            metadata: {
              sponsorId: String(sponsorId),
              allocationType,
              frequency,
              displayName: displayName || name,
              isAnonymous: isAnonymous ? 'true' : 'false',
              // Every renewal is a cleaning owed. For a reservation that is the
              // reservation again; for a block or area sponsorship the blocks
              // were funded once at checkout, so each later payment reserves
              // the same blocks' next cleaning. (Session metadata is left
              // alone, so the first payment still funds the blocks.)
              reservedBlockCount: String(reservation ? reservation.blockCount : blocksToFund.length || ''),
              reservedBlockCodes: codesWithin(
                reservation
                  ? reservation.blockCodes
                  : blocksToFund.map(b => b.code).filter((c): c is string => !!c),
                480,
              ),
            },
          }
        : undefined,
      payment_intent_data:
        !recurring
          ? {
              metadata: {
                sponsorId: String(sponsorId),
                allocationType,
                blockIds: finalBlockIds.join(','),
              },
            }
          : undefined,
    }, { idempotencyKey })

    return NextResponse.json({
      checkoutUrl: session.url,
      sessionId: session.id,
      allocationType,
      totalAmount: totalAmountCents / 100,
      blockCount: reservation ? reservation.blockCount : blocksToFund.length,
      reserved: reservation !== null,
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
