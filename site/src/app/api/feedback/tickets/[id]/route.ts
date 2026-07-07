import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { feedbackTickets, feedbackReplies, workers } from '@/db/schema'
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

// GET /api/feedback/tickets/[id] - Get ticket with replies
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
    const ticketId = parseInt(id, 10)
    if (isNaN(ticketId)) {
      return NextResponse.json({ error: 'Invalid ticket ID' }, { status: 400 })
    }

    // Get the ticket
    const [ticket] = await db
      .select()
      .from(feedbackTickets)
      .where(eq(feedbackTickets.id, ticketId))
      .limit(1)

    if (!ticket) {
      return NextResponse.json({ error: 'Ticket not found' }, { status: 404 })
    }

    // Verify ownership
    if (ticket.userType !== user.type || ticket.userId !== user.id) {
      return NextResponse.json({ error: 'Access denied' }, { status: 403 })
    }

    // Get replies (excluding internal notes)
    const replies = await db
      .select()
      .from(feedbackReplies)
      .where(
        and(
          eq(feedbackReplies.ticketId, ticketId),
          eq(feedbackReplies.isInternal, false)
        )
      )
      .orderBy(feedbackReplies.createdAt)

    return NextResponse.json({
      success: true,
      ticket: {
        id: ticket.id,
        category: ticket.category,
        subject: ticket.subject,
        description: ticket.description,
        status: ticket.status,
        priority: ticket.priority,
        resolutionNotes: ticket.resolutionNotes,
        createdAt: ticket.createdAt,
        updatedAt: ticket.updatedAt,
        resolvedAt: ticket.resolvedAt,
      },
      replies: replies.map((r) => ({
        id: r.id,
        isAdminReply: r.isAdminReply,
        replierName: r.replierName,
        message: r.message,
        createdAt: r.createdAt,
      })),
    })
  } catch (error) {
    console.error('Get feedback ticket error:', error)
    return NextResponse.json({ error: 'Failed to get ticket' }, { status: 500 })
  }
}

// POST /api/feedback/tickets/[id] - Add reply to ticket
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
    const ticketId = parseInt(id, 10)
    if (isNaN(ticketId)) {
      return NextResponse.json({ error: 'Invalid ticket ID' }, { status: 400 })
    }

    // Get the ticket
    const [ticket] = await db
      .select()
      .from(feedbackTickets)
      .where(eq(feedbackTickets.id, ticketId))
      .limit(1)

    if (!ticket) {
      return NextResponse.json({ error: 'Ticket not found' }, { status: 404 })
    }

    // Verify ownership
    if (ticket.userType !== user.type || ticket.userId !== user.id) {
      return NextResponse.json({ error: 'Access denied' }, { status: 403 })
    }

    // Check if ticket is closed
    if (ticket.status === 'closed' || ticket.status === 'resolved') {
      return NextResponse.json(
        { error: 'Cannot reply to a closed ticket' },
        { status: 400 }
      )
    }

    const body = await request.json()
    const { message } = body

    if (!message || message.trim().length === 0) {
      return NextResponse.json({ error: 'Message is required' }, { status: 400 })
    }

    // Create the reply
    const [reply] = await db
      .insert(feedbackReplies)
      .values({
        ticketId,
        isAdminReply: false,
        replierName: user.name,
        replierEmail: user.email,
        message: message.trim(),
        isInternal: false,
      })
      .returning()

    // Update ticket status to open if it was waiting for response
    if (ticket.status === 'waiting_response') {
      await db
        .update(feedbackTickets)
        .set({ status: 'open', updatedAt: new Date().toISOString() })
        .where(eq(feedbackTickets.id, ticketId))
    } else {
      // Just update the updatedAt timestamp
      await db
        .update(feedbackTickets)
        .set({ updatedAt: new Date().toISOString() })
        .where(eq(feedbackTickets.id, ticketId))
    }

    return NextResponse.json({
      success: true,
      reply: {
        id: reply.id,
        isAdminReply: reply.isAdminReply,
        replierName: reply.replierName,
        message: reply.message,
        createdAt: reply.createdAt,
      },
    })
  } catch (error) {
    console.error('Add ticket reply error:', error)
    return NextResponse.json({ error: 'Failed to add reply' }, { status: 500 })
  }
}
