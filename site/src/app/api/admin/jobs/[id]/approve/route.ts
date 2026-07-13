import { NextResponse } from 'next/server'
import { and, eq, isNull, ne, notInArray, or, sql } from 'drizzle-orm'
import { db } from '@/db'
import { jobs, workers, blocks } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'
import { creditWallet } from '@/lib/wallet'
import { notifyWorker, notificationTemplates } from '@/lib/notifications'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ id: string }> }

/**
 * POST /api/admin/jobs/[id]/approve
 *
 * Approve a pending_review job and credit the payout to the worker's in-app
 * wallet. Since the wallet-ledger rework this NEVER calls Stripe and does NOT
 * require the worker to have set up Stripe Connect — the money is earmarked
 * in the wallet immediately and leaves our Stripe balance only when the
 * worker cashes out.
 *
 * Read-only guards (checked BEFORE any state change):
 *  1. Job exists and status === 'pending_review'            → 404 / 409
 *  2. reviewStatus !== 'denied' (denied is terminal)        → 409
 *  3. Job has an assigned worker                            → 409
 *  4. amountCents is finite and > 0                         → 409
 *
 * Atomic claim (TOCTOU-safe):
 *  - Single conditional UPDATE with .returning():
 *    payoutStatus NOT IN ('completed','processing') AND payoutStripeTransferId IS NULL
 *  - claimed.length === 0 → 409 (already credited or being paid)
 *
 * On successful claim:
 *  - creditWallet(kind='job_earning') — double-credit is additionally blocked
 *    by the partial unique index on (job_id) WHERE kind='job_earning'
 *  - payoutStatus → 'completed' (the wallet credit IS delivery), stats bumped
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

  // ── Guard 3: job must have a worker to pay ────────────────────────────────
  if (!row.workerId) {
    return NextResponse.json({ error: 'Job has no assigned worker' }, { status: 409 })
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

  // ── Atomic claim: single conditional UPDATE to prevent TOCTOU race ────────
  // Only succeeds if payoutStatus is not already 'completed'/'processing' AND
  // no legacy Stripe transfer exists. Concurrent approve calls see claimed.length === 0.
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
      { error: 'Job already credited or being paid' },
      { status: 409 }
    )
  }

  // ── Credit the worker's wallet ────────────────────────────────────────────
  // The partial unique index (job_id WHERE kind='job_earning') makes this
  // idempotent even if the claim above were somehow re-run.
  const credit = await creditWallet({
    workerId: row.workerId,
    amountCents,
    kind: 'job_earning',
    jobId,
    note: row.blockCode ? `Job payout — block ${row.blockCode}` : `Job payout — job #${jobId}`,
    createdByAdminId: auth.workerId,
  })

  // Mark delivery. The wallet credit IS the payout landing (money leaves
  // Stripe later, at cash-out). payoutStatus 'completed' keeps every legacy
  // reader (worker history, admin lists) consistent.
  await db
    .update(jobs)
    .set({
      payoutStatus: 'completed',
      payoutCompletedAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    })
    .where(eq(jobs.id, jobId))

  // ── Worker stats (previously bumped by the payout.paid webhook) ──────────
  if (credit.credited) {
    await db
      .update(workers)
      .set({
        statsJobsCompleted: sql`COALESCE(${workers.statsJobsCompleted}, 0) + 1`,
        statsTotalEarnings: sql`COALESCE(${workers.statsTotalEarnings}, 0) + ${amountCents}`,
        statsLastJobAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      })
      .where(eq(workers.id, row.workerId))
  }

  // ── Notify worker: job approved, money in wallet ─────────────────────────
  // Fire-and-forget-with-logging: the credit already landed above; a
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
      notificationTemplates.walletCredited(
        `$${(amountCents / 100).toFixed(2)}`,
        row.blockCode ? `block ${row.blockCode}` : `job #${jobId}`
      )
    )
  } catch (notifyErr) {
    console.error('[approve] Failed to send walletCredited notification for job ' + jobId, notifyErr)
  }

  return NextResponse.json({
    ok: true,
    payoutMethod: 'wallet',
    balanceCents: credit.balanceCents,
    alreadyCredited: !credit.credited,
  })
}
