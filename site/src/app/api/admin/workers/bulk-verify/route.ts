import { NextResponse } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq, inArray } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'
import { mapWorkerToMobile } from '@/lib/worker-mapper'

export const runtime = 'nodejs'

// Matches shared/.../model/AdminModels.kt BulkActionRequest.workerIds and
// shared/.../model/WorkerEnums.kt BulkAction (verify/suspend/activate/delete).
const MAX_BATCH_SIZE = 100
type BulkAction = 'verify' | 'suspend' | 'activate' | 'delete'
const VALID_ACTIONS: BulkAction[] = ['verify', 'suspend', 'activate', 'delete']

/**
 * POST /api/admin/workers/bulk-verify — batch worker verification update.
 * Body: { workerIds: number[], action: BulkAction, reason?: string }.
 * Response: List<Worker> (only workers actually changed — mirrors the
 * single-worker POST /api/admin/workers/{id}/verify document-completeness
 * gate for 'verify'/'activate' so bulk approval can't skip it).
 */
export async function POST(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const body = await request.json().catch(() => null)
    const workerIds = Array.isArray(body?.workerIds)
      ? body.workerIds.filter((id: unknown) => Number.isInteger(id))
      : []
    const action = body?.action as BulkAction | undefined
    const reason = typeof body?.reason === 'string' ? body.reason : undefined

    if (workerIds.length === 0) {
      return NextResponse.json({ error: 'workerIds required' }, { status: 400 })
    }
    if (workerIds.length > MAX_BATCH_SIZE) {
      return NextResponse.json({ error: `Batch size exceeds ${MAX_BATCH_SIZE}` }, { status: 400 })
    }
    if (!action || !VALID_ACTIONS.includes(action)) {
      return NextResponse.json({ error: `action must be one of ${VALID_ACTIONS.join(', ')}` }, { status: 400 })
    }

    const rows = await db.select().from(workers).where(inArray(workers.id, workerIds))
    const found = new Map(rows.map((w) => [w.id, w]))

    if (action === 'delete') {
      const deletable = workerIds.filter((id: number) => found.has(id))
      if (deletable.length > 0) {
        await db.delete(workers).where(inArray(workers.id, deletable))
      }
      // Rows no longer exist post-delete; return the pre-delete snapshot so
      // the caller gets confirmation of what was removed.
      const data = deletable.map((id: number) => mapWorkerToMobile(found.get(id) as Record<string, unknown>))
      return NextResponse.json({ data })
    }

    const now = new Date()
    const updated: number[] = []

    for (const id of workerIds) {
      const worker = found.get(id)
      if (!worker) continue

      if (action === 'verify' || action === 'activate') {
        const hasGovId = worker.verificationGovernmentIdId != null
        const hasProofOfAddress = worker.verificationProofOfAddressId != null
        if (!hasGovId || !hasProofOfAddress) continue // skip — same gate as single-verify route

        await db.update(workers).set({
          verificationStatus: 'approved',
          verificationReviewedAt: now.toISOString(),
          verificationReviewNotes: reason ?? worker.verificationReviewNotes,
          updatedAt: now.toISOString(),
        }).where(eq(workers.id, id))
        updated.push(id)
      } else if (action === 'suspend') {
        // suspend -> 'rejected' is DELIBERATE, not a typo:
        // (a) verificationStatusEnum has NO 'suspended' value — its only
        //     values are pending/approved/rejected/needs_info (schema.ts).
        // (b) Job claiming gates on verificationStatus === 'approved', so
        //     setting any non-approved value blocks the worker from claiming
        //     jobs — the practical effect suspension wants. 'rejected' is the
        //     closest terminal state. Note AdminWorkersTab's "Suspend All"
        //     button is LIVE in the shipped app (it previously 404'd against
        //     this missing endpoint), so these semantics are load-bearing.
        // (c) FOLLOW-UP: suspended workers are indistinguishable from
        //     genuinely rejected ones — they appear mixed into the 'rejected'
        //     review queue. A dedicated 'suspended' enum value needs a schema
        //     change (enum alter + Tables.kt mirror + shared DTO), out of
        //     this task's scope.
        await db.update(workers).set({
          verificationStatus: 'rejected',
          verificationReviewedAt: now.toISOString(),
          verificationReviewNotes: reason ?? 'Suspended by admin (bulk action)',
          updatedAt: now.toISOString(),
        }).where(eq(workers.id, id))
        updated.push(id)
      }
    }

    if (updated.length === 0) {
      return NextResponse.json({ data: [] })
    }

    const updatedRows = await db.select().from(workers).where(inArray(workers.id, updated))
    const data = updatedRows.map((row) => mapWorkerToMobile(row as unknown as Record<string, unknown>))

    return NextResponse.json({ data })
  } catch (error) {
    console.error('POST /api/admin/workers/bulk-verify error:', error)
    return NextResponse.json({ error: 'Failed bulk worker action' }, { status: 500 })
  }
}
