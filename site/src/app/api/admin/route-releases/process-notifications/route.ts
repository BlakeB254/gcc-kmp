/**
 * POST /api/admin/route-releases/process-notifications
 * Cron-style: process every due batch (advance waves, expire offers). Notification
 * dispatch is not yet implemented, so notification counters are reported as zero.
 * Can be wired to a Vercel Cron later.
 */
import { NextRequest, NextResponse } from 'next/server'
import { requireAdmin } from '@/lib/admin-auth'
import { processDueBatches } from '@/lib/route-queue'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

const EMPTY_NOTIFS = { scheduled: 0, sent: 0, skipped: 0, failed: 0 }

export async function POST(request: NextRequest) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth
  try {
    const releases = await processDueBatches()
    return NextResponse.json({
      data: {
        releases,
        notificationsScheduled: EMPTY_NOTIFS,
        notificationsDispatched: EMPTY_NOTIFS,
      },
      success: true,
    })
  } catch (error) {
    console.error('POST /api/admin/route-releases/process-notifications error:', error)
    return NextResponse.json({ error: 'Failed to process releases', success: false }, { status: 500 })
  }
}
