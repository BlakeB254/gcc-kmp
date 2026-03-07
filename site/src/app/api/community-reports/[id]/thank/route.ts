/**
 * Thank Volunteer API
 * POST /api/community-reports/[id]/thank - Thank a volunteer for their cleanup
 */
export const dynamic = 'force-dynamic'

import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { communityReports, communityReportThanks } from '@/db/schema'
import { eq, and, sql } from 'drizzle-orm'
import { verifyWorkerToken } from '@/lib/auth'

interface RouteContext {
  params: Promise<{ id: string }>
}

export async function POST(request: NextRequest, context: RouteContext) {
  try {
    const { id } = await context.params
    const reportId = parseInt(id, 10)

    if (isNaN(reportId)) {
      return NextResponse.json(
        { error: 'Invalid report ID' },
        { status: 400 }
      )
    }

    // Authenticate worker
    const authHeader = request.headers.get('authorization')
    if (!authHeader?.startsWith('Bearer ')) {
      return NextResponse.json(
        { error: 'Authorization required' },
        { status: 401 }
      )
    }

    const token = authHeader.substring(7)
    const worker = await verifyWorkerToken(token)
    if (!worker) {
      return NextResponse.json(
        { error: 'Invalid token' },
        { status: 401 }
      )
    }

    // Check if report exists and is a volunteer cleanup
    const [report] = await db
      .select()
      .from(communityReports)
      .where(eq(communityReports.id, reportId))
      .limit(1)

    if (!report) {
      return NextResponse.json(
        { error: 'Report not found' },
        { status: 404 }
      )
    }

    if (report.type !== 'volunteer_cleanup') {
      return NextResponse.json(
        { error: 'Can only thank volunteer cleanup reports' },
        { status: 400 }
      )
    }

    // Check if user already thanked this report
    const [existingThank] = await db
      .select()
      .from(communityReportThanks)
      .where(
        and(
          eq(communityReportThanks.reportId, reportId),
          eq(communityReportThanks.userType, 'worker'),
          eq(communityReportThanks.userId, worker.id)
        )
      )
      .limit(1)

    if (existingThank) {
      return NextResponse.json(
        { error: 'You have already thanked this volunteer' },
        { status: 400 }
      )
    }

    // Add thank and increment count
    await db.insert(communityReportThanks).values({
      reportId,
      userType: 'worker',
      userId: worker.id,
    })

    await db
      .update(communityReports)
      .set({
        thankCount: sql`${communityReports.thankCount} + 1`,
        updatedAt: new Date().toISOString(),
      })
      .where(eq(communityReports.id, reportId))

    return NextResponse.json({
      success: true,
      message: 'Thank you for appreciating this volunteer!',
    })
  } catch (error) {
    console.error('Thank volunteer error:', error)
    return NextResponse.json(
      { error: 'Failed to thank volunteer' },
      { status: 500 }
    )
  }
}
