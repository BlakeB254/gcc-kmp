import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { jobs } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { declineClaimedJob } from '@/lib/job-lifecycle'
import { routeIdsByBlockId, toMobileJob } from '@/lib/mobile-job-response'

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
      return NextResponse.json({ error: 'Only assigned jobs can be declined before start', success: false }, { status: 409 })
    }

    // One statement: the job back to the pool (paid → available, $0 →
    // disputed), the block the claim flipped back to its route's status, and
    // a route-queue job's slot back on offer — a crash can't strand either.
    const now = new Date().toISOString()
    const declined = await declineClaimedJob(db, existing, now)
    if (!declined) {
      return NextResponse.json({ error: 'Job is no longer claimed', success: false }, { status: 409 })
    }
    const [updated] = await db.select().from(jobs).where(eq(jobs.id, jobId)).limit(1)

    const routeMap = await routeIdsByBlockId([updated.blockId])
    return NextResponse.json({
      data: toMobileJob(updated, routeMap.get(updated.blockId) ?? null),
      success: true,
    })
  } catch (error) {
    console.error('PATCH /api/jobs/[id]/decline error:', error)
    return NextResponse.json({ error: 'Failed to decline job', success: false }, { status: 500 })
  }
}
