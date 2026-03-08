import { NextResponse } from 'next/server'
import { db } from '@/db'
import { workers, jobs, routes, communityReports } from '@/db/schema'
import { eq, sql, or } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'

export const dynamic = 'force-dynamic'

async function safeCount(query: Promise<any[]>): Promise<number> {
  try {
    const [result] = await query
    return result?.total ?? 0
  } catch {
    return 0
  }
}

export async function GET(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    // Worker stats - use separate simple queries to avoid filter clause issues
    const totalWorkers = await safeCount(
      db.select({ total: sql<number>`count(*)::int` }).from(workers)
    )
    const verifiedWorkers = await safeCount(
      db.select({ total: sql<number>`count(*)::int` }).from(workers)
        .where(eq(workers.verificationStatus, 'approved'))
    )
    const pendingVerifications = await safeCount(
      db.select({ total: sql<number>`count(*)::int` }).from(workers)
        .where(eq(workers.verificationStatus, 'pending'))
    )

    // Job stats
    const totalJobs = await safeCount(
      db.select({ total: sql<number>`count(*)::int` }).from(jobs)
    )
    const completedJobs = await safeCount(
      db.select({ total: sql<number>`count(*)::int` }).from(jobs)
        .where(eq(jobs.status, 'completed'))
    )
    const activeJobs = await safeCount(
      db.select({ total: sql<number>`count(*)::int` }).from(jobs)
        .where(eq(jobs.status, 'active'))
    )

    // Route stats
    const totalRoutes = await safeCount(
      db.select({ total: sql<number>`count(*)::int` }).from(routes)
    )

    // Community report / bounty stats
    const totalBounties = await safeCount(
      db.select({ total: sql<number>`count(*)::int` }).from(communityReports)
    )
    const activeBounties = await safeCount(
      db.select({ total: sql<number>`count(*)::int` }).from(communityReports)
        .where(or(
          eq(communityReports.status, 'pending'),
          eq(communityReports.status, 'verified'),
        ))
    )

    return NextResponse.json({ data: {
      totalWorkers,
      verifiedWorkers,
      pendingVerifications,
      totalSponsors: 0,
      totalJobs,
      completedJobs,
      activeJobs,
      totalRevenue: 0,
      totalRoutes,
      totalBlocks: 0,
      totalBounties,
      activeBounties,
      totalDisputes: 0,
      openDisputes: 0,
    } })
  } catch (error) {
    console.error('Error fetching admin stats:', error)
    return NextResponse.json({ error: 'Failed to fetch stats' }, { status: 500 })
  }
}
