import { NextResponse } from 'next/server'
import { db } from '@/db'
import { workers, sponsors, jobs, routes, blocks, communityReports } from '@/db/schema'
import { eq, sql } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'

export async function GET(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const [workerStats] = await db
      .select({
        total: sql<number>`count(*)::int`,
        verified: sql<number>`count(*) filter (where ${workers.verificationStatus} = 'approved')::int`,
        pending: sql<number>`count(*) filter (where ${workers.verificationStatus} = 'pending')::int`,
      })
      .from(workers)

    const [sponsorStats] = await db
      .select({ total: sql<number>`count(*)::int` })
      .from(sponsors)

    const [jobStats] = await db
      .select({
        total: sql<number>`count(*)::int`,
        completed: sql<number>`count(*) filter (where ${jobs.status} = 'completed')::int`,
        active: sql<number>`count(*) filter (where ${jobs.status} = 'active')::int`,
      })
      .from(jobs)

    const [routeStats] = await db
      .select({ total: sql<number>`count(*)::int` })
      .from(routes)

    const [blockStats] = await db
      .select({ total: sql<number>`count(*)::int` })
      .from(blocks)

    const [bountyStats] = await db
      .select({
        total: sql<number>`count(*)::int`,
        active: sql<number>`count(*) filter (where ${communityReports.status} in ('pending', 'verified'))::int`,
      })
      .from(communityReports)

    return NextResponse.json({ data: {
      totalWorkers: workerStats.total,
      verifiedWorkers: workerStats.verified,
      pendingVerifications: workerStats.pending,
      totalSponsors: sponsorStats.total,
      totalJobs: jobStats.total,
      completedJobs: jobStats.completed,
      activeJobs: jobStats.active,
      totalRevenue: 0,
      totalRoutes: routeStats.total,
      totalBlocks: blockStats.total,
      totalBounties: bountyStats.total,
      activeBounties: bountyStats.active,
      totalDisputes: 0,
      openDisputes: 0,
    } })
  } catch (error) {
    console.error('Error fetching admin stats:', error)
    return NextResponse.json({ error: 'Failed to fetch stats' }, { status: 500 })
  }
}
