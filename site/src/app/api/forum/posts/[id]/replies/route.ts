import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { forumPosts, forumReplies, workers } from '@/db/schema'
import { eq, sql, and } from 'drizzle-orm'
import { verifyToken } from '@/lib/auth'
import { requireSponsor } from '@/lib/sponsor-auth'

export const runtime = 'nodejs'

// Helper to get current user (worker or sponsor)
async function getCurrentUser(request: NextRequest): Promise<{
  type: 'worker' | 'sponsor'
  id: number
  email: string
  name: string
} | null> {
  // Check worker token first
  const workerToken = request.cookies.get('worker-token')?.value
  if (workerToken) {
    const payload = await verifyToken(workerToken)
    if (payload && payload.type === 'worker') {
      const [worker] = await db
        .select({ id: workers.id, email: workers.email, name: workers.name })
        .from(workers)
        .where(eq(workers.id, payload.id))
        .limit(1)
      if (worker) {
        return { type: 'worker', id: worker.id, email: worker.email, name: worker.name }
      }
    }
  }

  // Check sponsor token
  const sponsor = await requireSponsor(request)
  if (sponsor) {
    return { type: 'sponsor' as const, id: sponsor.id, email: sponsor.email, name: sponsor.name }
  }

  return null
}

// POST /api/forum/posts/[id]/replies - Add a reply to a post
export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const user = await getCurrentUser(request)
    if (!user) {
      return NextResponse.json({ error: 'Not authenticated' }, { status: 401 })
    }

    const { id } = await params
    const postId = parseInt(id, 10)
    if (isNaN(postId)) {
      return NextResponse.json({ error: 'Invalid post ID' }, { status: 400 })
    }

    // Get the post
    const [post] = await db
      .select()
      .from(forumPosts)
      .where(eq(forumPosts.id, postId))
      .limit(1)

    if (!post) {
      return NextResponse.json({ error: 'Post not found' }, { status: 404 })
    }

    // Check if post allows replies
    if (post.status === 'locked' || post.status === 'hidden' || post.status === 'archived') {
      return NextResponse.json({ error: 'This post is locked' }, { status: 400 })
    }

    const body = await request.json()
    const { content, parentReplyId } = body

    if (!content || content.trim().length === 0) {
      return NextResponse.json({ error: 'Content is required' }, { status: 400 })
    }

    if (content.length > 5000) {
      return NextResponse.json({ error: 'Reply too long (max 5000 characters)' }, { status: 400 })
    }

    // Validate parent reply if provided
    if (parentReplyId) {
      const [parentReply] = await db
        .select()
        .from(forumReplies)
        .where(
          and(
            eq(forumReplies.id, parentReplyId),
            eq(forumReplies.postId, postId)
          )
        )
        .limit(1)

      if (!parentReply) {
        return NextResponse.json({ error: 'Parent reply not found' }, { status: 400 })
      }
    }

    const now = new Date().toISOString()

    // Create the reply
    const [reply] = await db
      .insert(forumReplies)
      .values({
        postId,
        authorType: user.type,
        authorId: user.id,
        authorName: user.name,
        parentReplyId: parentReplyId || null,
        content: content.trim(),
        isHidden: false,
        likeCount: 0,
      })
      .returning()

    // Update post reply count and last activity
    await db
      .update(forumPosts)
      .set({
        replyCount: sql`${forumPosts.replyCount} + 1`,
        lastActivityAt: now,
        updatedAt: now,
      })
      .where(eq(forumPosts.id, postId))

    return NextResponse.json({
      success: true,
      reply: {
        id: reply.id,
        authorType: reply.authorType,
        authorId: reply.authorId,
        authorName: reply.authorName,
        parentReplyId: reply.parentReplyId,
        content: reply.content,
        likeCount: 0,
        createdAt: reply.createdAt,
        isOwner: true,
        isLiked: false,
      },
    })
  } catch (error) {
    console.error('Add forum reply error:', error)
    return NextResponse.json({ error: 'Failed to add reply' }, { status: 500 })
  }
}
