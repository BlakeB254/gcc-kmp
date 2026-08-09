import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { waitlistEntries, workers, sponsors } from '@/db/schema'
import { eq, sql, and } from 'drizzle-orm'
import { z } from 'zod'
import { sendEmail } from '@/lib/email'
import { waitlistConfirmationEmail } from '@/lib/email/templates/waitlist-confirmation'
import { getEarlyAdopterTier, generateReferralCode } from '@/lib/movement-rankings'
import {
  isChicagoAddress,
  getChicagoCommunityArea,
  getLocationKey,
  getLocationDisplayName,
} from '@/lib/chicago-community-areas'
import { commitPublicDataMutation } from '@/lib/public-data/invalidation'

export const runtime = 'nodejs'

const joinSchema = z.object({
  email: z.string().email('Please enter a valid email address'),
  name: z.string().optional(),
  phone: z.string().optional(),
  location: z.object({
    country: z.string().default('United States'),
    countryCode: z.string().default('US'),
    state: z.string().min(1, 'Please select a state'),
    stateCode: z.string().min(1, 'Please select a state'),
    city: z.string().min(1, 'Please enter your city'),
    neighborhood: z.string().optional(),
  }),
  referralCode: z.string().optional(),
  // New: track signup source (e.g., 'mobile-app', 'landing', 'referral')
  source: z.string().optional(),
  // New: track mobile app platform preference
  mobileAppPlatform: z.enum(['ios', 'android', 'both']).optional(),
  preferences: z
    .object({
      wantsToBeWorker: z.boolean().default(true),
      wantsToBeSponsor: z.boolean().default(false),
      wantsToVolunteer: z.boolean().default(false),
      emailUpdates: z.boolean().default(true),
    })
    .optional(),
})

