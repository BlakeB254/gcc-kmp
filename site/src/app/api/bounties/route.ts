import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { bounties, communityReports, worldObservations } from '@/db/schema'
import { and, desc, eq, gt, isNull, or, sql } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { BOUNTY_MINIMUM_CENTS, bountyRowToApiBounty, observationToBountySource, reportToBountySource, resolveBountyTrigger } from './_derived'

export const dynamic = 'force-dynamic'

export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url)
    const status = searchParams.get('status')
    const limit = Math.min(parseInt(searchParams.get('limit') || '50', 10), 100)

    if (status && status !== 'open') {
      return NextResponse.json({ data: [], success: true, message: null })
    }

    const fundedBounties = await db
      .select()
      .from(bounties)
      .where(
        and(
          eq(bounties.status, 'open'),
          eq(bounties.visibility, 'public'),
          // Amendment 2 / Task 1b, Change 3: a bounty now has a trigger
          // (report OR observation) rather than always a report.
          sql`(${bounties.triggerReportId} IS NOT NULL OR ${bounties.triggerObservationId} IS NOT NULL)`,
          sql`${bounties.amount}::numeric >= ${BOUNTY_MINIMUM_CENTS}`,
        ),
      )
      .orderBy(desc(bounties.updatedAt))
      .limit(Number.isFinite(limit) && limit > 0 ? limit : 50)

    const funded = fundedBounties
      .map(bountyRowToApiBounty)
      .filter((bounty) => (
        (bounty.triggerReportId != null || bounty.triggerObservationId != null) &&
        bounty.amount >= BOUNTY_MINIMUM_CENTS &&
        Number.isFinite(bounty.locationLat) &&
        Number.isFinite(bounty.locationLng)
      ))

    return NextResponse.json({ data: funded, success: true, message: null })
  } catch (error) {
    console.error('Error fetching bounties:', error)
    return NextResponse.json({ data: [], success: true, message: null })
  }
}

export async function POST(request: NextRequest) {
  try {
    const auth = await requireMobileAuth(request)
    if (!auth) {
      return NextResponse.json({ data: null, success: false, message: 'Authorization required' }, { status: 401 })
    }

    const body = await request.json()
    const amount = Number(body.amount)
    const title = typeof body.title === 'string' ? body.title.trim() : ''
    const description = typeof body.description === 'string' ? body.description.trim() : null
    const visibility = body.visibility === 'secret' ? 'secret' : 'public'
    const isAnonymous = body.isAnonymous === true
    const posterName = typeof body.posterName === 'string' ? body.posterName.trim() : null

    // Amendment 2 / Task 1b, Change 3: a bounty can be seeded from EITHER a
    // communityReports row OR a worldObservations row (bounty continuity —
    // scan submissions no longer produce reports to trigger from). Exactly
    // one of the two ids must be present — validated by the pure,
    // independently-tested resolveBountyTrigger helper.
    const trigger = resolveBountyTrigger(body)
    if (!trigger.ok) {
      return NextResponse.json({ data: null, success: false, message: trigger.message }, { status: 400 })
    }
    const { triggerReportId, triggerObservationId } = trigger
    const hasTriggerReportId = triggerReportId != null
    const hasTriggerObservationId = triggerObservationId != null

    if (!Number.isFinite(amount) || amount < 1000 || !Number.isInteger(amount)) {
      return NextResponse.json(
        { data: null, success: false, message: 'Minimum bounty amount is $10' },
        { status: 400 },
      )
    }
    if (!title) {
      return NextResponse.json(
        { data: null, success: false, message: 'Bounty title is required' },
        { status: 400 },
      )
    }
    if (!isAnonymous && !posterName) {
      return NextResponse.json(
        { data: null, success: false, message: 'Poster name is required unless anonymous' },
        { status: 400 },
      )
    }

    let report: typeof communityReports.$inferSelect | null = null
    let source: ReturnType<typeof reportToBountySource> | ReturnType<typeof observationToBountySource> | null = null

    if (hasTriggerReportId) {
      const [foundReport] = await db
        .select()
        .from(communityReports)
        .where(
          and(
            eq(communityReports.id, triggerReportId as number),
            or(
              eq(communityReports.status, 'pending'),
              eq(communityReports.status, 'verified'),
              eq(communityReports.status, 'acknowledged'),
            ),
            or(
              isNull(communityReports.mapExpiresAt),
              gt(communityReports.mapExpiresAt, new Date().toISOString()),
            ),
          ),
        )
        .limit(1)

      report = foundReport ?? null
      source = report ? reportToBountySource(report) : null
      if (!report || !source) {
        return NextResponse.json(
          { data: null, success: false, message: 'Spot report is not available for bounty funding' },
          { status: 404 },
        )
      }
    } else {
      const [observation] = await db
        .select()
        .from(worldObservations)
        .where(
          and(
            eq(worldObservations.id, triggerObservationId as number),
            eq(worldObservations.isCurrent, true),
            eq(worldObservations.state, 'dirty'),
            eq(worldObservations.excludedPrivateProperty, false),
          ),
        )
        .limit(1)

      source = observation ? observationToBountySource(observation) : null
      if (!observation || !source) {
        return NextResponse.json(
          { data: null, success: false, message: 'Observation is not available for bounty funding' },
          { status: 404 },
        )
      }
    }

    const [existing] = await db
      .select()
      .from(bounties)
      .where(
        and(
          hasTriggerReportId
            ? eq(bounties.triggerReportId, triggerReportId as number)
            : eq(bounties.triggerObservationId, triggerObservationId as number),
          eq(bounties.status, 'open'),
        ),
      )
      .limit(1)

    if (existing) {
      const [updated] = await db
        .update(bounties)
        .set({
          amount: sql`${bounties.amount}::numeric + ${amount}`,
          pledgeCount: sql`COALESCE(${bounties.pledgeCount}, 0) + 1`,
          updatedAt: new Date().toISOString(),
        })
        .where(eq(bounties.id, existing.id))
        .returning()

      return NextResponse.json({ data: bountyRowToApiBounty(updated), success: true, message: null })
    }

    const [created] = await db
      .insert(bounties)
      .values({
        posterType: 'worker',
        posterId: auth.workerId,
        walletId: 0,
        title,
        description: description || source.description || null,
        amount: String(amount),
        status: 'open',
        visibility,
        isAnonymous,
        posterName: isAnonymous ? null : posterName,
        locationLat: source.locationLat,
        locationLng: source.locationLng,
        radiusMeters: String(source.radiusMeters),
        city: source.city,
        communityArea: source.communityArea,
        neighborhood: source.neighborhood,
        triggerReportId,
        triggerObservationId,
        // worldObservations has no reporter/worker column — the observation
        // path leaves observerId null (Amendment 2 / Task 1b, Change 3).
        observerId: report ? report.reporterId : null,
        mediaUrls: source.mediaUrls.length > 0 ? source.mediaUrls : null,
        aiAnalysis: source.aiAnalysis,
        expiresAt: report ? report.mapExpiresAt : null,
      })
      .returning()

    return NextResponse.json({ data: bountyRowToApiBounty(created), success: true, message: null })
  } catch (error) {
    console.error('Error creating spot bounty:', error)
    return NextResponse.json(
      { data: null, success: false, message: 'Failed to post bounty' },
      { status: 500 },
    )
  }
}
