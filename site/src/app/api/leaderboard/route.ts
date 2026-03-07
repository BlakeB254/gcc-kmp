import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { desc, eq, sql, and, gt } from 'drizzle-orm'
import { z } from 'zod'

export const runtime = 'nodejs'

const querySchema = z.object({
  scope: z.enum(['global', 'state', 'city', 'neighborhood']).default('global'),
  limit: z.coerce.number().min(1).max(100).default(50),
  // For authenticated requests, we can show the current user's position
  workerId: z.coerce.number().optional(),
})

export async function GET(request: NextRequest) {
  try {
    const searchParams = request.nextUrl.searchParams
    const result = querySchema.safeParse({
      scope: searchParams.get('scope') || 'global',
      limit: searchParams.get('limit') || 50,
      workerId: searchParams.get('workerId'),
    })

    if (!result.success) {
      return NextResponse.json(
        { success: false, error: 'Invalid query parameters' },
        { status: 400 }
      )
    }

    const { scope, limit, workerId } = result.data

    // Base query for approved workers with at least one completed job
    const baseConditions = [
      eq(workers.verificationStatus, 'approved'),
      gt(sql`COALESCE(${workers.statsJobsCompleted}::numeric, 0)`, 0),
    ]

    // Add scope-specific conditions
    let scopeConditions = baseConditions
    if (scope === 'state') {
      scopeConditions = [...baseConditions, eq(workers.addressState, 'IL')]
    } else if (scope === 'city') {
      scopeConditions = [...baseConditions, eq(workers.addressCity, 'Chicago')]
    } else if (scope === 'neighborhood') {
      // For now, filter to North Lawndale
      scopeConditions = [...baseConditions, eq(workers.addressIsNorthLawndale, true)]
    }

    // Get the leaderboard
    const leaderboardData = await db
      .select({
        id: workers.id,
        name: workers.name,
        neighborhood: workers.addressNeighborhood,
        jobsCompleted: workers.statsJobsCompleted,
        totalEarnings: workers.statsTotalEarnings,
        averageRating: workers.statsAverageRating,
        lastJobAt: workers.statsLastJobAt,
        earlyAdopterTier: workers.earlyAdopterTier,
      })
      .from(workers)
      .where(and(...scopeConditions))
      .orderBy(
        desc(sql`COALESCE(${workers.statsJobsCompleted}::numeric, 0)`),
        desc(sql`COALESCE(${workers.statsTotalEarnings}::numeric, 0)`)
      )
      .limit(limit)

    // Format the leaderboard with ranks (field names match Kotlin LeaderboardEntry model)
    const leaderboard = leaderboardData.map((worker, index) => ({
      rank: index + 1,
      workerId: worker.id,
      name: worker.name,
      jobsCompleted: Number(worker.jobsCompleted || 0),
      totalEarnings: Number(worker.totalEarnings || 0),
      averageRating: worker.averageRating ? Number(worker.averageRating) : null,
      earlyAdopterTier: worker.earlyAdopterTier || 'standard',
      badgeCount: 0,
    }))

    // If workerId is provided and they're not in the top N, get their position
    let currentUserPosition = null
    if (workerId) {
      const userInLeaderboard = leaderboard.find((w) => w.workerId === workerId)
      if (!userInLeaderboard) {
        // Get the user's data
        const [userData] = await db
          .select({
            id: workers.id,
            name: workers.name,
            neighborhood: workers.addressNeighborhood,
            jobsCompleted: workers.statsJobsCompleted,
            totalEarnings: workers.statsTotalEarnings,
            averageRating: workers.statsAverageRating,
            lastJobAt: workers.statsLastJobAt,
            earlyAdopterTier: workers.earlyAdopterTier,
          })
          .from(workers)
          .where(eq(workers.id, workerId))
          .limit(1)

        if (userData) {
          // Count how many workers have more jobs/earnings
          const [{ position }] = await db
            .select({
              position: sql<number>`COUNT(*) + 1`,
            })
            .from(workers)
            .where(
              and(
                ...scopeConditions,
                sql`COALESCE(${workers.statsJobsCompleted}::numeric, 0) > COALESCE(${userData.jobsCompleted}::numeric, 0)
                  OR (COALESCE(${workers.statsJobsCompleted}::numeric, 0) = COALESCE(${userData.jobsCompleted}::numeric, 0)
                      AND COALESCE(${workers.statsTotalEarnings}::numeric, 0) > COALESCE(${userData.totalEarnings}::numeric, 0))`
              )
            )

          currentUserPosition = {
            rank: Number(position),
            workerId: userData.id,
            name: userData.name,
            jobsCompleted: Number(userData.jobsCompleted || 0),
            totalEarnings: Number(userData.totalEarnings || 0),
            averageRating: userData.averageRating ? Number(userData.averageRating) : null,
            earlyAdopterTier: userData.earlyAdopterTier || 'standard',
            badgeCount: 0,
          }
        }
      }
    }

    // Get total count for this scope
    const [{ total }] = await db
      .select({
        total: sql<number>`COUNT(*)`,
      })
      .from(workers)
      .where(and(...scopeConditions))

    // Get last updated timestamp (most recent job completion)
    const [lastUpdated] = await db
      .select({
        lastJobAt: workers.statsLastJobAt,
      })
      .from(workers)
      .where(and(...scopeConditions))
      .orderBy(desc(workers.statsLastJobAt))
      .limit(1)

    // Wrap in ApiResponse<List<LeaderboardEntry>> for mobile compatibility
    return NextResponse.json({
      data: leaderboard,
      success: true,
    })
  } catch (error) {
    console.error('Leaderboard API error:', error)
    return NextResponse.json(
      { success: false, error: 'Failed to fetch leaderboard' },
      { status: 500 }
    )
  }
}
