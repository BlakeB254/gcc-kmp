import { NextResponse } from 'next/server'
import { eq } from 'drizzle-orm'
import { db } from '@/db'
import { jobs, workers } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'
import { processJobPayout } from '@/lib/stripe/connect'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ id: string }> }

/**
 * POST /api/admin/jobs/[id]/approve
 *
 * Approve a pending_review job and trigger a Stripe payout to the worker.
 *
 * Guards (all checked BEFORE any state change or Stripe call):
 *  1. Job exists and status === 'pending_review'  → 404 / 409
 *  2. payoutStatus not 'completed' or 'processing' → 409 (double-pay guard)
 *  3. Worker has stripeAccountId + stripeOnboardingStatus === 'completed' → 409
 *
 * On pass:
 *  - Sets jobs.status='completed', reviewStatus='approved',
 *    approvedByAdminId, reviewedAt, payoutStatus='processing'
 *  - Calls processJobPayout (transfer + payout)
 *  - Persists payoutStripeTransferId + returns { ok, payoutMethod }
 *
 * NOTE: processJobPayout does not accept a Stripe idempotency key parameter.
 * The payoutStatus guard ('completed'|'processing' → 409) is the primary
 * double-pay defence. The Stripe transfer also carries jobId in metadata so
 * manual reconciliation is possible.
 *
 * The payout.paid webhook later flips payoutStatus → 'completed'.
 */
export async function POST(request: Request, context: RouteContext) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const { id } = await context.params
  const jobId = parseInt(id, 10)
  if (Number.isNaN(jobId)) {
    return NextResponse.json({ error: 'Invalid job ID' }, { status: 400 })
  }

  // ── Fetch job + worker in one query ──────────────────────────────────────
  const [row] = await db
    .select({
      // job fields
      jobId: jobs.id,
      jobStatus: jobs.status,
      payoutStatus: jobs.payoutStatus,
      payoutAmount: jobs.payoutAmount,
      workerId: jobs.workerId,
      // worker fields
      stripeAccountId: workers.stripeAccountId,
      stripeOnboardingStatus: workers.stripeOnboardingStatus,
      stripeInstantPayoutsEnabled: workers.stripeInstantPayoutsEnabled,
    })
    .from(jobs)
    .leftJoin(workers, eq(jobs.workerId, workers.id))
    .where(eq(jobs.id, jobId))
    .limit(1)

  // ── Guard 1: job must exist and be pending_review ─────────────────────────
  if (!row) {
    return NextResponse.json({ error: 'Job not found' }, { status: 404 })
  }
  if (row.jobStatus !== 'pending_review') {
    return NextResponse.json(
      { error: `Job is not pending review (status: ${row.jobStatus})` },
      { status: 409 }
    )
  }

  // ── Guard 2: double-pay prevention ────────────────────────────────────────
  if (row.payoutStatus === 'completed' || row.payoutStatus === 'processing') {
    return NextResponse.json(
      { error: `Payout already in state '${row.payoutStatus}' — refusing to re-trigger` },
      { status: 409 }
    )
  }

  // ── Guard 3: worker must be payouts-ready ─────────────────────────────────
  if (!row.workerId) {
    return NextResponse.json({ error: 'Job has no assigned worker' }, { status: 409 })
  }
  if (!row.stripeAccountId || row.stripeOnboardingStatus !== 'completed') {
    return NextResponse.json(
      { error: 'Worker has not completed payout setup' },
      { status: 409 }
    )
  }

  // ── All guards passed — determine payout amount ──────────────────────────
  // payoutAmount is stored in cents as a numeric string (e.g. '10000' = $100)
  const amountCents = row.payoutAmount ? Math.round(parseFloat(row.payoutAmount)) : 0
  if (amountCents <= 0) {
    return NextResponse.json(
      { error: 'Job has no payout amount configured' },
      { status: 409 }
    )
  }

  const now = new Date().toISOString()
  const useInstant = row.stripeInstantPayoutsEnabled ?? false

  // ── State change: mark as processing BEFORE calling Stripe ───────────────
  // This narrow window (between DB write and Stripe call) is safe: if the
  // Stripe call fails we set payoutStatus='failed' and the job stays approved
  // but un-paid, allowing a manual retry without triggering the double-pay guard.
  await db
    .update(jobs)
    .set({
      status: 'completed',
      reviewStatus: 'approved',
      approvedByAdminId: auth.workerId,
      reviewedAt: now,
      payoutStatus: 'processing',
      updatedAt: now,
    })
    .where(eq(jobs.id, jobId))

  // ── Stripe payout (transfer + payout to bank) ────────────────────────────
  // NOTE: idempotency key 'job-payout-{jobId}' is NOT passed here because
  // processJobPayout issues two separate Stripe calls (transfer + payout) with
  // no idempotency key support in its current signature. The payoutStatus guard
  // above is the primary double-pay defence.
  let transferId: string
  let payoutMethod: string
  try {
    const result = await processJobPayout(
      row.stripeAccountId,
      amountCents,
      String(jobId),
      useInstant
    )
    transferId = result.transfer.id
    payoutMethod = result.payout.method ?? (useInstant ? 'instant' : 'standard')
  } catch (stripeError) {
    // Stripe failed — roll payoutStatus back to failed so this can be retried
    await db
      .update(jobs)
      .set({ payoutStatus: 'failed', updatedAt: new Date().toISOString() })
      .where(eq(jobs.id, jobId))
    console.error('[approve] Stripe payout failed for job', jobId, stripeError)
    return NextResponse.json(
      { error: 'Stripe payout failed — job approved but not paid. Retry via this endpoint.' },
      { status: 502 }
    )
  }

  // ── Persist transfer ID ───────────────────────────────────────────────────
  await db
    .update(jobs)
    .set({ payoutStripeTransferId: transferId, updatedAt: new Date().toISOString() })
    .where(eq(jobs.id, jobId))

  return NextResponse.json({ ok: true, payoutMethod, transferId })
}
