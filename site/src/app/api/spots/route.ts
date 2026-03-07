/**
 * Spots API
 * GET /api/spots - Returns community reports as spot report markers
 *
 * Both sightings and volunteer_cleanup reports are shown as orange spot dots
 * on the map. The radius indicates the approximate area of the report.
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { communityReports } from '@/db/schema'
import { eq, and, or, gt, isNull, desc } from 'drizzle-orm'

export const dynamic = 'force-dynamic'

export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url)
    const limit = Math.min(parseInt(searchParams.get('limit') || '100', 10), 500)

    // Fetch community reports that should be visible on the map
    const reports = await db
      .select({
        id: communityReports.id,
        type: communityReports.type,
        status: communityReports.status,
        coordinates: communityReports.coordinates,
        title: communityReports.title,
        showOnMap: communityReports.showOnMap,
        mapExpiresAt: communityReports.mapExpiresAt,
        createdAt: communityReports.createdAt,
      })
      .from(communityReports)
      .where(
        and(
          eq(communityReports.showOnMap, true),
          or(
            eq(communityReports.status, 'pending'),
            eq(communityReports.status, 'verified'),
            eq(communityReports.status, 'acknowledged')
          ),
          or(
            isNull(communityReports.mapExpiresAt),
            gt(communityReports.mapExpiresAt, new Date().toISOString())
          )
        )
      )
      .orderBy(desc(communityReports.createdAt))
      .limit(limit)

    // Transform to SpotReport format expected by the frontend
    const spots = reports.map((report) => ({
      id: String(report.id),
      coordinates: report.coordinates as [number, number],
      radiusMeters: 15, // Default radius for spot display
      label: report.title || (report.type === 'sighting' ? 'Sighting' : 'Volunteer Cleanup'),
    }))

    return NextResponse.json({ spots, data: spots, success: true })
  } catch (error) {
    console.error('Error fetching spots:', error)
    return NextResponse.json({ spots: [], data: [], success: true })
  }
}

export async function POST() {
  return Response.json(
    { error: 'Spot reporting not yet available via this endpoint', status: 501 },
    { status: 501 }
  )
}
