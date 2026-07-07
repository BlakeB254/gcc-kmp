import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { feedProfileOptIns } from '@/db/schema'
import { requireMobileAuth } from '@/lib/mobile-auth'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

type RouteContext = { params: Promise<{ id: string }> }

// feedItemId is client-supplied (there's no server-side feed_items table to
// validate ownership against — the feed is an in-memory union of several
// tables). Bound it to the synthetic composite-id shape the feed generates
// ("spot_123", "bounty_created_45", "job_7", ...): short, lowercase
// alphanumeric with underscores only.
const FEED_ITEM_ID_PATTERN = /^[a-z0-9_]{1,64}$/

/**
 * POST /api/feed/{id}/opt-in-profile — records a CONSENT row: "the
 * authenticated worker consents to their profile being visible on this feed
 * item". Idempotent per (feedItemId, workerId): repeating the call is a
 * no-op. A different worker opting in on the same item inserts their OWN
 * row — it cannot block or claim anyone else's opt-in (composite unique,
 * not unique-per-item; see the security note on feedProfileOptIns in
 * schema.ts).
 *
 * Render contract (for the future real feed GET): an item's
 * `actorProfileVisible` = an opt-in row exists WHERE worker_id == the
 * item's actor id. NEVER any-row-exists — a stranger's consent row is
 * inert because their consent is irrelevant to rendering someone else's
 * item. Ownership cannot be verified at write time (no server-side feed
 * items), so it MUST be enforced at render time.
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
    if (!FEED_ITEM_ID_PATTERN.test(feedItemId)) {
      return NextResponse.json({ data: null, success: false, message: 'Invalid feed item ID' }, { status: 400 })
    }

    await db.insert(feedProfileOptIns)
      .values({ feedItemId, workerId: auth.workerId })
      .onConflictDoNothing({ target: [feedProfileOptIns.feedItemId, feedProfileOptIns.workerId] })

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
