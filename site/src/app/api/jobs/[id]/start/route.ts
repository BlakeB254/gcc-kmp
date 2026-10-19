import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { jobs } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { routeIdsByBlockId, toMobileJob } from '@/lib/mobile-job-response'
import { activePaidJobConflictMessage, startClaimedJob } from '@/lib/job-lifecycle'

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
    if (existing.status !== 'claimed') {
      return NextResponse.json({ error: 'Job must be claimed before starting', success: false }, { status: 409 })
    }

    // Conditional on status='claimed' (and, for workers, ownership) so a
    // start racing a decline/release/second start can't apply.
    let updated: Awaited<ReturnType<typeof startClaimedJob>>
    try {
      updated = await startClaimedJob(
        db,
        jobId,
        { workerId: auth.workerId, isAdmin: auth.role === 'admin' },
        new Date().toISOString(),
      )
    } catch (error) {
      // An admin starting an unassigned job becomes its worker (COALESCE):
      // the 0055 indexes refuse that if they already hold an active paid job.
      const conflict = activePaidJobConflictMessage(error)
      if (conflict) return NextResponse.json({ error: conflict, success: false }, { status: 409 })
      throw error
    }
    if (!updated) {
      return NextResponse.json({ error: 'Job is no longer claimed', success: false }, { status: 409 })
    }

    const routeMap = await routeIdsByBlockId([updated.blockId])
    return NextResponse.json({
      data: toMobileJob(updated, routeMap.get(updated.blockId) ?? null),
      success: true,
    })
  } catch (error) {
    console.error('PATCH /api/jobs/[id]/start error:', error)
    return NextResponse.json({ error: 'Failed to start job', success: false }, { status: 500 })
  }
}
