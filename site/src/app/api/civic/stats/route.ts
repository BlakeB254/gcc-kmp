/**
 * GET /api/civic/stats — the capture-rate analytics of the 311 civic loop
 * (docs/superpowers/specs/2026-07-10-chicago-311-civic-loop-design.md §7):
 * per community area, how many GCC-relevant 311 reports are open and how
 * many we hold visual evidence for. Public read, same precedent as the
 * other aggregate map endpoints.
 */
import { NextResponse } from 'next/server'
import { db } from '@/db'
import { sql } from 'drizzle-orm'

export const dynamic = 'force-dynamic'

export async function GET() {
  try {
    const byArea = await db.execute(sql`
      SELECT r.community_area                                   AS "communityArea",
             count(*) FILTER (WHERE r.status = 'Open')::int      AS "openReports",
             count(*)::int                                        AS "totalReports",
             count(DISTINCT e.sr_number)::int                     AS "reportsWithEvidence",
             count(*) FILTER (WHERE r.status = 'Open'
                              AND e.sr_number IS NOT NULL)::int   AS "openWithEvidence"
      FROM civic_reports r
      LEFT JOIN civic_report_evidence e ON e.sr_number = r.sr_number
      WHERE r.community_area IS NOT NULL
      GROUP BY r.community_area
      ORDER BY "openReports" DESC
    `)
    const byType = await db.execute(sql`
      SELECT r.sr_type                                            AS "srType",
             count(*) FILTER (WHERE r.status = 'Open')::int       AS "openReports",
             count(DISTINCT e.sr_number)::int                      AS "reportsWithEvidence"
      FROM civic_reports r
      LEFT JOIN civic_report_evidence e ON e.sr_number = r.sr_number
      GROUP BY r.sr_type
      ORDER BY "openReports" DESC
    `)
    return NextResponse.json({ data: { byCommunityArea: byArea.rows, byType: byType.rows }, success: true })
  } catch (error) {
    console.error('GET /api/civic/stats error:', error)
    return NextResponse.json({ data: null, success: false, message: 'Failed to build stats' }, { status: 500 })
  }
}
