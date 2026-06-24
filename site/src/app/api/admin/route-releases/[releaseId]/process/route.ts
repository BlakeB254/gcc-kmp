/**
 * POST /api/admin/route-releases/:releaseId/process
 * Manually run the next wave for a batch (the cron-equivalent, admin-triggered).
 */
import { NextRequest, NextResponse } from 'next/server'
import { requireAdmin } from '@/lib/admin-auth'
import { processBatch } from '@/lib/route-queue'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

const EMPTY_NOTIFS = { scheduled: 0, sent: 0, skipped: 0, failed: 0 }

export async function POST(request: NextRequest, context: { params: Promise<{ releaseId: string }> }) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth
  try {
    const { releaseId: releaseIdStr } = await context.params
    const releaseId = parseInt(releaseIdStr, 10)
    if (Number.isNaN(releaseId)) {
      return NextResponse.json({ error: 'Invalid release ID', success: false }, { status: 400 })
    }
    const result = await processBatch(releaseId)
    return NextResponse.json({
      data: {
        releases: [result],
        notificationsScheduled: EMPTY_NOTIFS,
        notificationsDispatched: EMPTY_NOTIFS,
      },
      success: true,
    })
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Failed to process release'
    console.error('POST /api/admin/route-releases/[releaseId]/process error:', error)
    const status = /not found/.test(message) ? 404 : 500
    return NextResponse.json({ error: message, success: false }, { status })
  }
}
