/**
 * Spots API
 * GET  /api/spots - Returns community reports (full objects for admin, map markers for website)
 * POST /api/spots - Create a spot report (from iOS app)
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { communityReports } from '@/db/schema'
import { eq, and, or, gt, isNull, desc } from 'drizzle-orm'
import { verifyMobileToken } from '@/lib/mobile-auth'

export const dynamic = 'force-dynamic'

// Helper to convert DB row to CommunityReport shape the iOS app expects
function toCommunityReport(r: any) {
  const coords = r.coordinates as { lat?: number; lng?: number } | [number, number] | null
  return {
    id: r.id,
    reporterId: r.reporterId,
    reporterName: r.reporterName,
    type: r.type,
    coordinates: Array.isArray(coords)
      ? { lng: coords[0], lat: coords[1] }
      : coords ?? { lng: 0, lat: 0 },
    description: r.description,
    mediaUrls: (r.mediaIds as string[]) || [],
    status: r.status,
    upvotes: r.thankCount || 0,
    verifiedBy: r.reviewedById,
    resolvedAt: r.resolvedByJobId ? r.updatedAt : null,
    bountyId: null,
    observerRewardPoints: 0,
    aiAnalysis: null,
    affectedRadiusMeters: 15.0,
    photoUrl: ((r.mediaIds as string[]) || [])[0] || null,
    createdAt: new Date(r.createdAt).toISOString(),
    updatedAt: new Date(r.updatedAt).toISOString(),
  }
}

export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url)
    const limit = Math.min(parseInt(searchParams.get('limit') || '100', 10), 500)

    // Fetch community reports that should be visible on the map
    const reports = await db
      .select()
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

    // Map markers for website frontend
    const spots = reports.map((report) => ({
      id: String(report.id),
      coordinates: report.coordinates as [number, number],
      radiusMeters: 15,
      label: report.title || (report.type === 'sighting' ? 'Sighting' : 'Volunteer Cleanup'),
    }))

    // Full CommunityReport objects for iOS admin panel (via ApiResponse<T>.data)
    const fullReports = reports.map(toCommunityReport)

    return NextResponse.json({ spots, data: fullReports, success: true })
  } catch (error) {
    console.error('Error fetching spots:', error)
    return NextResponse.json({ spots: [], data: [], success: true })
  }
}

export async function POST(request: NextRequest) {
  try {
    // Authenticate via mobile Bearer token
    const authHeader = request.headers.get('authorization')
    if (!authHeader?.startsWith('Bearer ')) {
      return NextResponse.json({ error: 'Authorization required' }, { status: 401 })
    }
    const token = authHeader.substring(7)
    const auth = await verifyMobileToken(token)
    if (!auth) {
      return NextResponse.json({ error: 'Invalid token' }, { status: 401 })
    }

    // Look up worker name
    const { workers } = await import('@/db/schema')
    const { eq: eqOp } = await import('drizzle-orm')
    const [worker] = await db
      .select({ id: workers.id, name: workers.name })
      .from(workers)
      .where(eqOp(workers.id, auth.workerId))
      .limit(1)

    const workerName = worker?.name || 'Anonymous'

    const body = await request.json()
    const {
      type,
      lat,
      lng,
      description,
      mediaUrls,
      affectedRadiusMeters,
      reporterName,
    } = body

    // Validate coordinates
    if (lat == null || lng == null) {
      return NextResponse.json({ error: 'lat and lng are required' }, { status: 400 })
    }

    // Create the community report
    const [newReport] = await db
      .insert(communityReports)
      .values({
        reporterType: 'worker',
        reporterId: auth.workerId,
        reporterName: reporterName || workerName,
        type: type === 'sighting' ? 'sighting' : 'volunteer_cleanup',
        description: description || null,
        coordinates: [lng, lat], // GeoJSON [lng, lat]
        mediaIds: mediaUrls && mediaUrls.length > 0 ? mediaUrls : null,
        status: 'pending',
        showOnMap: true,
        capturedAt: new Date().toISOString(),
      })
      .returning()

    // Build response matching SpotResponse { report, matchingBounties }
    const report = toCommunityReport(newReport)

    return NextResponse.json({
      data: {
        report,
        matchingBounties: [], // No bounty matching implemented yet
      },
    })
  } catch (error) {
    console.error('Create spot report error:', error)
    return NextResponse.json({ error: 'Failed to create report' }, { status: 500 })
  }
}
