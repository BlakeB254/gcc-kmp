/**
 * Spot Verification API
 * POST /api/spots/:id/verify - Submit a verification (still_dirty or already_clean)
 *
 * Anti-gaming measures:
 * - Verifier must be different person than original reporter
 * - GPS proximity check: must be within affectedRadiusMeters + 200m
 * - Optimistic concurrency via verificationCount
 * - Photo evidence required
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { communityReports, spotVerifications, pointsLedger, workers } from '@/db/schema'
import { eq, and, sql } from 'drizzle-orm'
import { verifyMobileToken } from '@/lib/mobile-auth'

export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ id: string }> }

export async function POST(request: NextRequest, context: RouteContext) {
  try {
    const authHeader = request.headers.get('authorization')
    if (!authHeader?.startsWith('Bearer ')) {
      return NextResponse.json({ data: null, success: false, message: 'Authorization required' }, { status: 401 })
    }
    const auth = await verifyMobileToken(authHeader.substring(7))
    if (!auth) {
      return NextResponse.json({ data: null, success: false, message: 'Invalid token' }, { status: 401 })
    }

    const { id } = await context.params
    const spotId = parseInt(id, 10)
    if (isNaN(spotId)) {
      return NextResponse.json({ data: null, success: false, message: 'Invalid spot ID' }, { status: 400 })
    }

    const body = await request.json()
    const { result, photoUrl, verifierLat, verifierLng, expectedVerificationCount } = body as {
      result: 'still_dirty' | 'already_clean'
      photoUrl?: string
      verifierLat?: number
      verifierLng?: number
      expectedVerificationCount?: number
    }

    if (!result || !['still_dirty', 'already_clean'].includes(result)) {
      return NextResponse.json({ data: null, success: false, message: 'result must be "still_dirty" or "already_clean"' }, { status: 400 })
    }

    if (!photoUrl) {
      return NextResponse.json({ data: null, success: false, message: 'Photo evidence required' }, { status: 400 })
    }

    // Fetch the report
    const [report] = await db
      .select()
      .from(communityReports)
      .where(eq(communityReports.id, spotId))
      .limit(1)

    if (!report) {
      return NextResponse.json({ data: null, success: false, message: 'Spot report not found' }, { status: 404 })
    }

    // Anti-gaming: verifier must not be the original reporter
    if (report.reporterId === auth.workerId) {
      return NextResponse.json({ data: null, success: false, message: 'Cannot verify your own report' }, { status: 403 })
    }

    // Anti-gaming: optimistic concurrency check
    if (expectedVerificationCount !== undefined && report.verificationCount !== expectedVerificationCount) {
      return NextResponse.json({ data: null, success: false, message: 'Spot was already verified by someone else' }, { status: 409 })
    }

    // Parse spot coordinates once
    const coords = report.coordinates as [number, number] | { lng: number; lat: number }
    const spotLat = Array.isArray(coords) ? coords[1] : coords.lat
    const spotLng = Array.isArray(coords) ? coords[0] : coords.lng

    // Anti-gaming: GPS proximity check + distance for record
    let distanceFromSpot: number | null = null
    if (verifierLat != null && verifierLng != null) {
      distanceFromSpot = haversineDistance(verifierLat, verifierLng, spotLat, spotLng)
      const maxDistance = parseFloat(report.affectedRadiusMeters || '15') + 200

      if (distanceFromSpot > maxDistance) {
        return NextResponse.json({
          error: `Too far from spot. You are ${Math.round(distanceFromSpot)}m away, max is ${Math.round(maxDistance)}m`,
        }, { status: 403 })
      }
    }

    // Determine points
    const pointsAwarded = result === 'still_dirty' ? 5 : 10
    const now = new Date().toISOString()
    const next24h = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString()

    // Look up verifier name
    const [worker] = await db
      .select({ name: workers.name })
      .from(workers)
      .where(eq(workers.id, auth.workerId))
      .limit(1)

    // Insert verification record
    const [verification] = await db
      .insert(spotVerifications)
      .values({
        reportId: spotId,
        verifierId: auth.workerId,
        verifierName: worker?.name || 'Anonymous',
        result,
        photoUrl,
        verifierLat: verifierLat != null ? String(verifierLat) : null,
        verifierLng: verifierLng != null ? String(verifierLng) : null,
        distanceFromSpotMeters: distanceFromSpot != null ? String(distanceFromSpot) : null,
        pointsAwarded,
      })
      .returning()

    // Run report update, points insert, and worker update in parallel
    const reportUpdate = result === 'already_clean'
      ? db.update(communityReports).set({
          status: 'resolved',
          showOnMap: false,
          lastVerifiedAt: now,
          verificationCount: (report.verificationCount || 0) + 1,
          missedVerifications: 0,
          updatedAt: now,
        }).where(eq(communityReports.id, spotId))
      : db.update(communityReports).set({
          lastVerifiedAt: now,
          verificationCount: (report.verificationCount || 0) + 1,
          nextVerificationDue: next24h,
          missedVerifications: 0,
          updatedAt: now,
        }).where(eq(communityReports.id, spotId))

    await Promise.all([
      reportUpdate,
      db.insert(pointsLedger).values({
        workerId: auth.workerId,
        action: result === 'still_dirty' ? 'verification_dirty' : 'verification_clean',
        points: pointsAwarded,
        referenceType: 'verification',
        referenceId: verification.id,
      }),
      db.update(workers).set({
        statsCommunityPoints: sql`COALESCE(${workers.statsCommunityPoints}, 0) + ${pointsAwarded}`,
      }).where(eq(workers.id, auth.workerId)),
    ])

    return NextResponse.json({
      data: {
        verification,
        pointsAwarded,
        spotResolved: result === 'already_clean',
      },
      success: true,
      message: null,
    })
  } catch (error) {
    console.error('Spot verification error:', error)
    return NextResponse.json({ data: null, success: false, message: 'Failed to process verification' }, { status: 500 })
  }
}

/** Haversine distance between two lat/lng points in meters */
function haversineDistance(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const R = 6371000 // Earth's radius in meters
  const dLat = ((lat2 - lat1) * Math.PI) / 180
  const dLng = ((lng2 - lng1) * Math.PI) / 180
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos((lat1 * Math.PI) / 180) *
      Math.cos((lat2 * Math.PI) / 180) *
      Math.sin(dLng / 2) *
      Math.sin(dLng / 2)
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}
