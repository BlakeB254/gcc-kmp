import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { jobs, blockCompletionReports } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

type RouteContext = { params: Promise<{ id: string }> }

/**
 * GET /api/jobs/[id]/completion-report
 *
 * Worker: can fetch their own job's report. Admin: can fetch any job's
 * report. 404 (not 200-with-null) when a report hasn't been generated yet
 * — report generation is non-fatal and best-effort at job-completion time
 * (see jobs/[id]/complete/route.ts), so a missing row is an expected,
 * transient state the client should treat as "still generating", not an
 * error.
 */
export async function GET(request: NextRequest, context: RouteContext) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json({ data: null, success: false, message: 'Not authenticated' }, { status: 401 })
  }

  const { id } = await context.params
  const jobId = parseInt(id, 10)
  if (Number.isNaN(jobId)) {
    return NextResponse.json({ error: 'Invalid job ID', success: false }, { status: 400 })
  }

  const [job] = await db.select().from(jobs).where(eq(jobs.id, jobId)).limit(1)
  if (!job) {
    return NextResponse.json({ error: 'Job not found', success: false }, { status: 404 })
  }
  if (auth.role !== 'admin' && job.workerId !== auth.workerId) {
    return NextResponse.json({ error: 'Job belongs to a different worker', success: false }, { status: 403 })
  }

  const [report] = await db
    .select()
    .from(blockCompletionReports)
    .where(eq(blockCompletionReports.jobId, jobId))
    .limit(1)

  if (!report) {
    return NextResponse.json(
      { error: 'Completion report not yet generated', success: false },
      { status: 404 }
    )
  }

  return NextResponse.json({ data: report, success: true })
}
