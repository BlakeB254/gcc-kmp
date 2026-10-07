import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { jobs } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { routeIdsByBlockId, toMobileJob } from '@/lib/mobile-job-response'
import { claimAvailableJob } from '@/lib/job-lifecycle'

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
    if (existing.status !== 'available') {
      return NextResponse.json({ error: 'Job is not available', success: false }, { status: 409 })
    }

    // Conditional on status='available' so two concurrent claims can't both
    // win (the read above is only for the 404/clear message). Admins no
    // longer bypass availability: reassigning a taken job is
    // /api/admin/jobs/[id]/reassign.
    const updated = await claimAvailableJob(db, jobId, auth.workerId, new Date().toISOString())
    if (!updated) {
      return NextResponse.json({ error: 'Job was just claimed by someone else', success: false }, { status: 409 })
    }

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
