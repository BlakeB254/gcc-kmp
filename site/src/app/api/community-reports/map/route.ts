/**
 * Community Reports Map API
 * GET /api/community-reports/map - Get reports for map display
 *
 * Returns simplified markers for efficient map rendering
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { communityReports } from '@/db/schema'
import { eq, and, or, gt, isNull, sql } from 'drizzle-orm'

export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url)
    const showOnMap = searchParams.get('showOnMap') !== 'false'
    const limit = Math.min(parseInt(searchParams.get('limit') || '100', 10), 500)
    const boundsParam = searchParams.get('bounds')

    // Build conditions for map-visible reports
    const conditions = [
      eq(communityReports.showOnMap, showOnMap),
      // Only show pending, verified, or acknowledged reports
      or(
        eq(communityReports.status, 'pending'),
        eq(communityReports.status, 'verified'),
        eq(communityReports.status, 'acknowledged')
      ),
      // Exclude expired markers
      or(
        isNull(communityReports.mapExpiresAt),
        gt(communityReports.mapExpiresAt, new Date().toISOString())
      ),
    ]

    // Get reports
    const reports = await db
      .select({
        id: communityReports.id,
        type: communityReports.type,
        status: communityReports.status,
        coordinates: communityReports.coordinates,
        title: communityReports.title,
        reporterName: communityReports.reporterName,
        thankCount: communityReports.thankCount,
        createdAt: communityReports.createdAt,
      })
      .from(communityReports)
      .where(and(...conditions))
      .limit(limit)

    // Transform to map markers
    const markers = reports.map((report) => ({
      id: String(report.id),
      type: report.type,
      status: report.status,
      coordinates: report.coordinates,
      title: report.title,
      reporterName: report.reporterName,
      thankCount: report.thankCount || 0,
      createdAt: report.createdAt,
    }))

    return NextResponse.json({
      markers,
      count: markers.length,
    })
  } catch (error) {
    console.error('Fetch map reports error:', error)
    return NextResponse.json(
      { error: 'Failed to fetch map reports' },
      { status: 500 }
    )
  }
}
