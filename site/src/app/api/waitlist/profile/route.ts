import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { waitlistEntries } from '@/db/schema'
import { eq } from 'drizzle-orm'

export const runtime = 'nodejs'

/**
 * Waitlist Profile API
 *
 * Fetch profile by ID or referral code for sharing.
 */

export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url)
    const id = searchParams.get('id')
    const referralCode = searchParams.get('ref')

    if (!id && !referralCode) {
      return NextResponse.json(
        { success: false, error: 'Please provide id or ref parameter' },
        { status: 400 }
      )
    }

    let entry

    if (id) {
      // Fetch by ID
      const [found] = await db
        .select()
        .from(waitlistEntries)
        .where(eq(waitlistEntries.id, Number(id)))
        .limit(1)

      entry = found
    } else if (referralCode) {
      // Fetch by referral code
      const [found] = await db
        .select()
        .from(waitlistEntries)
        .where(eq(waitlistEntries.referralCode, referralCode.toUpperCase()))
        .limit(1)

      entry = found
    }

    if (!entry) {
      return NextResponse.json(
        { success: false, error: 'Profile not found' },
        { status: 404 }
      )
    }

    // Return public profile data (no sensitive info)
    return NextResponse.json({
      success: true,
      profile: {
        id: entry.id,
        name: entry.name,
        location: {
          country: entry.locationCountry,
          countryCode: entry.locationCountryCode,
          state: entry.locationState,
          stateCode: entry.locationStateCode,
          city: entry.locationCity,
          neighborhood: entry.locationNeighborhood,
        },
        rankings: {
          global: Number(entry.rankingsGlobal),
          country: Number(entry.rankingsCountry),
          state: Number(entry.rankingsState),
          city: Number(entry.rankingsCity),
          neighborhood: entry.rankingsNeighborhood
            ? Number(entry.rankingsNeighborhood)
            : null,
        },
        earlyAdopterTier: entry.earlyAdopterTier,
        referralCode: entry.referralCode,
        referralCount: Number(entry.referralCount) || 0,
        createdAt: entry.createdAt,
      },
    })
  } catch (error) {
    console.error('Waitlist profile error:', error)
    return NextResponse.json(
      { success: false, error: 'Failed to load profile' },
      { status: 500 }
    )
  }
}
