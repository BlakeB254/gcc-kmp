import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { jobs } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { routeIdsByBlockId, toMobileJob } from '@/lib/mobile-job-response'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

type RouteContext = { params: Promise<{ id: string }> }

export async function POST(request: NextRequest, context: RouteContext) {
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
    if (existing.status !== 'available' && auth.role !== 'admin') {
      return NextResponse.json({ error: 'Job is not available', success: false }, { status: 409 })
    }

    const now = new Date().toISOString()
    const [updated] = await db
      .update(jobs)
      .set({
        workerId: auth.workerId,
        status: 'claimed',
        claimedAt: now,
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
    console.error('POST /api/jobs/[id]/claim error:', error)
    return NextResponse.json({ error: 'Failed to claim job', success: false }, { status: 500 })
  }
}
