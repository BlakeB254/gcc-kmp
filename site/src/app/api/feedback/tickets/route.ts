import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { feedbackTickets, workers } from '@/db/schema'
import { eq, desc, and } from 'drizzle-orm'
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

// GET /api/feedback/tickets - List user's tickets
export async function GET(request: NextRequest) {
  try {
    const user = await getCurrentUser(request)
    if (!user) {
      return NextResponse.json({ error: 'Not authenticated' }, { status: 401 })
    }

    const tickets = await db
      .select()
      .from(feedbackTickets)
      .where(
        and(
          eq(feedbackTickets.userType, user.type),
          eq(feedbackTickets.userId, user.id)
        )
      )
      .orderBy(desc(feedbackTickets.createdAt))

    return NextResponse.json({
      success: true,
      tickets: tickets.map((t) => ({
        id: t.id,
        category: t.category,
        subject: t.subject,
        description: t.description,
        status: t.status,
        priority: t.priority,
        createdAt: t.createdAt,
        updatedAt: t.updatedAt,
        resolvedAt: t.resolvedAt,
      })),
    })
  } catch (error) {
    console.error('Get feedback tickets error:', error)
    return NextResponse.json({ error: 'Failed to get tickets' }, { status: 500 })
  }
}

// POST /api/feedback/tickets - Create a new ticket
export async function POST(request: NextRequest) {
  try {
    const user = await getCurrentUser(request)
    if (!user) {
      return NextResponse.json({ error: 'Not authenticated' }, { status: 401 })
    }

    const body = await request.json()
    const { category, subject, description } = body

    // Validate required fields
    if (!category || !subject || !description) {
      return NextResponse.json(
        { error: 'Category, subject, and description are required' },
        { status: 400 }
      )
    }

    // Validate category
    const validCategories = [
      'safety_concern',
      'app_issue',
      'verification_problem',
      'payment_issue',
      'general_improvement',
      'feature_request',
      'other',
    ]
    if (!validCategories.includes(category)) {
      return NextResponse.json({ error: 'Invalid category' }, { status: 400 })
    }

    // Create the ticket
    const [ticket] = await db
      .insert(feedbackTickets)
      .values({
        userType: user.type,
        userId: user.id,
        userEmail: user.email,
        userName: user.name,
        category,
        subject: subject.slice(0, 255),
        description,
        status: 'open',
        priority: category === 'safety_concern' ? 'urgent' : 'medium',
      })
      .returning()

    return NextResponse.json({
      success: true,
      ticket: {
        id: ticket.id,
        category: ticket.category,
        subject: ticket.subject,
        status: ticket.status,
        priority: ticket.priority,
        createdAt: ticket.createdAt,
      },
    })
  } catch (error) {
    console.error('Create feedback ticket error:', error)
    return NextResponse.json({ error: 'Failed to create ticket' }, { status: 500 })
  }
}
