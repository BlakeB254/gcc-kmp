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
import { autoTagFromDescription, resolveSpotTags, toCommunityReport } from '@/lib/spot-utils'

export const dynamic = 'force-dynamic'

export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url)
    const limit = Math.min(parseInt(searchParams.get('limit') || '100', 10), 500)
    const lat = searchParams.get('lat') ? parseFloat(searchParams.get('lat')!) : null
    const lng = searchParams.get('lng') ? parseFloat(searchParams.get('lng')!) : null
    const radiusMeters = searchParams.get('radius') ? parseFloat(searchParams.get('radius')!) : null

    // Build proximity bounding box filter if lat/lng/radius provided
    // ~0.000009 degrees per meter at Chicago latitude
    const degPerMeter = 0.000009
    const proximityFilter = lat != null && lng != null && radiusMeters != null
      ? and(
          or(
            // Object-style coordinates {lat, lng}
            and(
              sql`(${communityReports.coordinates}->>'lng')::float BETWEEN ${lng - radiusMeters * degPerMeter} AND ${lng + radiusMeters * degPerMeter}`,
              sql`(${communityReports.coordinates}->>'lat')::float BETWEEN ${lat - radiusMeters * degPerMeter} AND ${lat + radiusMeters * degPerMeter}`,
            ),
            // Array-style coordinates [lng, lat]
            and(
              sql`(${communityReports.coordinates}->>0)::float BETWEEN ${lng - radiusMeters * degPerMeter} AND ${lng + radiusMeters * degPerMeter}`,
              sql`(${communityReports.coordinates}->>1)::float BETWEEN ${lat - radiusMeters * degPerMeter} AND ${lat + radiusMeters * degPerMeter}`,
            ),
          ),
        )
      : undefined

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
          ),
          proximityFilter,
        )
      )
      .orderBy(desc(communityReports.createdAt))
      .limit(limit)

    // Pre-compute tags once per report to avoid double autoTagFromDescription calls
    const tagsCache = new Map<number, string[]>()
    for (const r of reports) {
      const raw = (r as any).tags
      tagsCache.set(r.id, (Array.isArray(raw) && raw.length > 0) ? raw : autoTagFromDescription(r.description))
    }

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
        tags: tagsCache.get(report.id) || [],
        affectedArea: (report as any).affectedArea || null,
        needsVerification: report.nextVerificationDue ? new Date(report.nextVerificationDue) <= new Date() : false,
      }
    })

    // Full CommunityReport objects for iOS admin panel (via ApiResponse<T>.data)
    const fullReports = reports.map((r) => toCommunityReport(r, tagsCache.get(r.id)))

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
    const [worker] = await db
      .select({ id: workers.id, name: workers.name })
      .from(workers)
      .where(eq(workers.id, auth.workerId))
      .limit(1)

    const workerName = worker?.name || 'Anonymous'

    const body = await request.json()
    const {
      type,
      lat,
      lng,
      description,
      mediaUrls,
      mediaCaptions,
      affectedRadiusMeters,
      affectedArea,
      tags,
      reporterName,
      depthAnalysis,
      captureAttestation,
      onDeviceAiResult,
      duplicateOverrideReason,
    } = body

    // Validate coordinates
    if (lat == null || lng == null) {
      return NextResponse.json({ error: 'lat and lng are required' }, { status: 400 })
    }

    // Server-side image limit: max 3 photos
    if (mediaUrls && mediaUrls.length > 3) {
      return NextResponse.json({ error: 'Maximum 3 photos allowed per spot report' }, { status: 400 })
    }

    // Duplicate spot check: reject if another spot exists within 50m
    // Skip if user provided a duplicate override reason (they acknowledged the duplicate)
    if (!duplicateOverrideReason) {
      // Single query checks both object-style {lat, lng} and array-style [lng, lat] coordinates
      const duplicateCheck = await db
        .select({ id: communityReports.id })
        .from(communityReports)
        .where(
          and(
            or(
              eq(communityReports.status, 'pending'),
              eq(communityReports.status, 'verified')
            ),
            or(
              // Object-style coordinates {lat, lng}
              and(
                sql`(${communityReports.coordinates}->>'lng')::float BETWEEN ${lng - 0.00045} AND ${lng + 0.00045}`,
                sql`(${communityReports.coordinates}->>'lat')::float BETWEEN ${lat - 0.00045} AND ${lat + 0.00045}`,
              ),
              // Array-style coordinates [lng, lat]
              and(
                sql`(${communityReports.coordinates}->>0)::float BETWEEN ${lng - 0.00045} AND ${lng + 0.00045}`,
                sql`(${communityReports.coordinates}->>1)::float BETWEEN ${lat - 0.00045} AND ${lat + 0.00045}`,
              ),
            ),
          )
        )
        .limit(1)

      if (duplicateCheck.length > 0) {
        return NextResponse.json(
          { error: 'A spot report already exists within 50m of this location', existingSpotId: duplicateCheck[0].id },
          { status: 409 }
        )
      }
    }

    // Derive affectedRadiusMeters from affectedArea if present
    let effectiveRadius = affectedRadiusMeters || 15
    if (affectedArea) {
      if (affectedArea.type === 'circle') {
        effectiveRadius = affectedArea.radiusMeters
      } else if (affectedArea.type === 'rectangle') {
        // Use half-diagonal as effective radius
        const w = affectedArea.widthMeters || 10
        const h = affectedArea.heightMeters || 10
        effectiveRadius = Math.sqrt(w * w + h * h) / 2
      }
    }

    // Auto-tag from description if no tags provided
    const effectiveTags = tags && tags.length > 0 ? tags : autoTagFromDescription(description)

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
        mediaCaptions: mediaCaptions && mediaCaptions.length > 0 ? mediaCaptions : null,
        affectedRadiusMeters: String(effectiveRadius),
        affectedArea: affectedArea || null,
        tags: effectiveTags.length > 0 ? effectiveTags : null,
        status: 'pending',
        showOnMap: true,
        capturedAt: new Date().toISOString(),
        // Verification lifecycle
        lastVerifiedAt: new Date().toISOString(),
        verificationCount: 0,
        nextVerificationDue: next24h,
        missedVerifications: 0,
        depthAnalysis: depthAnalysis || null,
        captureAttestation: captureAttestation || null,
        onDeviceAiResult: onDeviceAiResult || null,
        duplicateOverrideReason: duplicateOverrideReason || null,
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
