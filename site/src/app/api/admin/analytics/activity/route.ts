import { NextResponse } from 'next/server'
import { db } from '@/db'
import { workers, sponsors, jobs, bounties } from '@/db/schema'
import { desc, eq } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'

export const dynamic = 'force-dynamic'

type ActivityItem = {
  id: number
  type: string
  description: string
  actorName: string | null
  timestamp: string
}

async function safeRows<T>(query: Promise<T[]>): Promise<T[]> {
  try {
    return await query
  } catch {
    return []
  }
}

/**
 * GET /api/admin/analytics/activity — recent activity feed for the admin
 * overview tab. Aggregates events from existing tables (workers, sponsors,
 * jobs, bounties) rather than a dedicated activity-log table, matching
 * AdminActivityItem's shape (AdminPanelViewModel.kt loadTabData OVERVIEW).
 * `id` is a synthetic per-response sequence number for list-key purposes,
 * not a stable row id (items are merged from several source tables).
 */
export async function GET(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const { searchParams } = new URL(request.url)
    const limit = Math.min(Math.max(parseInt(searchParams.get('limit') || '20', 10) || 20, 1), 100)

    const items: Omit<ActivityItem, 'id'>[] = []

    const recentWorkers = await safeRows(
      db.select({ name: workers.name, createdAt: workers.createdAt })
        .from(workers)
        .orderBy(desc(workers.createdAt))
        .limit(limit)
    )
    for (const row of recentWorkers) {
      items.push({
        type: 'worker_registered',
        description: `${row.name} registered as a worker`,
        actorName: row.name,
        timestamp: new Date(row.createdAt).toISOString(),
      })
    }

    const recentVerified = await safeRows(
      db.select({ name: workers.name, verificationReviewedAt: workers.verificationReviewedAt })
        .from(workers)
        .where(eq(workers.verificationStatus, 'approved'))
        .orderBy(desc(workers.verificationReviewedAt))
        .limit(limit)
    )
    for (const row of recentVerified) {
      if (!row.verificationReviewedAt) continue
      items.push({
        type: 'worker_verified',
        description: `${row.name} was verified`,
        actorName: row.name,
        timestamp: new Date(row.verificationReviewedAt).toISOString(),
      })
    }

    const recentSponsors = await safeRows(
      db.select({ name: sponsors.name, createdAt: sponsors.createdAt })
        .from(sponsors)
        .orderBy(desc(sponsors.createdAt))
        .limit(limit)
    )
    for (const row of recentSponsors) {
      items.push({
        type: 'sponsor_registered',
        description: `${row.name} registered as a sponsor`,
        actorName: row.name,
        timestamp: new Date(row.createdAt).toISOString(),
      })
    }

    const recentJobsCompleted = await safeRows(
      db.select({ workerName: workers.name, completedAt: jobs.completedAt, updatedAt: jobs.updatedAt })
        .from(jobs)
        .leftJoin(workers, eq(jobs.workerId, workers.id))
        .where(eq(jobs.status, 'completed'))
        .orderBy(desc(jobs.completedAt))
        .limit(limit)
    )
    for (const row of recentJobsCompleted) {
      const ts = row.completedAt || row.updatedAt
      if (!ts) continue
      items.push({
        type: 'job_completed',
        description: `${row.workerName || 'A worker'} completed a cleaning job`,
        actorName: row.workerName,
        timestamp: new Date(ts).toISOString(),
      })
    }

    const recentBountiesPosted = await safeRows(
      db.select({ title: bounties.title, posterName: bounties.posterName, createdAt: bounties.createdAt })
        .from(bounties)
        .orderBy(desc(bounties.createdAt))
        .limit(limit)
    )
    for (const row of recentBountiesPosted) {
      items.push({
        type: 'bounty_posted',
        description: `New bounty posted: ${row.title}`,
        actorName: row.posterName,
        timestamp: new Date(row.createdAt).toISOString(),
      })
    }

    const recentBountiesCompleted = await safeRows(
      db.select({ title: bounties.title, updatedAt: bounties.updatedAt })
        .from(bounties)
        .where(eq(bounties.status, 'completed'))
        .orderBy(desc(bounties.updatedAt))
        .limit(limit)
    )
    for (const row of recentBountiesCompleted) {
      items.push({
        type: 'bounty_completed',
        description: `Bounty completed: ${row.title}`,
        actorName: null,
        timestamp: new Date(row.updatedAt).toISOString(),
      })
    }

    items.sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime())

    const data: ActivityItem[] = items.slice(0, limit).map((item, index) => ({
      id: index + 1,
      ...item,
    }))

    return NextResponse.json({ data })
  } catch (error) {
    console.error('Error fetching admin activity:', error)
    return NextResponse.json({ error: 'Failed to fetch activity' }, { status: 500 })
  }
}
