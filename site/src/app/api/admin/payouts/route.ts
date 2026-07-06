/**
 * GET /api/admin/payouts?status=check_pending&page=1&limit=50
 *
 * Lists ad-hoc admin payouts (newest first) with worker name/email joined.
 * status filter powers the "checks to mail" queue (status=check_pending);
 * omit it for full history.
 */
import { NextRequest, NextResponse } from 'next/server'
import { desc, eq } from 'drizzle-orm'
import { db } from '@/db'
import { payouts, workers } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

const VALID_STATUSES = ['processing', 'completed', 'failed', 'check_pending', 'check_mailed'] as const
type PayoutStatus = (typeof VALID_STATUSES)[number]

export async function GET(request: NextRequest) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const url = new URL(request.url)
  const statusParam = url.searchParams.get('status')
  if (statusParam && !VALID_STATUSES.includes(statusParam as PayoutStatus)) {
    return NextResponse.json(
      { data: null, success: false, message: `status must be one of: ${VALID_STATUSES.join(', ')}` },
      { status: 400 }
    )
  }
  const status = (statusParam as PayoutStatus | null) ?? null

  const limit = Math.min(Math.max(parseInt(url.searchParams.get('limit') ?? '50', 10) || 50, 1), 100)
  const page = Math.max(parseInt(url.searchParams.get('page') ?? '1', 10) || 1, 1)

  const rows = await db
    .select({
      id: payouts.id,
      workerId: payouts.workerId,
      workerName: workers.name,
      workerEmail: workers.email,
      amountCents: payouts.amountCents,
      note: payouts.note,
      method: payouts.method,
      status: payouts.status,
      stripeTransferId: payouts.stripeTransferId,
      initiatedByAdminId: payouts.initiatedByAdminId,
      mailingAddress: payouts.mailingAddress,
      checkMailedAt: payouts.checkMailedAt,
      createdAt: payouts.createdAt,
    })
    .from(payouts)
    .leftJoin(workers, eq(payouts.workerId, workers.id))
    .where(status ? eq(payouts.status, status) : undefined)
    .orderBy(desc(payouts.createdAt), desc(payouts.id))
    .limit(limit)
    .offset((page - 1) * limit)

  return NextResponse.json({
    data: { payouts: rows, page, limit },
    success: true,
    message: null,
  })
}
