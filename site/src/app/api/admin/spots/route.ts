import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { communityReports } from '@/db/schema'
import { desc } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'
import { toCommunityReport } from '@/lib/spot-utils'

export async function GET(request: NextRequest) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const reports = await db
      .select()
      .from(communityReports)
      .orderBy(desc(communityReports.createdAt))
      .limit(500)

    const fullReports = reports.map((r) => toCommunityReport(r))

    return NextResponse.json({ data: fullReports, success: true })
  } catch (error) {
    console.error('Admin list spots error:', error)
    return NextResponse.json({ error: 'Failed to list spots' }, { status: 500 })
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
        affectedRadiusMeters: affectedRadiusMeters ? String(affectedRadiusMeters) : '15',
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
