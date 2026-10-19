import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { toMobileJob } from '@/lib/mobile-job-response'
import { claimWork } from '@/lib/job-lifecycle'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

type RouteContext = { params: Promise<{ id: string }> }

/**
 * POST /api/jobs/[id]/claim — the app's claim (GccApiClient.claimJob).
 *
 * Same rules as the web's POST /api/jobs/claim, because both call claimWork
 * (lib/job-lifecycle.ts): verified worker, one active job, block available on
 * a route, block flipped to in_progress atomically with the job claim. Admins
 * claim under the same rules; reassigning a taken job is
 * /api/admin/jobs/[id]/reassign.
 */
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

    const result = await claimWork(db, { jobId }, auth.workerId, new Date().toISOString())
    if (!result.ok) {
      return NextResponse.json({ error: result.message, success: false }, { status: result.status })
    }

    return NextResponse.json({
      data: toMobileJob(result.job, result.block.routeId ?? null),
      success: true,
    })
  } catch (error) {
    console.error('POST /api/jobs/[id]/claim error:', error)
    return NextResponse.json({ error: 'Failed to claim job', success: false }, { status: 500 })
  }
}
