import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { jobs, workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { routeIdsByBlockId, toMobileJob } from '@/lib/mobile-job-response'
import { generateBlockCompletionReport } from '@/lib/block-completion-report'
import { completeJob, payoutCents } from '@/lib/job-lifecycle'
import { notifyAdmins, notificationTemplates } from '@/lib/notifications'
import { findQualifyingScanForJob, isUniqueViolation } from '@/lib/scan-completion'
import {
  commitPublicDataMutation,
  type PublicDataInvalidator,
} from '@/lib/public-data/invalidation'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

type RouteContext = { params: Promise<{ id: string }> }

export function commitJobCompletion<T>(
  mutation: () => Promise<T>,
  invalidate?: PublicDataInvalidator,
): Promise<T> {
  // Impact stats counts jobs by status; public stats does not.
  return commitPublicDataMutation('impact-stats', mutation, invalidate)
}

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

    // Server-verified completion (finding H2). A worker's job completes only
    // on a scan that the server itself verified (cleaning lap, lap-one proof,
    // server-computed route coverage — POST /api/scans/{id}/complete), on
    // this job's route, by this worker, started at/after they claimed the
    // job, and not already used to complete another job. "Some completed scan
    // by this worker on the route, ever" is no longer enough. $0 test jobs go
    // through the same check: they exercise the same flow, and a test that
    // skips the gate tests nothing.
    //
    // Admin completion stays ungated (manual recovery path). Blocks without a
    // linked route can't be scanned, so they skip the check.
    const blockRouteMap = await routeIdsByBlockId([existing.blockId])
    const blockRouteId = blockRouteMap.get(existing.blockId) ?? null
    let completionScanId: number | null = null
    if (auth.role !== 'admin' && blockRouteId != null) {
      const scan = await findQualifyingScanForJob(
        db,
        // Ownership was already enforced above, so on this (non-admin) path
        // the job's worker IS the caller.
        { id: existing.id, workerId: auth.workerId, claimedAt: existing.claimedAt, startedAt: existing.startedAt },
        blockRouteId,
      )
      if (!scan) {
        return NextResponse.json(
          {
            error: 'Complete a verified block scan (both laps) after claiming this job before completing it',
            success: false,
          },
          { status: 409 },
        )
      }
      completionScanId = scan.id
    }

    // Paid job → pending_review (an admin approves it, which is the ONLY
    // place its earning is credited). $0 test job → completed. The UPDATE
    // re-checks the status so two completes, or a complete racing a
    // decline/release, can't both apply.
    const now = new Date().toISOString()
    let updated
    try {
      updated = await commitJobCompletion(() =>
        completeJob(db, existing, { workerId: auth.workerId, isAdmin: auth.role === 'admin' }, now, {
          completionScanId,
        }),
      )
    } catch (error) {
      // jobs_completion_scan_id_unique: another job claimed this scan between
      // our read and this UPDATE.
      if (isUniqueViolation(error)) {
        return NextResponse.json(
          { error: 'That scan was already used to complete another job', success: false },
          { status: 409 },
        )
      }
      throw error
    }
    if (!updated) {
      return NextResponse.json(
        { error: 'Job is no longer in progress', success: false },
        { status: 409 },
      )
    }

    // Tell admins there is a payout to review. Best-effort: the job is
    // already in review; a lookup/notification failure must not fail it.
    if (updated.status === 'pending_review') {
      try {
        const [worker] = updated.workerId
          ? await db.select({ name: workers.name }).from(workers).where(eq(workers.id, updated.workerId)).limit(1)
          : []
        await notifyAdmins(
          notificationTemplates.payoutNeedsApproval(
            worker?.name ?? `Worker #${updated.workerId}`,
            jobId,
            (payoutCents(updated.payoutAmount) / 100).toFixed(2),
          ),
        )
      } catch (notifyErr) {
        console.error(`PATCH /api/jobs/[id]/complete: review notification failed for job ${jobId}:`, notifyErr)
      }
    }

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

    return NextResponse.json({
      data: toMobileJob(updated, blockRouteId),
      success: true,
    })
  } catch (error) {
    console.error('PATCH /api/jobs/[id]/complete error:', error)
    return NextResponse.json({ error: 'Failed to complete job', success: false }, { status: 500 })
  }
}
