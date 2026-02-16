/**
 * My Community Reports API
 * GET /api/community-reports/my-reports - Get user's own reports
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { communityReports } from '@/db/schema'
import { eq, and, desc, sql } from 'drizzle-orm'
import { verifyWorkerToken } from '@/lib/auth'

export async function GET(request: NextRequest) {
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

    const { searchParams } = new URL(request.url)
    const page = parseInt(searchParams.get('page') || '1', 10)
    const limit = parseInt(searchParams.get('limit') || '20', 10)
    const type = searchParams.get('type')

    const offset = (page - 1) * limit

    // Build conditions
    const conditions = [
      eq(communityReports.reporterType, 'worker'),
      eq(communityReports.reporterId, worker.id),
    ]

    if (type) {
      conditions.push(eq(communityReports.type, type as 'sighting' | 'volunteer_cleanup'))
    }

    // Get total count
    const [{ count }] = await db
      .select({ count: sql<number>`count(*)::int` })
      .from(communityReports)
      .where(and(...conditions))

    // Get paginated reports
    const reports = await db
      .select()
      .from(communityReports)
      .where(and(...conditions))
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
    console.error('Fetch my reports error:', error)
    return NextResponse.json(
      { error: 'Failed to fetch reports' },
      { status: 500 }
    )
  }
}
