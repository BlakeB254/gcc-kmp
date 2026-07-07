import { NextResponse } from 'next/server'
import { db } from '@/db'
import { blockCompletionReports } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'
import { generateBlockCompletionReport } from '@/lib/block-completion-report'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ id: string }> }

/**
 * POST /api/admin/reports/[id]/regenerate
 *
 * Admin-only. [id] is interpreted as a block_completion_reports.id first
 * (matching the "reports/[id]" URL shape) — if a report exists with that
 * id, its jobId is regenerated. If no report has that id, [id] is treated
 * as a jobId instead: this matters because a job whose report generation
 * failed at completion time has no report row yet, so an admin retrying
 * from the job's admin view only has the jobId to hand, not a report id.
 * Either way this is an upsert (block_completion_reports.jobId is unique).
 */
export async function POST(request: Request, context: RouteContext) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const { id } = await context.params
  const idNum = parseInt(id, 10)
  if (Number.isNaN(idNum)) {
    return NextResponse.json({ error: 'Invalid id', success: false }, { status: 400 })
  }

  const [existingReport] = await db
    .select({ jobId: blockCompletionReports.jobId })
    .from(blockCompletionReports)
    .where(eq(blockCompletionReports.id, idNum))
    .limit(1)

  const jobId = existingReport?.jobId ?? idNum

  try {
    const report = await generateBlockCompletionReport(jobId)
    return NextResponse.json({ data: report, success: true })
  } catch (error) {
    console.error(`POST /api/admin/reports/${id}/regenerate error:`, error)
    return NextResponse.json(
      { error: 'Failed to regenerate completion report', success: false },
      { status: 500 }
    )
  }
}
