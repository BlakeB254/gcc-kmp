import { NextResponse } from 'next/server'
import { db } from '@/db'
import { workers, sponsors, blocks, transactions, waitlistEntries } from '@/db/schema'
import { eq, sql } from 'drizzle-orm'

export const runtime = 'nodejs'
export const revalidate = 60 // Cache for 60 seconds

export async function GET() {
  try {
    // Fetch all counts in parallel
    const [
      workersCountResult,
      sponsorsCountResult,
      blocksCleanedResult,
      blocksFundedResult,
      totalRaisedResult,
      waitlistCountResult,
    ] = await Promise.all([
      // Total workers (approved only)
      db
        .select({ count: sql<number>`count(*)` })
        .from(workers)
        .where(eq(workers.verificationStatus, 'approved')),

      // Total sponsors
      db
        .select({ count: sql<number>`count(*)` })
        .from(sponsors),

      // Blocks cleaned
      db
        .select({ count: sql<number>`count(*)` })
        .from(blocks)
        .where(eq(blocks.status, 'completed')),

      // Blocks funded (paid for, waiting to be cleaned)
      db
        .select({ count: sql<number>`count(*)` })
        .from(blocks)
        .where(eq(blocks.status, 'funded')),

      // Total amount raised (completed transactions)
      db
        .select({ total: sql<number>`COALESCE(SUM(amount), 0)` })
        .from(transactions)
        .where(eq(transactions.status, 'completed')),

      // Waitlist count
      db
        .select({ count: sql<number>`count(*)` })
        .from(waitlistEntries),
    ])

    // Also get pending workers for context
    const [pendingWorkersResult] = await db
      .select({ count: sql<number>`count(*)` })
      .from(workers)
      .where(eq(workers.verificationStatus, 'pending'))

    const stats = {
      workers: {
        approved: Number(workersCountResult[0]?.count) || 0,
        pending: Number(pendingWorkersResult?.count) || 0,
        total: (Number(workersCountResult[0]?.count) || 0) + (Number(pendingWorkersResult?.count) || 0),
      },
      sponsors: {
        total: Number(sponsorsCountResult[0]?.count) || 0,
      },
      blocks: {
        cleaned: Number(blocksCleanedResult[0]?.count) || 0,
        funded: Number(blocksFundedResult[0]?.count) || 0,
        available: 10, // Initial launch blocks
      },
      financial: {
        totalRaised: Number(totalRaisedResult[0]?.total) || 0, // In cents
        workerPayouts: Number(totalRaisedResult[0]?.total) || 0, // 100% goes to workers
      },
      waitlist: {
        total: Number(waitlistCountResult[0]?.count) || 0,
      },
      movement: {
        // Total people in the movement (workers + sponsors + waitlist)
        total:
          (Number(workersCountResult[0]?.count) || 0) +
          (Number(pendingWorkersResult?.count) || 0) +
          (Number(sponsorsCountResult[0]?.count) || 0) +
          (Number(waitlistCountResult[0]?.count) || 0),
      },
    }

    return NextResponse.json({
      success: true,
      stats,
      lastUpdated: new Date().toISOString(),
    })
  } catch (error) {
    console.error('Public stats error:', error)
    // Return zeros on error so the page still renders
    return NextResponse.json({
      success: false,
      stats: {
        workers: { approved: 0, pending: 0, total: 0 },
        sponsors: { total: 0 },
        blocks: { cleaned: 0, funded: 0, available: 10 },
        financial: { totalRaised: 0, workerPayouts: 0 },
        waitlist: { total: 0 },
        movement: { total: 0 },
      },
      lastUpdated: new Date().toISOString(),
    })
  }
}
