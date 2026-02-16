import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { waitlistEntries } from '@/db/schema'
import { and, eq, sql } from 'drizzle-orm'

export const runtime = 'nodejs'

/**
 * Check Area API
 *
 * Checks if GCC is active in a user's area.
 * Used to determine whether to show "Join Waitlist" or "Start Working" CTAs.
 *
 * Currently hardcoded: North Lawndale, Chicago is the only active service area.
 */

// Active service area configuration
const ACTIVE_AREAS = [
  {
    city: 'Chicago',
    stateCode: 'IL',
    neighborhoods: ['North Lawndale'],
    displayName: 'North Lawndale, Chicago',
    launchedAt: '2024-01-01T00:00:00.000Z',
  },
]

export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url)
    const city = searchParams.get('city')
    const stateCode = searchParams.get('stateCode')

    if (!city || !stateCode) {
      return NextResponse.json(
        { success: false, error: 'city and stateCode are required' },
        { status: 400 }
      )
    }

    // Check for active service area
    const activeArea = ACTIVE_AREAS.find(
      (area) =>
        area.city.toLowerCase() === city.toLowerCase() &&
        area.stateCode.toLowerCase() === stateCode.toLowerCase()
    )

    if (activeArea) {
      return NextResponse.json({
        success: true,
        isActive: true,
        area: {
          displayName: activeArea.displayName,
          launchedAt: activeArea.launchedAt,
          neighborhoods: activeArea.neighborhoods,
        },
      })
    }

    // Count waitlist entries in this area for social proof
    const [result] = await db
      .select({ count: sql<number>`count(*)` })
      .from(waitlistEntries)
      .where(
        and(
          eq(waitlistEntries.locationCity, city),
          eq(waitlistEntries.locationStateCode, stateCode)
        )
      )

    const waitlistCount = Number(result?.count) || 0

    return NextResponse.json({
      success: true,
      isActive: false,
      isComingSoon: false,
      waitlistCount,
      message: `${waitlistCount} ${waitlistCount === 1 ? 'person' : 'people'} in ${city} waiting for GCC`,
    })
  } catch (error) {
    console.error('Check area error:', error)
    return NextResponse.json(
      { success: false, error: 'Failed to check area' },
      { status: 500 }
    )
  }
}
