import { NextResponse } from 'next/server'
import { db } from '@/db'
import { workers, sponsors, jobs, bounties } from '@/db/schema'
import { gte, inArray } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'

export const dynamic = 'force-dynamic'

type DailyStats = {
  date: string
  jobsCompleted: number
  jobsCreated: number
  revenue: number
  newWorkers: number
  newSponsors: number
  bountiesPosted: number
  bountiesClaimed: number
}

/** "2026-07-06T00:00:00Z" -> "2026-07-06" (UTC day bucket). */
function dayKey(ts: unknown): string | null {
  if (!ts) return null
  return new Date(ts as string).toISOString().slice(0, 10)
}

function bump(map: Map<string, number>, key: string | null, amount = 1) {
  if (!key) return
  map.set(key, (map.get(key) ?? 0) + amount)
}

async function safe<T>(query: Promise<T[]>): Promise<T[]> {
  try {
    return await query
  } catch {
    return []
  }
}

/**
 * GET /api/admin/analytics/daily?days=N — per-day counts for the admin
 * overview tab's trend chart, matching AdminDailyStats (AdminPanelViewModel
 * loadTabData OVERVIEW). Read-only aggregates over existing tables (workers,
 * sponsors, jobs, bounties) grouped in application code — no new tables, no
 * SQL-side date bucketing (keeps this readable and easy to verify by hand).
 */
export async function GET(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const { searchParams } = new URL(request.url)
    const days = Math.min(Math.max(parseInt(searchParams.get('days') || '30', 10) || 30, 1), 365)
    const cutoff = new Date(Date.now() - days * 24 * 60 * 60 * 1000).toISOString()

    const [workerRows, sponsorRows, jobRows, bountyRows] = await Promise.all([
      safe(db.select({ createdAt: workers.createdAt }).from(workers).where(gte(workers.createdAt, cutoff))),
      safe(db.select({ createdAt: sponsors.createdAt }).from(sponsors).where(gte(sponsors.createdAt, cutoff))),
      safe(db.select({
        createdAt: jobs.createdAt,
        completedAt: jobs.completedAt,
        payoutAmount: jobs.payoutAmount,
      }).from(jobs).where(gte(jobs.createdAt, cutoff))),
      safe(db.select({
        createdAt: bounties.createdAt,
        updatedAt: bounties.updatedAt,
        status: bounties.status,
      }).from(bounties).where(gte(bounties.createdAt, cutoff))),
    ])

    // Bounties claimed/completed can have updatedAt outside the createdAt
    // window (posted before the cutoff, resolved after it) — fetch those
    // separately so the "claimed" count isn't silently missing rows.
    const recentlyResolvedBounties = await safe(
      db.select({ updatedAt: bounties.updatedAt, status: bounties.status })
        .from(bounties)
        .where(inArray(bounties.status, ['claimed', 'completed']))
    )

    const newWorkers = new Map<string, number>()
    const newSponsors = new Map<string, number>()
    const jobsCreated = new Map<string, number>()
    const jobsCompleted = new Map<string, number>()
    const revenue = new Map<string, number>()
    const bountiesPosted = new Map<string, number>()
    const bountiesClaimed = new Map<string, number>()

    for (const row of workerRows) bump(newWorkers, dayKey(row.createdAt))
    for (const row of sponsorRows) bump(newSponsors, dayKey(row.createdAt))
    for (const row of jobRows) {
      bump(jobsCreated, dayKey(row.createdAt))
      if (row.completedAt) {
        bump(jobsCompleted, dayKey(row.completedAt))
        bump(revenue, dayKey(row.completedAt), Math.round(Number(row.payoutAmount ?? 0)))
      }
    }
    for (const row of bountyRows) bump(bountiesPosted, dayKey(row.createdAt))
    for (const row of recentlyResolvedBounties) {
      const key = dayKey(row.updatedAt)
      if (key && key >= cutoff.slice(0, 10)) bump(bountiesClaimed, key)
    }

    const allDates = new Set<string>([
      ...newWorkers.keys(), ...newSponsors.keys(), ...jobsCreated.keys(), ...jobsCompleted.keys(),
      ...bountiesPosted.keys(), ...bountiesClaimed.keys(),
    ])

    const data: DailyStats[] = Array.from(allDates).sort().map((date) => ({
      date,
      jobsCompleted: jobsCompleted.get(date) ?? 0,
      jobsCreated: jobsCreated.get(date) ?? 0,
      revenue: revenue.get(date) ?? 0,
      newWorkers: newWorkers.get(date) ?? 0,
      newSponsors: newSponsors.get(date) ?? 0,
      bountiesPosted: bountiesPosted.get(date) ?? 0,
      bountiesClaimed: bountiesClaimed.get(date) ?? 0,
    }))

    return NextResponse.json({ data })
  } catch (error) {
    console.error('Error fetching admin daily stats:', error)
    return NextResponse.json({ error: 'Failed to fetch daily stats' }, { status: 500 })
  }
}
