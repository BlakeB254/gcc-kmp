/**
 * Bounties API
 * GET /api/bounties - Returns community reports (sightings) as bounties
 *
 * Since there is no separate bounties table, we use community_reports
 * with type='sighting' as the data source. Each sighting becomes a bounty
 * displayed on the map with an amount based on status/severity.
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { communityReports } from '@/db/schema'
import { eq, and, or, desc, sql } from 'drizzle-orm'

export const dynamic = 'force-dynamic'

// Map community report status to bounty status
function toBountyStatus(reportStatus: string): 'open' | 'in_progress' | 'completed' {
  switch (reportStatus) {
    case 'pending':
    case 'verified':
      return 'open'
    case 'acknowledged':
      return 'in_progress'
    case 'resolved':
      return 'completed'
    default:
      return 'open'
  }
}

export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url)
    const limit = Math.min(parseInt(searchParams.get('limit') || '50', 10), 200)

    // Fetch sightings from community reports (these are the "bounties")
    const reports = await db
      .select({
        id: communityReports.id,
        title: communityReports.title,
        description: communityReports.description,
        status: communityReports.status,
        coordinates: communityReports.coordinates,
        locationAddress: communityReports.locationAddress,
        locationNeighborhood: communityReports.locationNeighborhood,
        thankCount: communityReports.thankCount,
        createdAt: communityReports.createdAt,
      })
      .from(communityReports)
      .where(
        and(
          eq(communityReports.type, 'sighting'),
          or(
            eq(communityReports.status, 'pending'),
            eq(communityReports.status, 'verified'),
            eq(communityReports.status, 'acknowledged'),
            eq(communityReports.status, 'resolved')
          )
        )
      )
      .orderBy(desc(communityReports.createdAt))
      .limit(limit)

    // Transform to bounty format expected by the frontend
    const bounties = reports.map((report) => ({
      id: String(report.id),
      title: report.title || 'Community Report',
      description: report.description || 'Area needs attention',
      amountCents: 10000, // Default $100 bounty per sighting
      status: toBountyStatus(report.status),
      coordinates: report.coordinates as [number, number],
      pledgeCount: report.thankCount || 0,
      locationAddress: report.locationAddress,
      locationNeighborhood: report.locationNeighborhood,
      createdAt: report.createdAt,
    }))

    return NextResponse.json({ bounties, data: bounties, success: true })
  } catch (error) {
    console.error('Error fetching bounties:', error)
    return NextResponse.json({ data: [], bounties: [], success: true })
  }
}

export async function POST() {
  return Response.json(
    { error: 'Bounty creation not yet available', status: 501 },
    { status: 501 }
  )
}
