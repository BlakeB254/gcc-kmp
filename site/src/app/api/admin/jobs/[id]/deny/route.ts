import { NextResponse } from 'next/server'
import { eq } from 'drizzle-orm'
import { db } from '@/db'
import { jobs } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'
import { denyJobForRework } from '@/lib/job-lifecycle'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ id: string }> }

/**
 * POST /api/admin/jobs/[id]/deny
 *
 * Deny a pending_review job and send it back to its worker for rework:
 * pending_review → in_progress, with reviewStatus='denied', deniedByAdminId,
 * denyReason and reviewedAt (lib/job-lifecycle.ts denyJobForRework). No
 * payout moves. The worker re-scans (or re-photographs a routeless block) and
 * resubmits; completion then needs evidence made after this denial. To take
 * the job away instead, POST /api/admin/jobs/[id]/release.
 *
 * Before this, a denial left the job pending_review forever — an active job
 * that blocked the worker from claiming anything else and was skipped by
 * every sweep.
 *
 * Body: { reason: string }  (required — 400 if missing)
 */
export async function POST(request: Request, context: RouteContext) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const { id } = await context.params
  const jobId = parseInt(id, 10)
  if (Number.isNaN(jobId)) {
    return NextResponse.json({ error: 'Invalid job ID' }, { status: 400 })
  }

  // ── Parse + validate body ─────────────────────────────────────────────────
  let reason: string
  try {
    const body = await request.json()
    reason = typeof body?.reason === 'string' ? body.reason.trim() : ''
  } catch {
    return NextResponse.json({ error: 'Request body must be valid JSON' }, { status: 400 })
  }
  if (!reason) {
    return NextResponse.json({ error: 'reason is required' }, { status: 400 })
  }

  // ── Fetch job (clear 404/409 messages; the UPDATE re-checks everything) ──
  const [job] = await db.select().from(jobs).where(eq(jobs.id, jobId)).limit(1)
  if (!job) {
    return NextResponse.json({ error: 'Job not found' }, { status: 404 })
  }
  if (job.status !== 'pending_review') {
    return NextResponse.json(
      { error: `Job is not pending review (status: ${job.status})` },
      { status: 409 }
    )
  }
  if (job.payoutStatus === 'processing' || job.payoutStatus === 'completed') {
    return NextResponse.json(
      { error: 'Cannot deny a job with an in-flight or completed payout' },
      { status: 409 }
    )
  }

  const denied = await denyJobForRework(db, jobId, auth.workerId, reason, new Date().toISOString())
  if (!denied) {
    return NextResponse.json({ error: 'Job was reviewed or changed meanwhile' }, { status: 409 })
  }

  return NextResponse.json({ ok: true, status: denied.status })
}
