/**
 * My Volunteer Stats API
 * GET /api/community-reports/my-stats - Get user's volunteer statistics
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { communityReports, communityReportThanks } from '@/db/schema'
import { eq, and, sql, desc } from 'drizzle-orm'
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

    // Get aggregated stats
    const statsQuery = await db
      .select({
        totalReports: sql<number>`count(*)::int`,
        sightingsSubmitted: sql<number>`count(*) FILTER (WHERE type = 'sighting')::int`,
        volunteerCleanupsCompleted: sql<number>`count(*) FILTER (WHERE type = 'volunteer_cleanup')::int`,
        totalCleanupMinutes: sql<number>`coalesce(sum(cleanup_duration_minutes) FILTER (WHERE type = 'volunteer_cleanup'), 0)::int`,
        totalBagsCollected: sql<number>`coalesce(sum(cleanup_bags_collected) FILTER (WHERE type = 'volunteer_cleanup'), 0)::int`,
        thanksReceived: sql<number>`coalesce(sum(thank_count), 0)::int`,
        lastActivityAt: sql<string>`max(created_at)`,
      })
      .from(communityReports)
      .where(
        and(
          eq(communityReports.reporterType, 'worker'),
          eq(communityReports.reporterId, worker.id)
        )
      )

    const stats = statsQuery[0] || {
      totalReports: 0,
      sightingsSubmitted: 0,
      volunteerCleanupsCompleted: 0,
      totalCleanupMinutes: 0,
      totalBagsCollected: 0,
      thanksReceived: 0,
      lastActivityAt: null,
    }

    return NextResponse.json(stats)
  } catch (error) {
    console.error('Fetch volunteer stats error:', error)
    return NextResponse.json(
      { error: 'Failed to fetch stats' },
      { status: 500 }
    )
  }
}
