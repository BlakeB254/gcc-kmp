/**
 * One-time admin endpoint to backfill verification timestamps for old spots.
 * Sets lastVerifiedAt = createdAt and nextVerificationDue = createdAt + 24h
 * for spots that have NULL verification timestamps.
 *
 * POST /api/admin/backfill-verification
 */
import { NextResponse } from 'next/server'
import { db } from '@/db'
import { communityReports } from '@/db/schema'
import { isNull, and, eq } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'

export const runtime = 'nodejs'

export async function POST(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    // Find spots with NULL nextVerificationDue that are still active
    const nullSpots = await db
      .select({
        id: communityReports.id,
        createdAt: communityReports.createdAt,
        status: communityReports.status,
      })
      .from(communityReports)
      .where(isNull(communityReports.nextVerificationDue))

    let updated = 0
    for (const spot of nullSpots) {
      const createdAt = new Date(spot.createdAt)
      const nextDue = new Date(createdAt.getTime() + 24 * 60 * 60 * 1000)

      await db
        .update(communityReports)
        .set({
          lastVerifiedAt: createdAt.toISOString(),
          verificationCount: 0,
          nextVerificationDue: nextDue.toISOString(),
          missedVerifications: 0,
        })
        .where(eq(communityReports.id, spot.id))

      updated++
    }

    return NextResponse.json({
      data: { updated, total: nullSpots.length },
    })
  } catch (error) {
    console.error('Backfill verification error:', error)
    return NextResponse.json({ error: 'Failed to backfill' }, { status: 500 })
  }
}
