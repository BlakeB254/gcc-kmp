import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { forumPosts, forumReplies, forumLikes } from '@/db/schema'
import { eq, sql, and } from 'drizzle-orm'
import { verifyToken } from '@/lib/auth'
import { requireSponsor } from '@/lib/sponsor-auth'

export const runtime = 'nodejs'

// Helper to get current user (worker or sponsor)
async function getCurrentUser(request: NextRequest): Promise<{
  type: 'worker' | 'sponsor'
  id: number
} | null> {
  // Check worker token first
  const workerToken = request.cookies.get('worker-token')?.value
  if (workerToken) {
    const payload = await verifyToken(workerToken)
    if (payload && payload.type === 'worker') {
      return { type: 'worker', id: payload.id }
    }
  }

  // Check sponsor token
  const sponsor = await requireSponsor(request)
  if (sponsor) {
    return { type: 'sponsor' as const, id: sponsor.id }
  }

  return null
}

// POST /api/forum/posts/[id]/like - Toggle like on post or reply
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

    const body = await request.json()
    const { replyId } = body // Optional - if provided, like a reply instead of the post

    // Verify post exists
    const [post] = await db
      .select()
      .from(forumPosts)
      .where(eq(forumPosts.id, postId))
      .limit(1)

    if (!post) {
      return NextResponse.json({ error: 'Post not found' }, { status: 404 })
    }

    if (replyId) {
      // Like/unlike a reply
      const [reply] = await db
        .select()
        .from(forumReplies)
        .where(
          and(eq(forumReplies.id, replyId), eq(forumReplies.postId, postId))
        )
        .limit(1)

      if (!reply) {
        return NextResponse.json({ error: 'Reply not found' }, { status: 404 })
      }

      // Check if already liked
      const [existingLike] = await db
        .select()
        .from(forumLikes)
        .where(
          and(
            eq(forumLikes.userType, user.type),
            eq(forumLikes.userId, user.id),
            eq(forumLikes.replyId, replyId)
          )
        )
        .limit(1)

      if (existingLike) {
        // Unlike
        await db
          .delete(forumLikes)
          .where(eq(forumLikes.id, existingLike.id))

        await db
          .update(forumReplies)
          .set({ likeCount: sql`${forumReplies.likeCount} - 1` })
          .where(eq(forumReplies.id, replyId))

        return NextResponse.json({ success: true, liked: false })
      } else {
        // Like
        await db
          .insert(forumLikes)
          .values({
            userType: user.type,
            userId: user.id,
            postId: null,
            replyId,
          })

        await db
          .update(forumReplies)
          .set({ likeCount: sql`${forumReplies.likeCount} + 1` })
          .where(eq(forumReplies.id, replyId))

        return NextResponse.json({ success: true, liked: true })
      }
    } else {
      // Like/unlike the post
      const [existingLike] = await db
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

      if (existingLike) {
        // Unlike
        await db
          .delete(forumLikes)
          .where(eq(forumLikes.id, existingLike.id))

        await db
          .update(forumPosts)
          .set({ likeCount: sql`${forumPosts.likeCount} - 1` })
          .where(eq(forumPosts.id, postId))

        return NextResponse.json({ success: true, liked: false })
      } else {
        // Like
        await db
          .insert(forumLikes)
          .values({
            userType: user.type,
            userId: user.id,
            postId,
            replyId: null,
          })

        await db
          .update(forumPosts)
          .set({ likeCount: sql`${forumPosts.likeCount} + 1` })
          .where(eq(forumPosts.id, postId))

        return NextResponse.json({ success: true, liked: true })
      }
    }
  } catch (error) {
    console.error('Toggle like error:', error)
    return NextResponse.json({ error: 'Failed to toggle like' }, { status: 500 })
  }
}
