import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { blocks, jobs } from '@/db/schema'
import { and, desc, eq, inArray, sql, SQL } from 'drizzle-orm'
import { toMobileJobs } from '@/lib/mobile-job-response'
import { requireMobileAuth } from '@/lib/mobile-auth'

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
  // This listing carries every job's workerId, routeId, timing and payout.
  // It was unauthenticated; the only caller is the KMP client, which already
  // sends a Bearer token on every request.
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json({ data: null, success: false, message: 'Not authenticated' }, { status: 401 })
  }

  try {
    const searchParams = request.nextUrl.searchParams
    const status = searchParams.get('status')
    const workerId = searchParams.get('worker_id')
    const routeId = searchParams.get('route_id')
    const includeTest = searchParams.get('include_test') === 'true'
    const limit = Math.min(parseInt(searchParams.get('limit') ?? '50', 10) || 50, 100)
    const offset = parseInt(searchParams.get('offset') ?? '0', 10) || 0

    const conditions: SQL[] = []

    if (status && allowedStatuses.has(status)) {
      conditions.push(eq(jobs.status, status as JobStatus))
      if (status === 'available' && !includeTest) {
        conditions.push(sql`coalesce(${jobs.payoutAmount}, '0')::numeric > 0`)
      }
    }

    const parsedWorkerId = workerId ? parseInt(workerId, 10) : null
    if (parsedWorkerId && Number.isInteger(parsedWorkerId)) {
      conditions.push(eq(jobs.workerId, parsedWorkerId))
    }

    const parsedRouteId = routeId ? parseInt(routeId, 10) : null
    if (parsedRouteId && Number.isInteger(parsedRouteId)) {
      const routeBlocks = await db
        .select({ id: blocks.id })
        .from(blocks)
        .where(eq(blocks.routeId, parsedRouteId))

      if (routeBlocks.length === 0) {
        return NextResponse.json({ data: [], success: true })
      }

      conditions.push(inArray(jobs.blockId, routeBlocks.map((block) => block.id)))
    }

    const rows = await db
      .select()
      .from(jobs)
      .where(conditions.length > 0 ? and(...conditions) : undefined)
      .orderBy(desc(jobs.createdAt))
      .limit(limit)
      .offset(offset)

    return NextResponse.json({ data: await toMobileJobs(rows), success: true })
  } catch (error) {
    console.error('GET /api/jobs error:', error)
    return NextResponse.json({ error: 'Failed to fetch jobs', success: false }, { status: 500 })
  }
}
