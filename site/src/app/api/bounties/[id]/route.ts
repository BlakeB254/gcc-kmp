import { NextResponse } from 'next/server'
import { db } from '@/db'
import { bounties, communityReports } from '@/db/schema'
import { and, eq, gt, isNull, or } from 'drizzle-orm'
import { bountyRowToApiBounty, reportToCleanupOpportunityBounty } from '../_derived'

export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ id: string }> }

export async function GET(_request: Request, context: RouteContext) {
  try {
    const { id } = await context.params
    const bountyId = parseInt(id, 10)
    if (isNaN(bountyId)) {
      return NextResponse.json(
        { data: null, success: false, message: 'Invalid bounty ID' },
        { status: 400 },
      )
    }

    if (bountyId > 0) {
      const [bounty] = await db
        .select()
        .from(bounties)
        .where(eq(bounties.id, bountyId))
        .limit(1)

      if (bounty) {
        return NextResponse.json({ data: bountyRowToApiBounty(bounty), success: true, message: null })
      }
    }

    const reportId = Math.abs(bountyId)
    const [report] = await db
      .select()
      .from(communityReports)
      .where(
        and(
          eq(communityReports.id, reportId),
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
      .limit(1)

    const bounty = report ? reportToCleanupOpportunityBounty(report) : null
    if (!bounty) {
      return NextResponse.json(
        { data: null, success: false, message: 'Bounty not found' },
        { status: 404 },
      )
    }

    return NextResponse.json({ data: bounty, success: true, message: null })
  } catch (error) {
    console.error('Error fetching cleanup opportunity:', error)
    return NextResponse.json(
      { data: null, success: false, message: 'Failed to fetch bounty' },
      { status: 500 },
    )
  }
}
