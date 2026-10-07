import { NextResponse } from 'next/server'
import { eq, sql } from 'drizzle-orm'
import { db } from '@/db'
import { jobs, workers, blocks } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'
import { creditWallet } from '@/lib/wallet'
import { approvalGuard, claimJobForApproval, payoutCents } from '@/lib/job-lifecycle'
import { commitPublicDataMutation } from '@/lib/public-data/invalidation'
import { notifyWorker, notificationTemplates } from '@/lib/notifications'
import { payoutsPausedResponse } from '@/lib/payouts-enabled'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ id: string }> }

/**
 * POST /api/admin/jobs/[id]/approve
 *
 * Approve a pending_review job and credit the payout to the worker's in-app
 * wallet. This is the ONLY path that credits a job earning: worker
 * completion of a paid job lands in pending_review, and manual admin credits
 * refuse job money. Since the wallet-ledger rework this NEVER calls Stripe and does NOT
 * require the worker to have set up Stripe Connect — the money is earmarked
 * in the wallet immediately and leaves our Stripe balance only when the
 * worker cashes out.
 *
 * Read-only guards (checked BEFORE any state change):
 *  1. Job exists and status === 'pending_review'            → 404 / 409
 *  2. reviewStatus !== 'denied' (denied is terminal)        → 409
 *  3. Job has an assigned worker                            → 409
 *  4. The approving admin is not the job's worker           → 403
 *  5. amountCents is finite and > 0                         → 409
 *
 * Atomic claim (TOCTOU-safe), see claimJobForApproval():
 *  - Single conditional UPDATE with .returning(): status = 'pending_review'
 *    AND (payoutStatus IS NULL OR NOT IN ('completed','processing'))
 *    AND payoutStripeTransferId IS NULL AND not denied AND worker <> admin
 *  - no row → 409 (already approved, credited or being paid)
 *
 * On successful claim:
 *  - creditWallet(kind='job_earning') — double-credit is additionally blocked
 *    by the partial unique index on (job_id) WHERE kind='job_earning'
 *  - payoutStatus → 'completed' (the wallet credit IS delivery), stats bumped
 */
export async function POST(request: Request, context: RouteContext) {
  // PAYOUTS_ENABLED master switch (lib/payouts-enabled.ts): 503 before anything else.
  const paused = payoutsPausedResponse()
  if (paused) return paused

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

  if (!row) {
    return NextResponse.json({ error: 'Job not found' }, { status: 404 })
  }

  // ── Guards 2–5 (read-only; the atomic UPDATE re-checks the money-critical ones)
  const refusal = approvalGuard(row, auth.workerId)
  if (refusal) {
    return NextResponse.json({ error: refusal.error }, { status: refusal.status })
  }
  const amountCents = payoutCents(row.payoutAmount)
  const workerId = row.workerId!

  // ── Atomic claim: single conditional UPDATE to prevent TOCTOU race ────────
  // Impact stats count jobs by status, so this flip to 'completed' refreshes them.
  const claimed = await commitPublicDataMutation('impact-stats', () =>
    claimJobForApproval(db, jobId, auth.workerId, new Date().toISOString())
  )

  if (!claimed) {
    return NextResponse.json(
      { error: 'Job already approved, credited or being paid' },
      { status: 409 }
    )
  }

  // ── Credit the worker's wallet ────────────────────────────────────────────
  // The partial unique index (job_id WHERE kind='job_earning') makes this
  // idempotent even if the claim above were somehow re-run.
  const credit = await creditWallet({
    workerId,
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
      .where(eq(workers.id, workerId))
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
