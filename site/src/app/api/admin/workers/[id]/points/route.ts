/**
 * POST /api/admin/workers/[id]/points
 *
 * Lets an admin grant or correct community points for a specific worker.
 * Records an immutable row in `points_ledger` (action = 'admin_grant')
 * and updates the denormalized `workers.stats_community_points` total.
 *
 * Body shape (JSON):
 *   {
 *     amount: number   // positive grants, negative subtracts. |amount| ≤ 10000.
 *     reason: string   // 1–280 chars. Captured in the ledger reference.
 *   }
 *
 * 403 if caller is not admin.
 * 404 if target worker doesn't exist.
 * 400 on bad input.
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { workers, pointsLedger } from '@/db/schema'
import { desc, eq, sql } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

const MAX_GRANT = 10000
const MAX_REASON_LEN = 280
const HISTORY_LIMIT = 50

/**
 * GET /api/admin/workers/[id]/points
 *
 * Returns the most recent points-ledger rows for this worker so an
 * admin can audit grants and corrections from the worker-detail screen.
 */
export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> },
) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const { id } = await params
  const targetWorkerId = parseInt(id, 10)
  if (isNaN(targetWorkerId)) {
    return NextResponse.json(
      { data: null, success: false, message: 'Invalid worker id' },
      { status: 400 },
    )
  }

  const rows = await db
    .select({
      id: pointsLedger.id,
      action: pointsLedger.action,
      points: pointsLedger.points,
      referenceType: pointsLedger.referenceType,
      referenceId: pointsLedger.referenceId,
      reason: pointsLedger.reason,
      grantedByWorkerId: pointsLedger.grantedByWorkerId,
      createdAt: pointsLedger.createdAt,
    })
    .from(pointsLedger)
    .where(eq(pointsLedger.workerId, targetWorkerId))
    .orderBy(desc(pointsLedger.createdAt))
    .limit(HISTORY_LIMIT)

  return NextResponse.json({
    data: { entries: rows },
    success: true,
    message: null,
  })
}

export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> },
) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const { id } = await params
  const targetWorkerId = parseInt(id, 10)
  if (isNaN(targetWorkerId)) {
    return NextResponse.json(
      { data: null, success: false, message: 'Invalid worker id' },
      { status: 400 },
    )
  }

  let body: { amount?: unknown; reason?: unknown; action?: unknown }
  try {
    body = await request.json()
  } catch {
    return NextResponse.json(
      { data: null, success: false, message: 'Body must be valid JSON' },
      { status: 400 },
    )
  }

  const amount = typeof body.amount === 'number' ? body.amount : NaN
  const reason = typeof body.reason === 'string' ? body.reason.trim() : ''

  // Allowed grant categories. Server narrows here so a stale client can't
  // sneak a different enum value through. Both must exist in the
  // points-ledger action enum (migration 0013).
  const ALLOWED_ACTIONS = ['admin_grant', 'bug_report'] as const
  type AllowedAction = (typeof ALLOWED_ACTIONS)[number]
  const requestedAction = typeof body.action === 'string' ? body.action : 'admin_grant'
  if (!ALLOWED_ACTIONS.includes(requestedAction as AllowedAction)) {
    return NextResponse.json(
      { data: null, success: false, message: `action must be one of: ${ALLOWED_ACTIONS.join(', ')}` },
      { status: 400 },
    )
  }
  const action = requestedAction as AllowedAction

  if (!Number.isFinite(amount) || amount === 0 || !Number.isInteger(amount)) {
    return NextResponse.json(
      { data: null, success: false, message: 'amount must be a non-zero integer' },
      { status: 400 },
    )
  }
  if (Math.abs(amount) > MAX_GRANT) {
    return NextResponse.json(
      { data: null, success: false, message: `|amount| must be ≤ ${MAX_GRANT}` },
      { status: 400 },
    )
  }
  if (reason.length === 0 || reason.length > MAX_REASON_LEN) {
    return NextResponse.json(
      { data: null, success: false, message: `reason must be 1–${MAX_REASON_LEN} chars` },
      { status: 400 },
    )
  }

  // Confirm the target worker exists before writing anything.
  const [target] = await db
    .select({ id: workers.id, statsCommunityPoints: workers.statsCommunityPoints })
    .from(workers)
    .where(eq(workers.id, targetWorkerId))
    .limit(1)
  if (!target) {
    return NextResponse.json(
      { data: null, success: false, message: 'Worker not found' },
      { status: 404 },
    )
  }

  // Wrap the ledger insert + denormalized-total update in a transaction
  // so they cannot diverge if the request crashes between statements.
  const { ledgerRow, updated } = await db.transaction(async (tx) => {
    const [insertedLedgerRow] = await tx
      .insert(pointsLedger)
      .values({
        workerId: targetWorkerId,
        action,
        points: amount,
        referenceType: action,
        // Keep the legacy referenceId (admin worker id) for back-compat
        // with any existing dashboards; the new explicit columns are the
        // source of truth going forward.
        referenceId: auth.workerId,
        reason,
        grantedByWorkerId: auth.workerId,
      })
      .returning()

    const [updatedWorker] = await tx
      .update(workers)
      .set({
        statsCommunityPoints: sql`COALESCE(${workers.statsCommunityPoints}, 0) + ${amount}`,
      })
      .where(eq(workers.id, targetWorkerId))
      .returning({
        id: workers.id,
        statsCommunityPoints: workers.statsCommunityPoints,
      })

    return { ledgerRow: insertedLedgerRow, updated: updatedWorker }
  })

  // Operational log — IDs only. The reason text lives in points_ledger.reason
  // for durable audit; logging free-form user input is a leakage risk.
  console.log(
    `[admin-grant] ledgerId=${ledgerRow.id} adminId=${auth.workerId} targetWorkerId=${targetWorkerId} action=${action} amount=${amount}`,
  )

  return NextResponse.json({
    data: {
      ledgerEntryId: ledgerRow.id,
      workerId: updated.id,
      newTotal: updated.statsCommunityPoints ?? 0,
      amount,
      reason,
      action,
    },
    success: true,
    message: null,
  })
}
