import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { feedComments, workers } from '@/db/schema'
import { asc, eq } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

// Comments live on synthetic feed-item ids (e.g. "spot_123") rather than a
// materialized feed_items table — see schema.ts comment on feedComments.
const MAX_COMMENT_LENGTH = 1000

type RouteContext = { params: Promise<{ id: string }> }

/** GET /api/feed/{id}/comments — list comments for a feed item, oldest first. */
export async function GET(request: NextRequest, context: RouteContext) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json({ data: null, success: false, message: 'Not authenticated' }, { status: 401 })
  }

  try {
    const { id: feedItemId } = await context.params
    const { searchParams } = new URL(request.url)
    const limit = Math.min(Math.max(parseInt(searchParams.get('limit') || '50', 10) || 50, 1), 200)

    const rows = await db
      .select({
        id: feedComments.id,
        feedItemId: feedComments.feedItemId,
        authorName: feedComments.authorName,
        authorTier: workers.earlyAdopterTier,
        text: feedComments.text,
        createdAt: feedComments.createdAt,
      })
      .from(feedComments)
      .leftJoin(workers, eq(feedComments.authorId, workers.id))
      .where(eq(feedComments.feedItemId, feedItemId))
      .orderBy(asc(feedComments.createdAt))
      .limit(limit)

    const data = rows.map((row) => ({
      id: String(row.id),
      feedItemId: row.feedItemId,
      authorName: row.authorName,
      authorTier: row.authorTier || 'standard',
      text: row.text,
      timestamp: new Date(row.createdAt).toISOString(),
    }))

    return NextResponse.json({ data, success: true })
  } catch (error) {
    console.error('GET /api/feed/[id]/comments error:', error)
    return NextResponse.json({ data: null, success: false, message: 'Failed to fetch comments' }, { status: 500 })
  }
}

/** POST /api/feed/{id}/comments — add a comment as the authenticated worker. */
export async function POST(request: NextRequest, context: RouteContext) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json({ data: null, success: false, message: 'Not authenticated' }, { status: 401 })
  }

  try {
    const { id: feedItemId } = await context.params
    const body = await request.json().catch(() => null)
    const text = typeof body?.text === 'string' ? body.text.trim() : ''
    if (!text) {
      return NextResponse.json({ data: null, success: false, message: 'Comment text required' }, { status: 400 })
    }
    if (text.length > MAX_COMMENT_LENGTH) {
      return NextResponse.json(
        { data: null, success: false, message: `Comment must be ${MAX_COMMENT_LENGTH} characters or fewer` },
        { status: 400 }
      )
    }

    const [worker] = await db.select({ name: workers.name, earlyAdopterTier: workers.earlyAdopterTier })
      .from(workers)
      .where(eq(workers.id, auth.workerId))
      .limit(1)
    const authorName = worker?.name || 'Anonymous'

    const [inserted] = await db.insert(feedComments).values({
      feedItemId,
      authorId: auth.workerId,
      authorName,
      text,
    }).returning({ id: feedComments.id, createdAt: feedComments.createdAt })

    return NextResponse.json({
      data: {
        id: String(inserted.id),
        feedItemId,
        authorName,
        authorTier: worker?.earlyAdopterTier || 'standard',
        text,
        timestamp: new Date(inserted.createdAt).toISOString(),
      },
      success: true,
    }, { status: 201 })
  } catch (error) {
    console.error('POST /api/feed/[id]/comments error:', error)
    return NextResponse.json({ data: null, success: false, message: 'Failed to add comment' }, { status: 500 })
  }
}
