import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { sponsors, blocks } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { stripe, isStripeConfigured } from '@/lib/stripe/client'
import { hashPassword } from '@/lib/auth'

export const runtime = 'nodejs'

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
    const body = await request.json()

    const {
      blockId,
      sponsorType,
      name,
      email,
      company,
      isAnonymous,
      message,
      password,
      preferredCleaningDate,
      preferredCleaningTimeStart,
      preferredCleaningTimeEnd,
    } = body

    if (!blockId || !name || !email) {
      return NextResponse.json(
        { error: 'Block ID, name, and email are required' },
        { status: 400 }
      )
    }

    if (!preferredCleaningDate) {
      return NextResponse.json(
        { error: 'Please select a preferred cleaning date' },
        { status: 400 }
      )
    }

    // Verify block exists and is unfunded
    const [block] = await db
      .select()
      .from(blocks)
      .where(eq(blocks.id, Number(blockId)))
      .limit(1)

    if (!block) {
      return NextResponse.json({ error: 'Block not found' }, { status: 404 })
    }

    if (block.status !== 'available') {
      return NextResponse.json(
        { error: 'This block has already been funded' },
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
        metadata: {
          company: company || '',
          source: 'gcc-sponsor',
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

    // Create Stripe checkout session
    const session = await stripe.checkout.sessions.create({
      customer: customer.id,
      mode: sponsorType === 'monthly' ? 'subscription' : 'payment',
      line_items: [
        {
          price_data: {
            currency: 'usd',
            unit_amount: 10000, // $100 in cents
            product_data: {
              name: `Block Sponsorship: ${block.name}`,
              description: block.description || 'Block cleaning sponsorship',
              metadata: {
                blockId: String(blockId),
              },
            },
            ...(sponsorType === 'monthly' ? { recurring: { interval: 'month' } } : {}),
          },
          quantity: 1,
        },
      ],
      success_url: `${appUrl}/sponsor/success?session_id={CHECKOUT_SESSION_ID}&block=${block.id}`,
      cancel_url: `${appUrl}/sponsor?canceled=true`,
      metadata: {
        blockId: String(blockId),
        sponsorId: String(sponsorId),
        sponsorType,
        message: message || '',
        isAnonymous: isAnonymous ? 'true' : 'false',
        displayName: name,
        // Cleaning schedule
        preferredCleaningDate,
        preferredCleaningTimeStart: preferredCleaningTimeStart || '',
        preferredCleaningTimeEnd: preferredCleaningTimeEnd || '',
      },
      payment_intent_data:
        sponsorType !== 'monthly'
          ? {
              metadata: {
                blockId: String(blockId),
                sponsorId: String(sponsorId),
              },
            }
          : undefined,
    })

    return NextResponse.json({
      checkoutUrl: session.url,
      sessionId: session.id,
    })
  } catch (error) {
    console.error('Sponsor checkout error:', error)
    return NextResponse.json(
      { error: 'Failed to create checkout session' },
      { status: 500 }
    )
  }
}
