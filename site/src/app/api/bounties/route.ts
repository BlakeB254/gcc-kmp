import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { bounties, communityReports } from '@/db/schema'
import { and, desc, eq, gt, isNull, or, sql } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { bountyRowToApiBounty, reportToCleanupOpportunityBounty } from './_derived'

export const dynamic = 'force-dynamic'

export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url)
    const status = searchParams.get('status')
    const limit = Math.min(parseInt(searchParams.get('limit') || '50', 10), 100)

    if (status && status !== 'open') {
      return NextResponse.json({ data: [], success: true, message: null })
    }

    const [reports, fundedBounties] = await Promise.all([
      db
        .select()
        .from(communityReports)
        .where(
          and(
            or(eq(communityReports.showOnMap, true), isNull(communityReports.showOnMap)),
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
        .orderBy(desc(communityReports.createdAt))
        .limit(Number.isFinite(limit) && limit > 0 ? limit : 50),
      db
        .select()
        .from(bounties)
        .where(eq(bounties.status, 'open'))
        .orderBy(desc(bounties.updatedAt))
        .limit(Number.isFinite(limit) && limit > 0 ? limit : 50),
    ])

    const fundedReportIds = new Set(
      fundedBounties
        .map((bounty) => bounty.triggerReportId)
        .filter((id): id is number => id != null),
    )

    const opportunities = reports
      .filter((report) => !fundedReportIds.has(report.id))
      .map(reportToCleanupOpportunityBounty)
      .filter((bounty): bounty is NonNullable<typeof bounty> => bounty != null)

    const funded = fundedBounties.map(bountyRowToApiBounty)

    return NextResponse.json({ data: [...funded, ...opportunities], success: true, message: null })
  } catch (error) {
    console.error('Error fetching cleanup opportunities:', error)
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
    const triggerReportId = Number(body.triggerReportId)
    const amount = Number(body.amount)
    const title = typeof body.title === 'string' ? body.title.trim() : ''
    const description = typeof body.description === 'string' ? body.description.trim() : null
    const visibility = body.visibility === 'secret' ? 'secret' : 'public'
    const isAnonymous = body.isAnonymous === true
    const posterName = typeof body.posterName === 'string' ? body.posterName.trim() : null

    if (!Number.isInteger(triggerReportId) || triggerReportId <= 0) {
      return NextResponse.json(
        { data: null, success: false, message: 'Choose an active spot report before posting a bounty' },
        { status: 400 },
      )
    }
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

    const [report] = await db
      .select()
      .from(communityReports)
      .where(
        and(
          eq(communityReports.id, triggerReportId),
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

    const reportBounty = report ? reportToCleanupOpportunityBounty(report) : null
    if (!report || !reportBounty) {
      return NextResponse.json(
        { data: null, success: false, message: 'Spot report is not available for bounty funding' },
        { status: 404 },
      )
    }

    const [existing] = await db
      .select()
      .from(bounties)
      .where(and(eq(bounties.triggerReportId, triggerReportId), eq(bounties.status, 'open')))
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
        description: description || reportBounty.description || null,
        amount: String(amount),
        status: 'open',
        visibility,
        isAnonymous,
        posterName: isAnonymous ? null : posterName,
        locationLat: reportBounty.locationLat,
        locationLng: reportBounty.locationLng,
        radiusMeters: String(reportBounty.radiusMeters),
        city: reportBounty.city,
        communityArea: reportBounty.communityArea,
        neighborhood: reportBounty.neighborhood,
        triggerReportId,
        observerId: report.reporterId,
        mediaUrls: reportBounty.mediaUrls.length > 0 ? reportBounty.mediaUrls : null,
        aiAnalysis: reportBounty.aiAnalysis,
        expiresAt: report.mapExpiresAt,
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
