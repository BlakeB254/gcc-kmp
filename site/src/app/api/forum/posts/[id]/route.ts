import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { forumPosts, forumReplies, forumLikes, workers, sponsors } from '@/db/schema'
import { eq, sql, and, or } from 'drizzle-orm'
import { verifyToken } from '@/lib/auth'

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
  const sponsorToken = request.cookies.get('sponsor-token')?.value
  if (sponsorToken) {
    const payload = await verifyToken(sponsorToken)
    if (payload && payload.type === 'sponsor') {
      const [sponsor] = await db
        .select({ id: sponsors.id, email: sponsors.email, name: sponsors.name })
        .from(sponsors)
        .where(eq(sponsors.id, payload.id))
        .limit(1)
      if (sponsor) {
        return { type: 'sponsor', id: sponsor.id, email: sponsor.email, name: sponsor.name }
      }
    }
  }

  return null
}

// GET /api/forum/posts/[id] - Get a single post with replies
export async function GET(
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

    // Check if post is visible
    if (post.status === 'hidden' || post.status === 'archived') {
      return NextResponse.json({ error: 'Post not available' }, { status: 404 })
    }

    // Increment view count
    await db
      .update(forumPosts)
      .set({ viewCount: sql`${forumPosts.viewCount} + 1` })
      .where(eq(forumPosts.id, postId))

    // Get replies (excluding hidden ones)
    const replies = await db
      .select()
      .from(forumReplies)
      .where(
        and(
          eq(forumReplies.postId, postId),
          eq(forumReplies.isHidden, false)
        )
      )
      .orderBy(forumReplies.createdAt)

    // Check if user has liked this post
    const [postLike] = await db
      .select()
      .from(forumLikes)
      .where(
        and(
          eq(forumLikes.userType, user.type),
          eq(forumLikes.userId, user.id),
          eq(forumLikes.postId, postId)
        )
      )
      .limit(1)

    // Get user's liked reply IDs
    const replyIds = replies.map((r) => r.id)
    const likedReplies = replyIds.length > 0
      ? await db
          .select({ replyId: forumLikes.replyId })
          .from(forumLikes)
          .where(
            and(
              eq(forumLikes.userType, user.type),
              eq(forumLikes.userId, user.id),
              sql`${forumLikes.replyId} IN (${sql.join(replyIds.map(id => sql`${id}`), sql`, `)})`
            )
          )
      : []

    const likedReplyIds = new Set(likedReplies.map((l) => l.replyId))

    return NextResponse.json({
      success: true,
      post: {
        id: post.id,
        authorType: post.authorType,
        authorId: post.authorId,
        authorName: post.authorName,
        category: post.category,
        title: post.title,
        content: post.content,
        status: post.status,
        isPinned: post.isPinned,
        isAnnouncement: post.isAnnouncement,
        viewCount: Number(post.viewCount) + 1, // Include this view
        replyCount: post.replyCount,
        likeCount: post.likeCount,
        lastActivityAt: post.lastActivityAt,
        createdAt: post.createdAt,
        isOwner: post.authorType === user.type && post.authorId === user.id,
        isLiked: !!postLike,
      },
      replies: replies.map((r) => ({
        id: r.id,
        authorType: r.authorType,
        authorId: r.authorId,
        authorName: r.authorName,
        parentReplyId: r.parentReplyId,
        content: r.content,
        likeCount: r.likeCount,
        createdAt: r.createdAt,
        isOwner: r.authorType === user.type && r.authorId === user.id,
        isLiked: likedReplyIds.has(r.id),
      })),
    })
  } catch (error) {
    console.error('Get forum post error:', error)
    return NextResponse.json({ error: 'Failed to get post' }, { status: 500 })
  }
}

// DELETE /api/forum/posts/[id] - Delete own post
export async function DELETE(
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

    // Verify ownership
    if (post.authorType !== user.type || post.authorId !== user.id) {
      return NextResponse.json({ error: 'Access denied' }, { status: 403 })
    }

    // Soft delete by setting status to hidden
    await db
      .update(forumPosts)
      .set({ status: 'hidden', updatedAt: new Date().toISOString() })
      .where(eq(forumPosts.id, postId))

    return NextResponse.json({ success: true })
  } catch (error) {
    console.error('Delete forum post error:', error)
    return NextResponse.json({ error: 'Failed to delete post' }, { status: 500 })
  }
}
