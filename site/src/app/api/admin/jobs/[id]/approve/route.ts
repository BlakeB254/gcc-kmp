import { NextResponse } from 'next/server'
import { and, eq, isNull, ne, notInArray, or } from 'drizzle-orm'
import { db } from '@/db'
import { jobs, workers, blocks } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'
import { processJobPayout } from '@/lib/stripe/connect'
import { notifyWorker, notificationTemplates } from '@/lib/notifications'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ id: string }> }

/**
 * POST /api/admin/jobs/[id]/approve
 *
 * Approve a pending_review job and trigger a Stripe payout to the worker.
 *
 * Read-only guards (checked BEFORE any state change or Stripe call):
 *  1. Job exists and status === 'pending_review'            → 404 / 409
 *  2. reviewStatus !== 'denied' (denied is terminal)        → 409
 *  3. Worker has stripeAccountId + onboardingStatus=done    → 409
 *  4. amountCents is finite and > 0                         → 409
 *
 * Atomic claim (TOCTOU-safe):
 *  - Single conditional UPDATE with .returning():
 *    payoutStatus NOT IN ('completed','processing') AND payoutStripeTransferId IS NULL
 *  - claimed.length === 0 → 409 (already being paid or paid)
 *
 * On successful claim:
 *  - Calls processJobPayout (transfer + payout)
 *  - Persists payoutStripeTransferId; returns { ok, payoutMethod, transferId }
 *
 * On Stripe failure:
 *  - Does NOT roll back payoutStatus — leaves 'processing' to block retries
 *  - Returns 502; admin must reconcile manually
 *
 * On persist failure:
 *  - Money already moved; returns 200 with transferId so caller has it
 *  - Logs CRITICAL; webhook reconciles payoutStatus → 'completed'
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
      reviewStatus: jobs.reviewStatus,
      payoutStatus: jobs.payoutStatus,
      payoutAmount: jobs.payoutAmount,
      workerId: jobs.workerId,
      // worker fields
      stripeAccountId: workers.stripeAccountId,
      stripeOnboardingStatus: workers.stripeOnboardingStatus,
      stripeInstantPayoutsEnabled: workers.stripeInstantPayoutsEnabled,
      workerPhone: workers.phone,
      workerNotificationsPushEnabled: workers.notificationsPushEnabled,
      workerNotificationsPushSubscription: workers.notificationsPushSubscription,
      workerNotificationsSmsEnabled: workers.notificationsSmsEnabled,
      // block field
      blockCode: blocks.code,
    })
    .from(jobs)
    .leftJoin(workers, eq(jobs.workerId, workers.id))
    .leftJoin(blocks, eq(jobs.blockId, blocks.id))
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

  // ── Guard 2: denied is terminal for payment ───────────────────────────────
  if (row.reviewStatus === 'denied') {
    return NextResponse.json({ error: 'Job was denied' }, { status: 409 })
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

  // ── Guard 4: payout amount must be finite and positive ────────────────────
  // payoutAmount is stored in cents as a numeric string (e.g. '10000' = $100)
  const amountCents = row.payoutAmount ? Math.round(parseFloat(row.payoutAmount)) : 0
  if (!Number.isFinite(amountCents) || amountCents <= 0) {
    return NextResponse.json(
      { error: 'Job has no payout amount configured' },
      { status: 409 }
    )
  }

  const useInstant = row.stripeInstantPayoutsEnabled ?? false

  // ── Atomic claim: single conditional UPDATE to prevent TOCTOU race ────────
  // Only succeeds if payoutStatus is not already 'completed'/'processing' AND
  // no transfer ID exists yet. Concurrent approve calls will see claimed.length === 0.
  const claimed = await db
    .update(jobs)
    .set({
      status: 'completed',
      reviewStatus: 'approved',
      approvedByAdminId: auth.workerId,
      reviewedAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      payoutStatus: 'processing',
    })
    .where(
      and(
        eq(jobs.id, jobId),
        notInArray(jobs.payoutStatus, ['completed', 'processing']),
        isNull(jobs.payoutStripeTransferId),
        // Atomic denied-guard (NULL reviewStatus counts as not-denied): a
        // concurrent deny cannot be overwritten and paid.
        or(isNull(jobs.reviewStatus), ne(jobs.reviewStatus, 'denied')),
      )
    )
    .returning({ id: jobs.id })

  if (claimed.length === 0) {
    return NextResponse.json(
      { error: 'Job already being paid or paid' },
      { status: 409 }
    )
  }

  // ── Stripe payout (transfer + payout to bank) ────────────────────────────
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
  } catch (err) {
    // CRITICAL: Do NOT roll back payoutStatus to 'failed' — the transfer may have
    // partially completed. Leave payoutStatus='processing' to block auto-retries.
    // The atomic claim's notInArray + isNull conditions prevent double transfers.
    console.error(
      '[approve] CRITICAL: payout failed for job ' + jobId +
      ' — transfer may have partially completed; manual reconciliation required',
      err
    )
    return NextResponse.json(
      { error: 'Stripe payout failed — job approved but payout status unknown. Do NOT retry; manual reconciliation required.' },
      { status: 502 }
    )
  }

  // ── Notify worker: job approved, payout processing ──────────────────────
  // Fire-and-forget-with-logging: the payout already succeeded above: a
  // notification failure must never mask that success or block the response.
  try {
    await notifyWorker(
      {
        phone: row.workerPhone ?? undefined,
        pushSubscription: (row.workerNotificationsPushSubscription as any) ?? undefined,
        preferences: {
          pushEnabled: row.workerNotificationsPushEnabled ?? undefined,
          smsEnabled: row.workerNotificationsSmsEnabled ?? undefined,
        },
      },
      notificationTemplates.jobApproved(row.blockCode ?? `job #${jobId}`)
    )
  } catch (notifyErr) {
    console.error('[approve] Failed to send jobApproved notification for job ' + jobId, notifyErr)
  }

  // ── Persist transfer ID ───────────────────────────────────────────────────
  // Money has already moved. If persist fails, return 200 with transferId so
  // the caller has it. Leave payoutStatus='processing'; webhook reconciles.
  try {
    await db
      .update(jobs)
      .set({ payoutStripeTransferId: transferId, updatedAt: new Date().toISOString() })
      .where(eq(jobs.id, jobId))
  } catch (e) {
    console.error(
      '[approve] CRITICAL: transfer ' + transferId + ' created but failed to persist for job ' + jobId,
      e
    )
    return NextResponse.json({ ok: true, payoutMethod, transferId, persistFailed: true })
  }

  return NextResponse.json({ ok: true, payoutMethod, transferId })
}
