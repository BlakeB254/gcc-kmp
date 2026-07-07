import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { jobs } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { routeIdsByBlockId, toMobileJob } from '@/lib/mobile-job-response'
import { generateBlockCompletionReport } from '@/lib/block-completion-report'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

type RouteContext = { params: Promise<{ id: string }> }

export async function PATCH(request: NextRequest, context: RouteContext) {
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

    const [existing] = await db.select().from(jobs).where(eq(jobs.id, jobId)).limit(1)
    if (!existing) {
      return NextResponse.json({ error: 'Job not found', success: false }, { status: 404 })
    }
    if (auth.role !== 'admin' && existing.workerId !== auth.workerId) {
      return NextResponse.json({ error: 'Job belongs to a different worker', success: false }, { status: 403 })
    }
    if (auth.role !== 'admin' && existing.status !== 'in_progress') {
      return NextResponse.json({ error: 'Job must be in progress to complete', success: false }, { status: 409 })
    }

    const now = new Date().toISOString()
    const isTestAssignment = Number(existing.payoutAmount ?? 0) === 0
    const [updated] = await db
      .update(jobs)
      .set({
        status: 'completed',
        completedAt: now,
        payoutStatus: isTestAssignment ? 'completed' : existing.payoutStatus,
        updatedAt: now,
      })
      .where(eq(jobs.id, jobId))
      .returning()

    // Pillar 9 — auto-generate the block completion report from the job's
    // linked route_scans session. Non-fatal by design: report generation
    // can fail (e.g. no matching scan session yet) without failing job
    // completion. Admin can re-run via POST /api/admin/reports/[id]/regenerate.
    try {
      await generateBlockCompletionReport(jobId)
    } catch (reportError) {
      console.error(
        `PATCH /api/jobs/[id]/complete: completion report generation failed for job ${jobId}:`,
        reportError
      )
    }

    const routeMap = await routeIdsByBlockId([updated.blockId])
    return NextResponse.json({
      data: toMobileJob(updated, routeMap.get(updated.blockId) ?? null),
      success: true,
    })
  } catch (error) {
    console.error('PATCH /api/jobs/[id]/complete error:', error)
    return NextResponse.json({ error: 'Failed to complete job', success: false }, { status: 500 })
  }
}
