import { NextResponse } from 'next/server'
import { db } from '@/db'
import { requireAdmin } from '@/lib/admin-auth'
import { releaseJobToPool } from '@/lib/job-lifecycle'
import { commitPublicDataMutation } from '@/lib/public-data/invalidation'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ id: string }> }

/**
 * POST /api/admin/jobs/[id]/release
 *
 * Take a claimed or in-progress job away from its worker and return it to the
 * pool (lib/job-lifecycle.ts releaseJobToPool): a stalled job (the TTL sweep
 * flags but never releases paid work), a denied job the worker won't rework,
 * an abandoned claim. Paid → available, $0 → disputed; the block goes back
 * from in_progress to its route's eligible status; a route-queue slot reopens.
 * A pending_review job must be approved or denied first (409) — except one
 * denied under the old rule, which sat in pending_review for good.
 */
export async function POST(request: Request, context: RouteContext) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const { id } = await context.params
  const jobId = parseInt(id, 10)
  if (Number.isNaN(jobId)) {
    return NextResponse.json({ error: 'Invalid job ID' }, { status: 400 })
  }

  try {
    // Impact stats count jobs by status.
    const result = await commitPublicDataMutation('impact-stats', () =>
      releaseJobToPool(db, jobId, new Date().toISOString()),
    )
    if (!result.ok) {
      return NextResponse.json({ error: result.message }, { status: result.status })
    }
    return NextResponse.json({
      ok: true,
      job: result.job,
      blockReopened: result.blockReopened,
      slotReopened: result.slotReopened,
    })
  } catch (error) {
    console.error('POST /api/admin/jobs/[id]/release error:', error)
    return NextResponse.json({ error: 'Failed to release job' }, { status: 500 })
  }
}
