/**
 * GET /api/workers/me/payouts
 *
 * The worker's ad-hoc payout history (admin-initiated bonuses/corrections),
 * newest first. Job payouts are not included — they live on jobs rows and
 * surface through the jobs history.
 */
import { NextRequest, NextResponse } from 'next/server'
import { desc, eq } from 'drizzle-orm'
import { db } from '@/db'
import { payouts } from '@/db/schema'
import { verifyMobileToken } from '@/lib/mobile-auth'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

const HISTORY_LIMIT = 50

export async function GET(request: NextRequest) {
  const authHeader = request.headers.get('authorization')
  const auth = authHeader?.startsWith('Bearer ')
    ? await verifyMobileToken(authHeader.substring(7))
    : null
  if (!auth) {
    return NextResponse.json({ data: null, message: 'Not authenticated' }, { status: 401 })
  }

  const rows = await db
    .select({
      id: payouts.id,
      amountCents: payouts.amountCents,
      note: payouts.note,
      method: payouts.method,
      status: payouts.status,
      checkMailedAt: payouts.checkMailedAt,
      createdAt: payouts.createdAt,
    })
    .from(payouts)
    .where(eq(payouts.workerId, auth.workerId))
    .orderBy(desc(payouts.createdAt), desc(payouts.id))
    .limit(HISTORY_LIMIT)

  return NextResponse.json({ data: { payouts: rows }, success: true, message: null })
}
