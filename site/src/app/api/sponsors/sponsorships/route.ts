import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { transactions, blocks, jobs, workers } from '@/db/schema'
import { eq, desc } from 'drizzle-orm'
import { verifyToken } from '@/lib/auth'

export const runtime = 'nodejs'

// Helper to authenticate and get sponsor ID from JWT
async function getAuthenticatedSponsorId(request: NextRequest): Promise<number | null> {
  const token = request.cookies.get('sponsor-token')?.value
  if (!token) return null

  const payload = await verifyToken(token)
  if (!payload || payload.type !== 'sponsor') return null

  return payload.id
}

export async function GET(request: NextRequest) {
  try {
    const sponsorId = await getAuthenticatedSponsorId(request)

    if (!sponsorId) {
      return NextResponse.json({ error: 'Not authenticated' }, { status: 401 })
    }

    // Fetch transactions for this sponsor with block details
    const txList = await db
      .select({
        id: transactions.id,
        amount: transactions.amount,
        status: transactions.status,
        sponsorType: transactions.sponsorType,
        createdAt: transactions.createdAt,
        blockId: transactions.blockId,
        blockName: blocks.name,
        blockStatus: blocks.status,
        jobId: transactions.jobId,
        workerId: jobs.workerId,
      })
      .from(transactions)
      .leftJoin(blocks, eq(transactions.blockId, blocks.id))
      .leftJoin(jobs, eq(transactions.jobId, jobs.id))
      .where(eq(transactions.sponsorId, sponsorId))
      .orderBy(desc(transactions.createdAt))
      .limit(50)

    // Get worker names for completed jobs
    const workerIds = txList.filter((tx) => tx.workerId).map((tx) => tx.workerId!)
    const workerMap: Record<number, string> = {}

    if (workerIds.length > 0) {
      const workerNames = await db
        .select({ id: workers.id, name: workers.name })
        .from(workers)
        .where(eq(workers.id, workerIds[0])) // Simple case for now

      workerNames.forEach((w) => {
        workerMap[w.id] = w.name
      })
    }

    // Transform to sponsorship format
    const sponsorships = txList.map((tx) => ({
      id: String(tx.id),
      blockCode: `BLK-${String(tx.blockId || 0).padStart(4, '0')}`,
      blockName: tx.blockName || 'Unknown Block',
      amount: Number(tx.amount) || 10000,
      status:
        tx.status === 'completed'
          ? tx.blockStatus === 'completed'
            ? 'cleaned'
            : 'pending'
          : tx.status,
      createdAt: tx.createdAt,
      workerName: tx.workerId ? workerMap[tx.workerId] : undefined,
    }))

    return NextResponse.json({
      success: true,
      sponsorships,
      total: sponsorships.length,
    })
  } catch (error) {
    console.error('Get sponsorships error:', error)
    return NextResponse.json({ error: 'Failed to get sponsorships' }, { status: 500 })
  }
}
