import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { jobs } from '@/db/schema'
import { and, desc, eq, SQL } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { toMobileJobs } from '@/lib/mobile-job-response'
import { notExpiredTestJobCondition } from '@/lib/stale-test-jobs'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

type JobStatus = NonNullable<typeof jobs.$inferSelect.status>

const allowedStatuses = new Set([
  'available',
  'claimed',
  'in_progress',
  'pending_review',
  'completed',
  'disputed',
])

export async function GET(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json({ data: null, success: false, message: 'Not authenticated' }, { status: 401 })
  }

  try {
    const searchParams = request.nextUrl.searchParams
    const status = searchParams.get('status')
    const limit = Math.min(parseInt(searchParams.get('limit') ?? '20', 10) || 20, 100)
    const offset = parseInt(searchParams.get('offset') ?? '0', 10) || 0

    // Expired $0 test jobs are hidden even before the route-releases cron
    // releases them, so the app stops drawing an abandoned assignment.
    const conditions: SQL[] = [eq(jobs.workerId, auth.workerId), notExpiredTestJobCondition()]
    if (status && allowedStatuses.has(status)) {
      conditions.push(eq(jobs.status, status as JobStatus))
    }

    const rows = await db
      .select()
      .from(jobs)
      .where(and(...conditions))
      .orderBy(desc(jobs.createdAt))
      .limit(limit)
      .offset(offset)

    return NextResponse.json({ data: await toMobileJobs(rows), success: true })
  } catch (error) {
    console.error('GET /api/workers/me/jobs error:', error)
    return NextResponse.json({ error: 'Failed to fetch worker jobs', success: false }, { status: 500 })
  }
}
