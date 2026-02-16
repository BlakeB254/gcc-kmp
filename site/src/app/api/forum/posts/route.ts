import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { forumPosts, workers, sponsors } from '@/db/schema'
import { eq, desc, sql, and, or } from 'drizzle-orm'
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

// GET /api/forum/posts - List forum posts
export async function GET(request: NextRequest) {
  try {
    const user = await getCurrentUser(request)
    if (!user) {
      return NextResponse.json({ error: 'Not authenticated' }, { status: 401 })
    }

    const { searchParams } = new URL(request.url)
    const category = searchParams.get('category')
    const page = parseInt(searchParams.get('page') || '1', 10)
    const limit = Math.min(parseInt(searchParams.get('limit') || '20', 10), 50)
    const offset = (page - 1) * limit

    // Build query conditions
    const conditions = [
      or(eq(forumPosts.status, 'active'), eq(forumPosts.status, 'locked'))
    ]

    if (category && category !== 'all') {
      conditions.push(eq(forumPosts.category, category as any))
    }

    // Get posts with pagination
    const posts = await db
      .select({
        id: forumPosts.id,
        authorType: forumPosts.authorType,
        authorId: forumPosts.authorId,
        authorName: forumPosts.authorName,
        category: forumPosts.category,
        title: forumPosts.title,
        content: forumPosts.content,
        status: forumPosts.status,
        isPinned: forumPosts.isPinned,
        isAnnouncement: forumPosts.isAnnouncement,
        viewCount: forumPosts.viewCount,
        replyCount: forumPosts.replyCount,
        likeCount: forumPosts.likeCount,
        lastActivityAt: forumPosts.lastActivityAt,
        createdAt: forumPosts.createdAt,
      })
      .from(forumPosts)
      .where(and(...conditions))
      .orderBy(
        desc(forumPosts.isPinned),
        desc(forumPosts.isAnnouncement),
        desc(forumPosts.lastActivityAt)
      )
      .limit(limit)
      .offset(offset)

    // Get total count for pagination
    const [{ count }] = await db
      .select({ count: sql<number>`count(*)` })
      .from(forumPosts)
      .where(and(...conditions))

    return NextResponse.json({
      success: true,
      posts: posts.map((p) => ({
        ...p,
        content: p.content.slice(0, 300) + (p.content.length > 300 ? '...' : ''), // Preview only
        isOwner: p.authorType === user.type && p.authorId === user.id,
      })),
      pagination: {
        page,
        limit,
        total: Number(count),
        totalPages: Math.ceil(Number(count) / limit),
      },
    })
  } catch (error) {
    console.error('Get forum posts error:', error)
    return NextResponse.json({ error: 'Failed to get posts' }, { status: 500 })
  }
}

// POST /api/forum/posts - Create a new post
export async function POST(request: NextRequest) {
  try {
    const user = await getCurrentUser(request)
    if (!user) {
      return NextResponse.json({ error: 'Not authenticated' }, { status: 401 })
    }

    const body = await request.json()
    const { category, title, content } = body

    // Validate required fields
    if (!category || !title || !content) {
      return NextResponse.json(
        { error: 'Category, title, and content are required' },
        { status: 400 }
      )
    }

    // Validate category
    const validCategories = [
      'general',
      'worker_tips',
      'sponsor_stories',
      'feature_requests',
      'local_news',
    ]
    // Announcements are admin-only
    if (!validCategories.includes(category)) {
      return NextResponse.json({ error: 'Invalid category' }, { status: 400 })
    }

    if (title.length > 255) {
      return NextResponse.json({ error: 'Title too long (max 255 characters)' }, { status: 400 })
    }

    if (content.length > 10000) {
      return NextResponse.json({ error: 'Content too long (max 10000 characters)' }, { status: 400 })
    }

    // Create the post
    const now = new Date().toISOString()
    const [post] = await db
      .insert(forumPosts)
      .values({
        authorType: user.type,
        authorId: user.id,
        authorName: user.name,
        category,
        title: title.trim(),
        content: content.trim(),
        status: 'active',
        isPinned: false,
        isAnnouncement: false,
        viewCount: 0,
        replyCount: 0,
        likeCount: 0,
        lastActivityAt: now,
      })
      .returning()

    return NextResponse.json({
      success: true,
      post: {
        id: post.id,
        category: post.category,
        title: post.title,
        authorName: post.authorName,
        createdAt: post.createdAt,
      },
    })
  } catch (error) {
    console.error('Create forum post error:', error)
    return NextResponse.json({ error: 'Failed to create post' }, { status: 500 })
  }
}
