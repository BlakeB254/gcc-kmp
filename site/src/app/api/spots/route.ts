/**
 * Spots API
 * GET  /api/spots - Returns community reports (full objects for admin, map markers for website)
 * POST /api/spots - Create a spot report (from iOS app)
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { communityReports, pointsLedger, workers } from '@/db/schema'
import { eq, and, or, gt, isNull, desc, sql } from 'drizzle-orm'
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
    affectedRadiusMeters: r.affectedRadiusMeters ? parseFloat(r.affectedRadiusMeters) : 15.0,
    photoUrl: ((r.mediaIds as string[]) || [])[0] || null,
    // Verification lifecycle
    lastVerifiedAt: r.lastVerifiedAt || null,
    verificationCount: r.verificationCount || 0,
    nextVerificationDue: r.nextVerificationDue || null,
    needsVerification: r.nextVerificationDue ? new Date(r.nextVerificationDue) <= new Date() : false,
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
          // showOnMap: treat NULL as true (column was added after initial data)
          or(eq(communityReports.showOnMap, true), isNull(communityReports.showOnMap)),
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

    // Map markers for website frontend - normalize coordinates to [lng, lat]
    const spots = reports.map((report) => {
      const raw = report.coordinates as any
      let coords: [number, number]
      if (Array.isArray(raw)) {
        coords = [raw[0], raw[1]]
      } else if (raw && typeof raw === 'object') {
        coords = [raw.lng ?? raw.lon ?? 0, raw.lat ?? 0]
      } else {
        coords = [0, 0]
      }
      return {
        id: String(report.id),
        coordinates: coords,
        radiusMeters: parseFloat(report.affectedRadiusMeters || '15'),
        label: report.title || (report.type === 'sighting' ? 'Sighting' : 'Volunteer Cleanup'),
      }
    })

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

    // Create the community report with verification lifecycle fields
    const next24h = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString()
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
        affectedRadiusMeters: affectedRadiusMeters ? String(affectedRadiusMeters) : '15',
        status: 'pending',
        showOnMap: true,
        capturedAt: new Date().toISOString(),
        // Verification lifecycle
        lastVerifiedAt: new Date().toISOString(),
        verificationCount: 0,
        nextVerificationDue: next24h,
        missedVerifications: 0,
      })
      .returning()

    // Award 10 observer points for creating a spot report
    const observerPoints = 10
    await db.insert(pointsLedger).values({
      workerId: auth.workerId,
      action: 'spot_created',
      points: observerPoints,
      referenceType: 'spot',
      referenceId: newReport.id,
    })

    // Update worker's denormalized points total
    await db
      .update(workers)
      .set({
        statsCommunityPoints: sql`COALESCE(${workers.statsCommunityPoints}, 0) + ${observerPoints}`,
      })
      .where(eq(workers.id, auth.workerId))

    // Build response matching SpotResponse { report, matchingBounties }
    const report = toCommunityReport(newReport)

    return NextResponse.json({
      data: {
        report,
        matchingBounties: [],
        pointsAwarded: observerPoints,
      },
    })
  } catch (error) {
    console.error('Create spot report error:', error)
    return NextResponse.json({ error: 'Failed to create report' }, { status: 500 })
  }
}
