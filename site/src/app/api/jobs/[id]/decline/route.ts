import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { jobs } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
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

    const now = new Date().toISOString()
    const payoutAmount = existing.payoutAmount ? Math.round(parseFloat(existing.payoutAmount)) : 0
    const declinedStatus = payoutAmount > 0 ? 'available' : 'disputed'
    const [updated] = await db
      .update(jobs)
      .set({
        workerId: null,
        status: declinedStatus,
        claimedAt: null,
        payoutStatus: null,
        updatedAt: now,
      })
      .where(eq(jobs.id, jobId))
      .returning()

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
