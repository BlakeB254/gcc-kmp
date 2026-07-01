import { NextResponse } from 'next/server'
import { eq } from 'drizzle-orm'
import { db } from '@/db'
import { jobs } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ id: string }> }

/**
 * POST /api/admin/jobs/[id]/deny
 *
 * Deny a pending_review job. No payout is triggered; payoutStatus is unchanged.
 *
 * Body: { reason: string }  (required — 400 if missing)
 *
 * Sets: reviewStatus='denied', deniedByAdminId, denyReason, reviewedAt.
 * Does NOT move money or change payoutStatus.
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

  // ── Fetch job ─────────────────────────────────────────────────────────────
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

  // ── Apply denial fields — no payout, no payoutStatus change ──────────────
  const now = new Date().toISOString()
  await db
    .update(jobs)
    .set({
      reviewStatus: 'denied',
      deniedByAdminId: auth.workerId,
      denyReason: reason,
      reviewedAt: now,
      updatedAt: now,
    })
    .where(eq(jobs.id, jobId))

  return NextResponse.json({ ok: true })
}
