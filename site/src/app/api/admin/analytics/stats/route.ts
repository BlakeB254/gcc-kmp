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
    // Run all independent count queries in parallel
    const [
      totalWorkers, verifiedWorkers, pendingVerifications,
      totalJobs, completedJobs, activeJobs,
      totalRoutes,
      totalSpots, activeSpots,
    ] = await Promise.all([
      safeCount(db.select({ total: sql<number>`count(*)::int` }).from(workers)),
      safeCount(db.select({ total: sql<number>`count(*)::int` }).from(workers).where(eq(workers.verificationStatus, 'approved'))),
      safeCount(db.select({ total: sql<number>`count(*)::int` }).from(workers).where(eq(workers.verificationStatus, 'pending'))),
      safeCount(db.select({ total: sql<number>`count(*)::int` }).from(jobs)),
      safeCount(db.select({ total: sql<number>`count(*)::int` }).from(jobs).where(eq(jobs.status, 'completed'))),
      safeCount(db.select({ total: sql<number>`count(*)::int` }).from(jobs).where(or(eq(jobs.status, 'available'), eq(jobs.status, 'in_progress'), eq(jobs.status, 'claimed')))),
      safeCount(db.select({ total: sql<number>`count(*)::int` }).from(routes)),
      safeCount(db.select({ total: sql<number>`count(*)::int` }).from(communityReports)),
      safeCount(db.select({ total: sql<number>`count(*)::int` }).from(communityReports).where(or(eq(communityReports.status, 'pending'), eq(communityReports.status, 'verified')))),
    ])

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
      totalSpots,
      activeSpots,
      totalBounties: 0,
      activeBounties: 0,
      totalDisputes: 0,
      openDisputes: 0,
    } })
  } catch (error) {
    console.error('Error fetching admin stats:', error)
    return NextResponse.json({ error: 'Failed to fetch stats' }, { status: 500 })
  }
}
