/**
 * Community Reports API
 * POST /api/community-reports - Create a new report
 * GET /api/community-reports - List reports with pagination
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { communityReports, workers } from '@/db/schema'
import { eq, desc, and, sql } from 'drizzle-orm'
import { verifyWorkerToken } from '@/lib/auth'
import { commitPublicDataMutation } from '@/lib/public-data/invalidation'

export async function POST(request: NextRequest) {
  try {
    // Authenticate worker
    const authHeader = request.headers.get('authorization')
    if (!authHeader?.startsWith('Bearer ')) {
      return NextResponse.json(
        { error: 'Authorization required' },
        { status: 401 }
      )
    }

    const token = authHeader.substring(7)
    const worker = await verifyWorkerToken(token)
    if (!worker) {
      return NextResponse.json(
        { error: 'Invalid token' },
        { status: 401 }
      )
    }

    const body = await request.json()
    const {
      type,
      title,
      description,
      coordinates,
      locationAddress,
      mediaIds,
      beforePhotos,
      afterPhotos,
      cleanupDurationMinutes,
      cleanupBagsCollected,
      cleanupNotes,
      capturedAt,
    } = body

    // Validate required fields
    if (!type || !coordinates) {
      return NextResponse.json(
        { error: 'Type and coordinates are required' },
        { status: 400 }
      )
    }

    if (!['sighting', 'volunteer_cleanup'].includes(type)) {
      return NextResponse.json(
        { error: 'Invalid report type' },
        { status: 400 }
      )
    }

    // Create the report
    // Public stats counts community reports; impact stats does not.
    const [newReport] = await commitPublicDataMutation('public-stats', () =>
      db.insert(communityReports).values({
        reporterType: 'worker',
        reporterId: worker.id,
        reporterName: worker.name,
        type,
        title,
        description,
        coordinates,
        locationAddress,
        mediaIds: mediaIds || null,
        beforePhotos: beforePhotos || null,
        afterPhotos: afterPhotos || null,
        cleanupDurationMinutes,
        cleanupBagsCollected,
        cleanupNotes,
        status: 'pending',
        showOnMap: true,
        capturedAt: capturedAt || new Date().toISOString(),
      }).returning(),
    )

    return NextResponse.json({
      success: true,
      id: newReport.id,
      message: type === 'sighting'
        ? 'Thank you for reporting! We\'ll review and prioritize this area.'
        : 'Thank you for your volunteer cleanup! Your contribution is appreciated.',
    })
  } catch (error) {
    console.error('Create community report error:', error)
    return NextResponse.json(
      { error: 'Failed to create report' },
      { status: 500 }
    )
  }
}

export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url)
    const page = parseInt(searchParams.get('page') || '1', 10)
    const limit = parseInt(searchParams.get('limit') || '20', 10)
    const type = searchParams.get('type')
    const status = searchParams.get('status')

    const offset = (page - 1) * limit

    // Build conditions
    const conditions = []
    if (type) {
      conditions.push(eq(communityReports.type, type as 'sighting' | 'volunteer_cleanup'))
    }
    if (status) {
      conditions.push(eq(communityReports.status, status as any))
    }

    // Get total count
    const [{ count }] = await db
      .select({ count: sql<number>`count(*)::int` })
      .from(communityReports)
      .where(conditions.length > 0 ? and(...conditions) : undefined)

    // Get paginated reports
    const reports = await db
      .select()
      .from(communityReports)
      .where(conditions.length > 0 ? and(...conditions) : undefined)
      .orderBy(desc(communityReports.createdAt))
      .limit(limit)
      .offset(offset)

    const totalPages = Math.ceil(count / limit)

    return NextResponse.json({
      docs: reports,
      totalDocs: count,
      totalPages,
      page,
      limit,
      hasNextPage: page < totalPages,
      hasPrevPage: page > 1,
    })
  } catch (error) {
    console.error('List community reports error:', error)
    return NextResponse.json(
      { error: 'Failed to fetch reports' },
      { status: 500 }
    )
  }
}
