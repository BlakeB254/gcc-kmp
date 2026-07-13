/**
 * GET /api/workers/me/wallet
 *
 * The worker's in-app wallet: available balance + recent ledger activity.
 * Earnings land here when an admin approves a job (or sends an ad-hoc
 * credit); money leaves via POST /api/workers/me/withdraw.
 */
import { NextRequest, NextResponse } from 'next/server'
import { desc, eq } from 'drizzle-orm'
import { db } from '@/db'
import { workers, workerWalletEntries } from '@/db/schema'
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

  const [worker] = await db
    .select({ balanceCents: workers.walletBalanceCents })
    .from(workers)
    .where(eq(workers.id, auth.workerId))
    .limit(1)

  if (!worker) {
    return NextResponse.json({ data: null, message: 'Worker not found' }, { status: 404 })
  }

  const entries = await db
    .select({
      id: workerWalletEntries.id,
      amountCents: workerWalletEntries.amountCents,
      kind: workerWalletEntries.kind,
      jobId: workerWalletEntries.jobId,
      payoutId: workerWalletEntries.payoutId,
      note: workerWalletEntries.note,
      createdAt: workerWalletEntries.createdAt,
    })
    .from(workerWalletEntries)
    .where(eq(workerWalletEntries.workerId, auth.workerId))
    .orderBy(desc(workerWalletEntries.createdAt), desc(workerWalletEntries.id))
    .limit(HISTORY_LIMIT)

  return NextResponse.json({
    data: { balanceCents: worker.balanceCents, entries },
    success: true,
    message: null,
  })
}
