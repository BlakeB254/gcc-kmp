import { NextResponse } from 'next/server'
import { db } from '@/db'
import { blockCompletionReports, workers } from '@/db/schema'
import { desc, eq, sql } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

/**
 * GET /api/admin/reports
 *
 * Admin-only. There's no single-report list endpoint from Task 1 (it only
 * built the job-keyed GET and the admin regenerate POST), so this is a new
 * route for the admin reports list page (Task 3). Deliberately lean: skips
 * the jsonb blobs (gpsTrack, areasCleaned, imagePairs, countsByType) — the
 * list table only needs summary numbers plus an image-pair count computed
 * in SQL via jsonb_array_length so the full imagePairs array never leaves
 * the database for this endpoint. The detail view fetches the full row via
 * the existing GET /api/jobs/[id]/completion-report (admin-any-job branch).
 */
export async function GET(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const rows = await db
      .select({
        id: blockCompletionReports.id,
        jobId: blockCompletionReports.jobId,
        routeId: blockCompletionReports.routeId,
        workerId: blockCompletionReports.workerId,
        workerName: workers.name,
        startedAt: blockCompletionReports.startedAt,
        completedAt: blockCompletionReports.completedAt,
        durationSeconds: blockCompletionReports.durationSeconds,
        totalEstimatedWeightLbs: blockCompletionReports.totalEstimatedWeightLbs,
        cleanedEstimatedWeightLbs: blockCompletionReports.cleanedEstimatedWeightLbs,
        imagePairCount: sql<number>`jsonb_array_length(coalesce(${blockCompletionReports.imagePairs}, '[]'::jsonb))`,
        createdAt: blockCompletionReports.createdAt,
      })
      .from(blockCompletionReports)
      .leftJoin(workers, eq(blockCompletionReports.workerId, workers.id))
      .orderBy(desc(blockCompletionReports.completedAt))

    return NextResponse.json({ data: rows, success: true })
  } catch (error) {
    console.error('GET /api/admin/reports error:', error)
    return NextResponse.json(
      { error: 'Failed to fetch completion reports', success: false },
      { status: 500 }
    )
  }
}
