import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { jobs, routeScans } from '@/db/schema'
import { and, eq, or } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { routeIdsByBlockId, toMobileJob } from '@/lib/mobile-job-response'
import { generateBlockCompletionReport } from '@/lib/block-completion-report'
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
  return commitPublicDataMutation('cleanup-impact', mutation, invalidate)
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

    // The 2-lap invariant, server side: a worker's job only completes once
    // their scan session for the block's route reached COMPLETE (scout lap +
    // cleanup lap submitted). Previously this was enforced only by client
    // navigation, so a bare PATCH could complete a job with no scan at all —
    // which also left completion-report generation permanently unable to run.
    // Admin completion stays ungated (manual recovery path). Blocks without
    // a linked route can't be scanned, so they skip the check.
    const blockRouteMap = await routeIdsByBlockId([existing.blockId])
    const blockRouteId = blockRouteMap.get(existing.blockId) ?? null
    if (auth.role !== 'admin' && blockRouteId != null) {
      const [completedScan] = await db
        .select({ id: routeScans.id })
        .from(routeScans)
        .where(
          and(
            eq(routeScans.routeId, blockRouteId),
            // Ownership was already enforced above, so on this (non-admin)
            // path the job's worker IS the caller.
            eq(routeScans.workerId, auth.workerId),
            or(eq(routeScans.status, 'completed'), eq(routeScans.phase, 'complete')),
          ),
        )
        .limit(1)
      if (!completedScan) {
        return NextResponse.json(
          { error: 'Complete the block scan (both laps) before completing the job', success: false },
          { status: 409 },
        )
      }
    }

    const now = new Date().toISOString()
    const isTestAssignment = Number(existing.payoutAmount ?? 0) === 0
    const [updated] = await commitJobCompletion(() =>
      db
        .update(jobs)
        .set({
          status: 'completed',
          completedAt: now,
          payoutStatus: isTestAssignment ? 'completed' : existing.payoutStatus,
          updatedAt: now,
        })
        .where(eq(jobs.id, jobId))
        .returning(),
    )

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
