import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { jobs } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { routeIdsByBlockId, toMobileJob } from '@/lib/mobile-job-response'
import { requireMobileAuth } from '@/lib/mobile-auth'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

type RouteContext = { params: Promise<{ id: string }> }

export async function GET(request: NextRequest, context: RouteContext) {
  // A job row carries its worker, route, timing and payout. This was
  // unauthenticated — the same leak as GET /api/jobs, one id at a time.
  // Workers may read their own job; admins may read any.
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json({ data: null, success: false, message: 'Not authenticated' }, { status: 401 })
  }

  try {
    const { id } = await context.params
    const jobId = parseInt(id, 10)
    if (Number.isNaN(jobId)) {
      return NextResponse.json({ error: 'Invalid job ID', success: false }, { status: 400 })
    }

    const [job] = await db
      .select()
      .from(jobs)
      .where(eq(jobs.id, jobId))
      .limit(1)

    if (!job) {
      return NextResponse.json({ error: 'Job not found', success: false }, { status: 404 })
    }

    if (auth.role !== 'admin' && job.workerId !== auth.workerId) {
      return NextResponse.json({ error: 'Job belongs to a different worker', success: false }, { status: 403 })
    }

    const routeMap = await routeIdsByBlockId([job.blockId])
    return NextResponse.json({
      data: toMobileJob(job, routeMap.get(job.blockId) ?? null),
      success: true,
    })
  } catch (error) {
    console.error('GET /api/jobs/[id] error:', error)
    return NextResponse.json({ error: 'Failed to fetch job', success: false }, { status: 500 })
  }
}
