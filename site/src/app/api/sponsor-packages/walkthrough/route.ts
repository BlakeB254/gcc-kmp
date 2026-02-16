import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { sponsors } from '@/db/schema'
import { eq } from 'drizzle-orm'

export const runtime = 'nodejs'

/**
 * Sponsor Package Walkthrough Request API
 *
 * Allows potential sponsors to request a consultation call before committing
 * to large sponsorship packages. This builds trust and allows for custom
 * package configurations.
 */

interface WalkthroughRequest {
  // Package configuration summary
  config: {
    blockCount: number
    isRecurring: boolean
    recurringFrequency?: string
  }

  // Price breakdown summary
  priceBreakdown: {
    total: number
    workerPayoutTotal: number
    addonsTotal: number
    isEnterprise: boolean
    enterpriseTier: string | null
  }

  // Contact info
  name: string
  email: string
  phone?: string
  company?: string
  notes?: string
}

export async function POST(request: NextRequest) {
  try {
    const body: WalkthroughRequest = await request.json()
    const { config, priceBreakdown, name, email, phone, company, notes } = body

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

    // Check if sponsor already exists
    const [existingSponsor] = await db
      .select({ id: sponsors.id, email: sponsors.email })
      .from(sponsors)
      .where(eq(sponsors.email, email.toLowerCase()))
      .limit(1)

    let sponsorId: number

    if (existingSponsor) {
      sponsorId = existingSponsor.id
    } else {
      // Create new sponsor record (without payment, just for tracking)
      const [newSponsor] = await db
        .insert(sponsors)
        .values({
          name,
          email: email.toLowerCase(),
          businessName: company,
          type: company ? 'business' : 'individual',
          preferencesIsAnonymous: false,
        })
        .returning({ id: sponsors.id })

      sponsorId = newSponsor.id
    }

    // In a production app, you would:
    // 1. Store the walkthrough request in a dedicated table
    // 2. Send an email notification to the sales team
    // 3. Send a confirmation email to the requester
    // 4. Create a CRM record or calendar invite

    // For now, we'll log the request and return success
    console.log('Walkthrough request received:', {
      sponsorId,
      name,
      email,
      phone,
      company,
      config,
      priceBreakdown,
      notes,
      requestedAt: new Date().toISOString(),
    })

    // TODO: Integrate with email service (Resend) to send notifications
    // TODO: Create a walkthrough_requests table to track these

    return NextResponse.json({
      success: true,
      message: 'Walkthrough request submitted successfully',
      sponsorId,
      estimatedResponseTime: '24 hours',
    })
  } catch (error) {
    console.error('Walkthrough request error:', error)
    return NextResponse.json(
      { error: 'Failed to submit walkthrough request' },
      { status: 500 }
    )
  }
}
