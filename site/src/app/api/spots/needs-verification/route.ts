/**
 * Spots Needing Verification API
 * GET /api/spots/needs-verification?lat=X&lng=Y&radiusKm=5
 * Returns spots where nextVerificationDue has passed (or is null and spot is old enough)
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { communityReports } from '@/db/schema'
import { and, or, eq, lt, isNull, desc } from 'drizzle-orm'
import { verifyMobileToken } from '@/lib/mobile-auth'

export const dynamic = 'force-dynamic'

export async function GET(request: NextRequest) {
  try {
    const authHeader = request.headers.get('authorization')
    if (!authHeader?.startsWith('Bearer ')) {
      return NextResponse.json({ data: null, success: false, message: 'Authorization required' }, { status: 401 })
    }
    const auth = await verifyMobileToken(authHeader.substring(7))
    if (!auth) {
      return NextResponse.json({ data: null, success: false, message: 'Invalid token' }, { status: 401 })
    }

    const { searchParams } = new URL(request.url)
    const limit = Math.min(parseInt(searchParams.get('limit') || '50', 10), 100)
    const now = new Date().toISOString()

    // Fetch spots that are still active (pending/verified) and due for verification
    const spots = await db
      .select()
      .from(communityReports)
      .where(
        and(
          // Must be an active spot
          or(
            eq(communityReports.status, 'pending'),
            eq(communityReports.status, 'verified'),
          ),
          // Show on map
          or(eq(communityReports.showOnMap, true), isNull(communityReports.showOnMap)),
          // Verification is due (nextVerificationDue has passed OR was never set)
          or(
            lt(communityReports.nextVerificationDue, now),
            isNull(communityReports.nextVerificationDue),
          ),
        )
      )
      .orderBy(desc(communityReports.missedVerifications))
      .limit(limit)

    const data = spots.map((r) => {
      const coords = r.coordinates as { lat?: number; lng?: number } | [number, number] | null
      return {
        id: r.id,
        coordinates: Array.isArray(coords)
          ? { lng: coords[0], lat: coords[1] }
          : coords ?? { lng: 0, lat: 0 },
        type: r.type,
        description: r.description,
        affectedRadiusMeters: r.affectedRadiusMeters ? parseFloat(r.affectedRadiusMeters) : 15,
        verificationCount: r.verificationCount || 0,
        missedVerifications: r.missedVerifications || 0,
        lastVerifiedAt: r.lastVerifiedAt,
        nextVerificationDue: r.nextVerificationDue,
        reporterName: r.reporterName,
        reporterId: r.reporterId,
        mediaUrls: (r.mediaIds as string[]) || [],
        createdAt: r.createdAt,
      }
    })

    return NextResponse.json({ data, success: true, message: null })
  } catch (error) {
    console.error('Needs verification error:', error)
    return NextResponse.json({ data: null, success: false, message: 'Failed to fetch spots needing verification' }, { status: 500 })
  }
}
