import { NextResponse } from 'next/server'
import { db } from '@/db'
import { sql } from 'drizzle-orm'

export const runtime = 'nodejs'
export const revalidate = 60 // Revalidate every 60 seconds

export async function GET() {
  try {
    // Get worker stats
    const workersResult = await db.execute(sql`
      SELECT
        COUNT(*) as total_workers,
        COUNT(*) FILTER (WHERE verification_status = 'approved') as approved_workers,
        COALESCE(SUM(stats_jobs_completed::numeric), 0) as total_jobs_completed,
        COALESCE(SUM(stats_total_earnings::numeric), 0) as total_earnings_paid
      FROM workers
    `)

    // Get sponsor stats
    const sponsorsResult = await db.execute(sql`
      SELECT
        COUNT(*) as total_sponsors,
        COALESCE(SUM(stats_total_contributed::numeric), 0) as total_contributed,
        COALESCE(SUM(stats_blocks_funded::numeric), 0) as total_blocks_funded
      FROM sponsors
    `)

    // Get job stats
    const jobsResult = await db.execute(sql`
      SELECT
        COUNT(*) as total_jobs,
        COUNT(*) FILTER (WHERE status = 'completed') as completed_jobs,
        COUNT(*) FILTER (WHERE status = 'in_progress') as active_jobs
      FROM jobs
    `)

    // Get block stats
    const blocksResult = await db.execute(sql`
      SELECT
        COUNT(*) as total_blocks,
        COUNT(*) FILTER (WHERE status = 'completed') as cleaned_blocks,
        COUNT(*) FILTER (WHERE status = 'funded') as funded_blocks,
        COUNT(*) FILTER (WHERE status = 'available') as available_blocks
      FROM blocks
    `)

    // Get waitlist/movement stats
    const movementResult = await db.execute(sql`
      SELECT COUNT(*) as waitlist_count FROM waitlist_entries
    `)

    const workers = workersResult.rows[0] as any
    const sponsors = sponsorsResult.rows[0] as any
    const jobs = jobsResult.rows[0] as any
    const blocks = blocksResult.rows[0] as any
    const movement = movementResult.rows[0] as any

    return NextResponse.json({
      workers: {
        total: Number(workers.total_workers) || 0,
        approved: Number(workers.approved_workers) || 0,
        jobsCompleted: Number(workers.total_jobs_completed) || 0,
        totalEarnings: Number(workers.total_earnings_paid) || 0,
      },
      sponsors: {
        total: Number(sponsors.total_sponsors) || 0,
        totalContributed: Number(sponsors.total_contributed) || 0,
        blocksFunded: Number(sponsors.total_blocks_funded) || 0,
      },
      jobs: {
        total: Number(jobs.total_jobs) || 0,
        completed: Number(jobs.completed_jobs) || 0,
        active: Number(jobs.active_jobs) || 0,
      },
      blocks: {
        total: Number(blocks.total_blocks) || 0,
        cleaned: Number(blocks.cleaned_blocks) || 0,
        funded: Number(blocks.funded_blocks) || 0,
        available: Number(blocks.available_blocks) || 0,
      },
      movement: {
        waitlistCount: Number(movement.waitlist_count) || 0,
      },
    })
  } catch (error) {
    console.error('Impact stats error:', error)
    return NextResponse.json({
      workers: { total: 0, approved: 0, jobsCompleted: 0, totalEarnings: 0 },
      sponsors: { total: 0, totalContributed: 0, blocksFunded: 0 },
      jobs: { total: 0, completed: 0, active: 0 },
      blocks: { total: 0, cleaned: 0, funded: 0, available: 0 },
      movement: { waitlistCount: 0 },
    })
  }
}
