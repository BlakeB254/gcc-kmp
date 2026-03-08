import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { communityReports } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'

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

export async function POST(request: NextRequest) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const body = await request.json()
    const { type, lat, lng, description, mediaUrls, affectedRadiusMeters } = body

    if (lat == null || lng == null) {
      return NextResponse.json({ error: 'lat and lng are required' }, { status: 400 })
    }

    const [newReport] = await db
      .insert(communityReports)
      .values({
        reporterType: 'worker',
        reporterId: auth.workerId,
        reporterName: 'GCC',
        type: type === 'sighting' ? 'sighting' : 'volunteer_cleanup',
        description: description || null,
        coordinates: [lng, lat],
        mediaIds: mediaUrls && mediaUrls.length > 0 ? mediaUrls : null,
        status: 'pending',
        showOnMap: true,
        capturedAt: new Date().toISOString(),
      })
      .returning()

    const report = toCommunityReport(newReport)

    return NextResponse.json({
      data: {
        report,
        matchingBounties: [],
      },
    })
  } catch (error) {
    console.error('Admin create spot error:', error)
    return NextResponse.json({ error: 'Failed to create report' }, { status: 500 })
  }
}
