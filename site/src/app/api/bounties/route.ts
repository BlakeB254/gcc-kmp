/**
 * Bounties API
 * GET /api/bounties - Returns actual bounties (community reports with monetary rewards attached)
 *
 * Bounties are NOT the same as spot reports. A spot report (community_reports)
 * only becomes a bounty when someone pledges money for its cleanup.
 * Until the bounty/pledge system is implemented, this returns an empty list.
 */
import { NextRequest, NextResponse } from 'next/server'

export const dynamic = 'force-dynamic'

export async function GET(request: NextRequest) {
  // No bounty system implemented yet — return empty list
  // When bounties are implemented, they'll have a separate bounties table
  // or a bounty_amount column on community_reports
  return NextResponse.json({ data: [], success: true })
}

export async function POST() {
  return Response.json(
    { error: 'Bounty creation not yet available', status: 501 },
    { status: 501 }
  )
}
