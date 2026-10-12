/**
 * POST /api/admin/wallet-credits/[id]/reject
 *
 * Withdraw a pending ad-hoc credit request. Any admin may — the requester
 * included (they noticed a mistake). One conditional UPDATE … WHERE status =
 * 'pending' (lib/admin-credit-approval.ts rejectPendingCredit); no row → 409.
 * Rejecting gives the day's reserved capacity back to the requesting admin
 * and the target (lib/admin-credit-caps.ts) for the UTC day the request was
 * made.
 *
 * Body (optional): { note?: string } — why.
 */
import { NextResponse } from 'next/server'
import { db } from '@/db'
import { requireAdmin } from '@/lib/admin-auth'
import { getPendingAdminCredit, rejectPendingCredit } from '@/lib/admin-credit-approval'
import { releaseDailyCapacity, utcDay } from '@/lib/admin-credit-caps'
import { MAX_NOTE_LEN } from '@/lib/payouts'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ id: string }> }

export async function POST(request: Request, context: RouteContext) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const { id } = await context.params
  const pendingId = parseInt(id, 10)
  if (Number.isNaN(pendingId)) {
    return NextResponse.json({ error: 'Invalid credit request id' }, { status: 400 })
  }

  let note: string | null = null
  try {
    const body = await request.json()
    if (body && typeof body === 'object' && typeof body.note === 'string') {
      note = body.note.trim().slice(0, MAX_NOTE_LEN) || null
    }
  } catch {
    // No body is fine.
  }

  const pending = await getPendingAdminCredit(db, pendingId)
  if (!pending) {
    return NextResponse.json({ error: 'Credit request not found' }, { status: 404 })
  }
  if (pending.status !== 'pending') {
    return NextResponse.json({ error: `This credit was already ${pending.status}.` }, { status: 409 })
  }

  const now = new Date().toISOString()
  const rejected = await rejectPendingCredit(db, pendingId, auth.workerId, now, note)
  if (!rejected) {
    return NextResponse.json({ error: 'Credit request already decided' }, { status: 409 })
  }

  await releaseDailyCapacity(db, {
    adminWorkerId: rejected.createdByAdminId,
    targetWorkerId: rejected.targetWorkerId,
    amountCents: rejected.amountCents,
    day: utcDay(new Date(rejected.createdAt)),
  })

  return NextResponse.json({ ok: true, pendingCreditId: pendingId, status: rejected.status })
}
