import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { sql } from 'drizzle-orm'
import { requireSponsor } from '@/lib/sponsor-auth'

export const runtime = 'nodejs'

export async function GET(request: NextRequest) {
  try {
    const auth = await requireSponsor(request)

    if (!auth) {
      return NextResponse.json({ error: 'Not authenticated' }, { status: 401 })
    }
    const sponsorId = auth.id

    // Fetch the sponsor using raw SQL to avoid ORM issues
    const result = await db.execute(sql`
      SELECT
        id, name, email, type, phone,
        business_name, business_website,
        address_street, address_city, address_state, address_zip,
        preferences_frequency, preferences_is_anonymous, preferences_wants_updates,
        stripe_customer_id, stripe_subscription_id, stripe_default_payment_method,
        stats_total_contributed, stats_blocks_funded, stats_last_contribution_at,
        active_sponsorship,
        signup_rankings_global, early_adopter_tier, referral_code,
        created_at
      FROM sponsors
      WHERE id = ${sponsorId}
      LIMIT 1
    `)

    const rows = result.rows as any[]
    if (!rows || rows.length === 0) {
      return NextResponse.json({ error: 'Sponsor not found' }, { status: 404 })
    }

    const sponsor = rows[0]

    // Return sponsor data (excluding sensitive fields)
    return NextResponse.json({
      success: true,
      sponsor: {
        id: sponsor.id,
        name: sponsor.name,
        email: sponsor.email,
        type: sponsor.type,
        phone: sponsor.phone,
        business: sponsor.business_name
          ? {
              name: sponsor.business_name,
              website: sponsor.business_website,
            }
          : null,
        address: sponsor.address_street
          ? {
              street: sponsor.address_street,
              city: sponsor.address_city,
              state: sponsor.address_state,
              zip: sponsor.address_zip,
            }
          : null,
        preferences: {
          frequency: sponsor.preferences_frequency,
          isAnonymous: sponsor.preferences_is_anonymous,
          wantsUpdates: sponsor.preferences_wants_updates,
        },
        stripe: {
          customerId: sponsor.stripe_customer_id,
          subscriptionId: sponsor.stripe_subscription_id,
          hasPaymentMethod: !!sponsor.stripe_default_payment_method,
        },
        stats: {
          totalContributed: Number(sponsor.stats_total_contributed) || 0,
          blocksFunded: Number(sponsor.stats_blocks_funded) || 0,
          lastContributionAt: sponsor.stats_last_contribution_at,
        },
        activeSponsorship: sponsor.active_sponsorship || false,
        // Movement rankings
        signupRankings: {
          global: sponsor.signup_rankings_global ? Number(sponsor.signup_rankings_global) : null,
        },
        earlyAdopterTier: sponsor.early_adopter_tier || 'standard',
        referralCode: sponsor.referral_code,
        createdAt: sponsor.created_at,
      },
    })
  } catch (error) {
    // Log details server-side only — internal error messages must not leak
    // to clients.
    console.error('Get sponsor profile error:', error)
    return NextResponse.json({ error: 'Failed to get profile' }, { status: 500 })
  }
}
