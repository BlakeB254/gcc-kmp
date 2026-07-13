/**
 * POST /api/admin/jobs/[id]/reassign
 *
 * Admin corrects who a job belongs to — including COMPLETED jobs that were
 * credited to the wrong worker. Body: { toWorkerId: number }
 *
 * What it does:
 *  1. Atomically moves jobs.workerId (conditional UPDATE keyed on the current
 *     worker — a concurrent reassign loses the race and 409s).
 *  2. If the job's payout was already credited to a wallet (a 'job_earning'
 *     ledger entry exists), moves the money: debits the previous worker
 *     (allowed to go negative — they may have already cashed out) and credits
 *     the new worker, both as auditable 'adjustment' ledger entries.
 *  3. Mirrors the jobs-completed / total-earnings stats on both workers.
 *
 * Money that was never credited (job still pending review) just follows the
 * job — approval later credits the new worker.
 */
import { NextResponse } from 'next/server'
import { and, desc, eq } from 'drizzle-orm'
import { sql } from 'drizzle-orm'
import { db } from '@/db'
import { jobs, workers, workerWalletEntries, blocks } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'
import { creditWallet, debitWallet } from '@/lib/wallet'
import { notifyWorker, notificationTemplates } from '@/lib/notifications'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ id: string }> }

export async function POST(request: Request, context: RouteContext) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const { id } = await context.params
  const jobId = parseInt(id, 10)
  if (Number.isNaN(jobId)) {
    return NextResponse.json({ error: 'Invalid job ID' }, { status: 400 })
  }

  let body: { toWorkerId?: unknown }
  try {
    body = await request.json()
  } catch {
    return NextResponse.json({ error: 'Body must be valid JSON' }, { status: 400 })
  }
  const toWorkerId = typeof body.toWorkerId === 'number' ? body.toWorkerId : NaN
  if (!Number.isInteger(toWorkerId) || toWorkerId <= 0) {
    return NextResponse.json({ error: 'toWorkerId must be a positive integer' }, { status: 400 })
  }

  const [row] = await db
    .select({
      jobId: jobs.id,
      jobStatus: jobs.status,
      workerId: jobs.workerId,
      payoutAmount: jobs.payoutAmount,
      blockCode: blocks.code,
    })
    .from(jobs)
    .leftJoin(blocks, eq(jobs.blockId, blocks.id))
    .where(eq(jobs.id, jobId))
    .limit(1)

  if (!row) {
    return NextResponse.json({ error: 'Job not found' }, { status: 404 })
  }
  if (!row.workerId) {
    return NextResponse.json(
      { error: 'Job has no assigned worker — assign it through the normal claim flow' },
      { status: 409 }
    )
  }
  const fromWorkerId = row.workerId
  if (fromWorkerId === toWorkerId) {
    return NextResponse.json({ error: 'Job is already assigned to that worker' }, { status: 409 })
  }

  const [toWorker] = await db
    .select({
      id: workers.id,
      name: workers.name,
      phone: workers.phone,
      notificationsPushEnabled: workers.notificationsPushEnabled,
      notificationsPushSubscription: workers.notificationsPushSubscription,
      notificationsSmsEnabled: workers.notificationsSmsEnabled,
    })
    .from(workers)
    .where(eq(workers.id, toWorkerId))
    .limit(1)
  if (!toWorker) {
    return NextResponse.json({ error: 'Target worker not found' }, { status: 404 })
  }
  const [fromWorker] = await db
    .select({ id: workers.id, name: workers.name })
    .from(workers)
    .where(eq(workers.id, fromWorkerId))
    .limit(1)

  // ── Atomic claim: only reassign from the worker we just read ─────────────
  const claimed = await db
    .update(jobs)
    .set({ workerId: toWorkerId, updatedAt: new Date().toISOString() })
    .where(and(eq(jobs.id, jobId), eq(jobs.workerId, fromWorkerId)))
    .returning({ id: jobs.id })

  if (claimed.length === 0) {
    return NextResponse.json(
      { error: 'Job assignment changed under you — reload and retry' },
      { status: 409 }
    )
  }

  // ── Move the money if it was already credited ─────────────────────────────
  // The primal 'job_earning' entry tells us the job was paid out to a wallet;
  // the amount moves between wallets as adjustment entries so repeated
  // reassignments stay consistent.
  const [earning] = await db
    .select({
      id: workerWalletEntries.id,
      amountCents: workerWalletEntries.amountCents,
    })
    .from(workerWalletEntries)
    .where(
      and(eq(workerWalletEntries.jobId, jobId), eq(workerWalletEntries.kind, 'job_earning'))
    )
    .orderBy(desc(workerWalletEntries.id))
    .limit(1)

  let movedCents = 0
  if (earning) {
    movedCents = earning.amountCents
    const noteBase = `Job #${jobId}${row.blockCode ? ` (block ${row.blockCode})` : ''} reassigned from ${fromWorker?.name ?? `worker ${fromWorkerId}`} to ${toWorker.name}`

    await debitWallet({
      workerId: fromWorkerId,
      amountCents: movedCents,
      kind: 'adjustment',
      jobId,
      note: noteBase,
      createdByAdminId: auth.workerId,
      allowNegative: true,
    })
    await creditWallet({
      workerId: toWorkerId,
      amountCents: movedCents,
      kind: 'adjustment',
      jobId,
      note: noteBase,
      createdByAdminId: auth.workerId,
    })

    // Mirror stats both ways (approve bumped them for the wrong worker).
    await db
      .update(workers)
      .set({
        statsJobsCompleted: sql`GREATEST(COALESCE(${workers.statsJobsCompleted}, 0) - 1, 0)`,
        statsTotalEarnings: sql`COALESCE(${workers.statsTotalEarnings}, 0) - ${movedCents}`,
        updatedAt: new Date().toISOString(),
      })
      .where(eq(workers.id, fromWorkerId))
    await db
      .update(workers)
      .set({
        statsJobsCompleted: sql`COALESCE(${workers.statsJobsCompleted}, 0) + 1`,
        statsTotalEarnings: sql`COALESCE(${workers.statsTotalEarnings}, 0) + ${movedCents}`,
        statsLastJobAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      })
      .where(eq(workers.id, toWorkerId))

    // Tell the rightful worker their money landed. Fire-and-forget.
    try {
      await notifyWorker(
        {
          phone: toWorker.phone ?? undefined,
          pushSubscription: (toWorker.notificationsPushSubscription as any) ?? undefined,
          preferences: {
            pushEnabled: toWorker.notificationsPushEnabled ?? undefined,
            smsEnabled: toWorker.notificationsSmsEnabled ?? undefined,
          },
        },
        notificationTemplates.walletCredited(
          `$${(movedCents / 100).toFixed(2)}`,
          row.blockCode ? `block ${row.blockCode}` : `job #${jobId}`
        )
      )
    } catch (notifyErr) {
      console.error('[reassign] Failed to notify worker for job ' + jobId, notifyErr)
    }
  }

  return NextResponse.json({
    ok: true,
    jobId,
    fromWorkerId,
    toWorkerId,
    movedCents,
    creditMoved: movedCents > 0,
  })
}
