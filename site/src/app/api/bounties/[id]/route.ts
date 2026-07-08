import { NextResponse } from 'next/server'
import { db } from '@/db'
import { bounties } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { BOUNTY_MINIMUM_CENTS, bountyRowToApiBounty } from '../_derived'

export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ id: string }> }

export async function GET(_request: Request, context: RouteContext) {
  try {
    const { id } = await context.params
    const bountyId = parseInt(id, 10)
    if (isNaN(bountyId) || bountyId <= 0) {
      return NextResponse.json(
        { data: null, success: false, message: 'Invalid bounty ID' },
        { status: 400 },
      )
    }

    const [bounty] = await db
      .select()
      .from(bounties)
      .where(eq(bounties.id, bountyId))
      .limit(1)

    const apiBounty = bounty ? bountyRowToApiBounty(bounty) : null
    if (
      !bounty ||
      !apiBounty ||
      apiBounty.status !== 'open' ||
      bounty.visibility !== 'public' ||
      // Amendment 2 / Task 1b, Change 3: mirror the list GET's "has a
      // report OR observation trigger" rule — an observation-seeded bounty
      // must be individually fetchable too, not just listable.
      (apiBounty.triggerReportId == null && apiBounty.triggerObservationId == null) ||
      apiBounty.amount < BOUNTY_MINIMUM_CENTS ||
      !Number.isFinite(apiBounty.locationLat) ||
      !Number.isFinite(apiBounty.locationLng)
    ) {
      return NextResponse.json(
        { data: null, success: false, message: 'Bounty not found' },
        { status: 404 },
      )
    }

    return NextResponse.json({ data: apiBounty, success: true, message: null })
  } catch (error) {
    console.error('Error fetching bounty:', error)
    return NextResponse.json(
      { data: null, success: false, message: 'Failed to fetch bounty' },
      { status: 500 },
    )
  }
}
