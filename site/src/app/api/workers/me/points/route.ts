/**
 * Worker Points Summary
 * GET /api/workers/me/points — Total points, breakdown by action, recent entries
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { pointsLedger } from '@/db/schema'
import { eq, sql, desc } from 'drizzle-orm'
import { verifyMobileToken } from '@/lib/mobile-auth'

export const runtime = 'nodejs'

async function requireAuth(request: NextRequest) {
  const authHeader = request.headers.get('authorization')
  if (!authHeader?.startsWith('Bearer ')) return null
  return verifyMobileToken(authHeader.substring(7))
}

export async function GET(request: NextRequest) {
  try {
    const auth = await requireAuth(request)
    if (!auth) {
      return NextResponse.json({ data: null, message: 'Not authenticated' }, { status: 401 })
    }

    const { workerId } = auth

    // Aggregate points by action type
    const breakdown = await db
      .select({
        action: pointsLedger.action,
        total: sql<number>`COALESCE(sum(${pointsLedger.points}), 0)::int`,
      })
      .from(pointsLedger)
      .where(eq(pointsLedger.workerId, workerId))
      .groupBy(pointsLedger.action)

    // Recent entries (last 20)
    const recentEntries = await db
      .select()
      .from(pointsLedger)
      .where(eq(pointsLedger.workerId, workerId))
      .orderBy(desc(pointsLedger.createdAt))
      .limit(20)

    const breakdownMap: Record<string, number> = {}
    for (const row of breakdown) {
      breakdownMap[row.action] = row.total
    }

    const totalPoints = Object.values(breakdownMap).reduce((a, b) => a + b, 0)

    return NextResponse.json({
      data: {
        totalPoints,
        breakdown: {
          spotCreated: breakdownMap['spot_created'] ?? 0,
          verificationDirty: breakdownMap['verification_dirty'] ?? 0,
          verificationClean: breakdownMap['verification_clean'] ?? 0,
          volunteerCleanup: breakdownMap['volunteer_cleanup'] ?? 0,
          alreadyCleanReport: breakdownMap['already_clean_report'] ?? 0,
        },
        recentEntries,
      },
    })
  } catch (error) {
    console.error('Points summary error:', error)
    return NextResponse.json({ error: 'Failed to fetch points' }, { status: 500 })
  }
}