export async function POST(request: NextRequest) {
  try {
    const body = await request.json()

    const result = joinSchema.safeParse(body)
    if (!result.success) {
      return NextResponse.json(
        { success: false, error: result.error.issues[0]?.message || 'Invalid input' },
        { status: 400 }
      )
    }

    const data = result.data

    // Check if email already exists
    const existing = await db
      .select({ id: waitlistEntries.id })
      .from(waitlistEntries)
      .where(eq(waitlistEntries.email, data.email))
      .limit(1)

    if (existing.length > 0) {
      return NextResponse.json(
        { success: false, error: 'This email is already on the waitlist' },
        { status: 400 }
      )
    }

    // Also check if they're already a worker
    const existingWorker = await db
      .select({ id: workers.id })
      .from(workers)
      .where(eq(workers.email, data.email))
      .limit(1)

    if (existingWorker.length > 0) {
      return NextResponse.json(
        { success: false, error: 'This email is already registered as a worker' },
        { status: 400 }
      )
    }

    // Calculate movement rankings
    const signupTimestamp = new Date()

    // Get total counts from all participant types
    const [{ waitlistCount }] = await db
      .select({ waitlistCount: sql<number>`count(*)` })
      .from(waitlistEntries)

    const [{ workersCount }] = await db
      .select({ workersCount: sql<number>`count(*)` })
      .from(workers)

    const [{ sponsorsCount }] = await db
      .select({ sponsorsCount: sql<number>`count(*)` })
      .from(sponsors)

    // Global rank includes all movement participants: waitlist + workers + sponsors
    const globalRank = Number(waitlistCount) + Number(workersCount) + Number(sponsorsCount) + 1

    // Determine location keys based on Chicago community areas
    const locationKeys = getLocationKey(
      data.location.city,
      data.location.stateCode,
      data.location.countryCode,
      data.location.neighborhood // This may contain zip code
    )

    // For Chicago addresses, try to get community area from zip
    const isChicago = isChicagoAddress(data.location.city, data.location.stateCode, data.location.neighborhood)
    const communityArea = data.location.neighborhood ? getChicagoCommunityArea(data.location.neighborhood) : null

    // Calculate location-specific ranks
    const [{ countryCount }] = await db
      .select({ countryCount: sql<number>`count(*)` })
      .from(waitlistEntries)
      .where(eq(waitlistEntries.locationCountryCode, data.location.countryCode))

    // Include workers and sponsors in country count for US
    let workersCountryCount = 0
    let sponsorsCountryCount = 0
    if (data.location.countryCode === 'US') {
      const [workersResult] = await db
        .select({ count: sql<number>`count(*)` })
        .from(workers)
      workersCountryCount = Number(workersResult.count)

      const [sponsorsResult] = await db
        .select({ count: sql<number>`count(*)` })
        .from(sponsors)
      sponsorsCountryCount = Number(sponsorsResult.count)
    }

    const [{ stateCount }] = await db
      .select({ stateCount: sql<number>`count(*)` })
      .from(waitlistEntries)
      .where(
        and(
          eq(waitlistEntries.locationCountryCode, data.location.countryCode),
          eq(waitlistEntries.locationStateCode, data.location.stateCode)
        )
      )

    const [{ workersStateCount }] = await db
      .select({ workersStateCount: sql<number>`count(*)` })
      .from(workers)
      .where(eq(workers.addressState, data.location.stateCode))

    // City ranking - for Chicago, this counts all Chicago community areas together
    const cityToMatch = isChicago ? 'Chicago' : data.location.city
    const [{ cityCount }] = await db
      .select({ cityCount: sql<number>`count(*)` })
      .from(waitlistEntries)
      .where(
        and(
          eq(waitlistEntries.locationStateCode, data.location.stateCode),
          isChicago
            ? sql`${waitlistEntries.locationCity} = 'Chicago' OR ${waitlistEntries.locationCity} IN (SELECT DISTINCT location_city FROM waitlist_entries WHERE location_state_code = 'IL' AND location_neighborhood IS NOT NULL)`
            : eq(waitlistEntries.locationCity, data.location.city)
        )
      )

    const [{ workersCityCount }] = await db
      .select({ workersCityCount: sql<number>`count(*)` })
      .from(workers)
      .where(
        isChicago
          ? eq(workers.addressCity, 'Chicago')
          : eq(workers.addressCity, data.location.city)
      )

    // Neighborhood ranking - only for Chicago community areas
    let neighborhoodRank: number | null = null
    if (isChicago && communityArea) {
      const [{ neighborhoodCount }] = await db
        .select({ neighborhoodCount: sql<number>`count(*)` })
        .from(waitlistEntries)
        .where(eq(waitlistEntries.locationNeighborhood, communityArea))

      // Count workers in this community area by zip code
      const [{ workersNeighborhoodCount }] = await db
        .select({ workersNeighborhoodCount: sql<number>`count(*)` })
        .from(workers)
        .where(
          sql`${workers.addressZip} IN (
            SELECT key FROM (VALUES ('60623'), ('60624'), ('60612'), ('60607'), ('60608'), ('60616'), ('60609'),
            ('60644'), ('60651'), ('60639'), ('60641'), ('60634'), ('60622'), ('60647'), ('60618'), ('60625'),
            ('60621'), ('60636'), ('60620'), ('60619'), ('60637'), ('60649'), ('60617'), ('60633'), ('60628'),
            ('60643'), ('60655'), ('60652'), ('60629'), ('60632'), ('60638'), ('60614'), ('60657'), ('60613'),
            ('60640'), ('60660'), ('60626'), ('60645'), ('60659'), ('60646'), ('60631'), ('60656'), ('60630'),
            ('60642'), ('60654'), ('60610'), ('60611'), ('60601'), ('60602'), ('60603'), ('60604'), ('60605'),
            ('60606'), ('60661'), ('60827'), ('60615'), ('60653')
            ) AS zips(key)
            WHERE key = ${workers.addressZip}
          ) AND ${workers.addressCity} = 'Chicago'`
        )

      neighborhoodRank = Number(neighborhoodCount) + Number(workersNeighborhoodCount) + 1
    }

    const rankings = {
      global: globalRank,
      country: Number(countryCount) + workersCountryCount + sponsorsCountryCount + 1,
      state: Number(stateCount) + Number(workersStateCount) + 1,
      city: Number(cityCount) + Number(workersCityCount) + 1,
      neighborhood: neighborhoodRank,
    }

    const earlyAdopterTier = getEarlyAdopterTier(globalRank)
    const referralCode = generateReferralCode()

    // Handle referral
    let referredById: number | null = null
    if (data.referralCode) {
      const referrer = await db
        .select({ id: waitlistEntries.id })
        .from(waitlistEntries)
        .where(eq(waitlistEntries.referralCode, data.referralCode))
        .limit(1)

      if (referrer.length > 0) {
        referredById = referrer[0].id
      }
    }

    // Determine display names for location
    const displayCity = isChicago ? 'Chicago' : data.location.city
    const displayNeighborhood = communityArea || data.location.neighborhood

    // Create waitlist entry
    const [entry] = await commitPublicDataMutation('supporter', () =>
      db.insert(waitlistEntries).values({
        email: data.email,
        name: data.name,
        phone: data.phone,
        locationCountry: data.location.country,
        locationCountryCode: data.location.countryCode,
        locationState: data.location.state,
        locationStateCode: data.location.stateCode,
        locationCity: displayCity,
        locationNeighborhood: displayNeighborhood,
        signupTimestamp: signupTimestamp.toISOString(),
        rankingsGlobal: String(rankings.global),
        rankingsCountry: String(rankings.country),
        rankingsState: String(rankings.state),
        rankingsCity: String(rankings.city),
        rankingsNeighborhood: rankings.neighborhood ? String(rankings.neighborhood) : null,
        earlyAdopterTier,
        referralCode,
        referredById,
        // Source tracking
        source: data.source,
        mobileAppPlatform: data.mobileAppPlatform,
        preferencesWantsToBeWorker: data.preferences?.wantsToBeWorker ?? true,
        preferencesWantsToBeSponsor: data.preferences?.wantsToBeSponsor ?? false,
        preferencesWantsToVolunteer: data.preferences?.wantsToVolunteer ?? false,
        preferencesEmailUpdates: data.preferences?.emailUpdates ?? true,
      }).returning(),
    )

    // Update referrer's count
    if (referredById) {
      await db
        .update(waitlistEntries)
        .set({
          referralCount: sql`COALESCE(${waitlistEntries.referralCount}, '0')::numeric + 1`,
        })
        .where(eq(waitlistEntries.id, referredById))
    }

    // Send confirmation email
    if (data.preferences?.emailUpdates !== false) {
      try {
        // For email, show community area name for Chicago, otherwise city
        const locationDisplayName = getLocationDisplayName(
          data.location.city,
          data.location.stateCode,
          data.location.countryCode,
          data.location.neighborhood
        )

        const emailContent = waitlistConfirmationEmail({
          name: data.name || 'Friend',
          email: data.email,
          globalRank: rankings.global,
          tier: earlyAdopterTier,
          referralCode,
          city: locationDisplayName,
          cityRank: isChicago && rankings.neighborhood ? rankings.neighborhood : rankings.city,
        })

        await sendEmail({
          to: data.email,
          subject: `You're #${rankings.global} in the GCC Movement!`,
          html: emailContent.html,
          text: emailContent.text,
        })
      } catch (emailError) {
        console.error('Failed to send confirmation email:', emailError)
      }
    }

    // Get display name for response
    const locationDisplayName = getLocationDisplayName(
      data.location.city,
      data.location.stateCode,
      data.location.countryCode,
      data.location.neighborhood
    )

    return NextResponse.json({
      success: true,
      entry: {
        id: entry.id,
        email: entry.email,
        name: entry.name,
        location: {
          country: entry.locationCountry,
          countryCode: entry.locationCountryCode,
          state: entry.locationState,
          stateCode: entry.locationStateCode,
          city: entry.locationCity,
          neighborhood: entry.locationNeighborhood,
          displayName: locationDisplayName,
          isChicago,
        },
        rankings: {
          ...rankings,
          // For Chicago, provide neighborhood-level rank as the local rank
          local: isChicago && rankings.neighborhood ? rankings.neighborhood : rankings.city,
          localName: isChicago && communityArea ? `${communityArea}, Chicago` : `${data.location.city}, ${data.location.stateCode}`,
        },
        earlyAdopterTier,
        referralCode,
      },
    })
  } catch (error) {
    console.error('Waitlist join error:', error)
    return NextResponse.json(
      { success: false, error: 'Failed to join waitlist. Please try again.' },
      { status: 500 }
    )
  }
}
