import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { feedProfileOptIns } from '@/db/schema'
import { requireMobileAuth } from '@/lib/mobile-auth'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

type RouteContext = { params: Promise<{ id: string }> }

/**
 * POST /api/feed/{id}/opt-in-profile — the authenticated worker reveals
 * their profile on this one feed event. Idempotent: a repeat call on the
 * same feed item is a no-op (feed_item_id is UNIQUE).
 *
 * The client (FeedViewModel.optInProfile) only reads the response to
 * confirm success — it applies `actorProfileVisible = true` optimistically
 * to its own local item — so the body only needs to satisfy FeedItem's
 * required fields.
 */
export async function POST(request: NextRequest, context: RouteContext) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json({ data: null, success: false, message: 'Not authenticated' }, { status: 401 })
  }

  try {
    const { id: feedItemId } = await context.params

    await db.insert(feedProfileOptIns)
      .values({ feedItemId, workerId: auth.workerId })
      .onConflictDoNothing({ target: feedProfileOptIns.feedItemId })

    return NextResponse.json({
      data: {
        id: feedItemId,
        type: 'spot_report',
        actorName: '',
        title: '',
        description: '',
        actorProfileVisible: true,
        timestamp: new Date().toISOString(),
      },
      success: true,
    })
  } catch (error) {
    console.error('POST /api/feed/[id]/opt-in-profile error:', error)
    return NextResponse.json({ data: null, success: false, message: 'Failed to opt in' }, { status: 500 })
  }
}
