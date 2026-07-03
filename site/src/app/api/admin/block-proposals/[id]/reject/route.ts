import { NextResponse } from 'next/server'
import { db } from '@/db'
import { blockProposals } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'

export async function POST(request: Request, { params }: { params: Promise<{ id: string }> }) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const { id } = await params
  const proposalId = parseInt(id, 10)
  if (isNaN(proposalId)) {
    return NextResponse.json({ error: 'Invalid proposal id' }, { status: 400 })
  }

  try {
    const body = await request.json().catch(() => ({}))
    const reason = typeof body?.reason === 'string' ? body.reason : null

    const [proposal] = await db.select().from(blockProposals).where(eq(blockProposals.id, proposalId)).limit(1)
    if (!proposal) {
      return NextResponse.json({ error: 'Proposal not found' }, { status: 404 })
    }
    if (proposal.status !== 'pending') {
      return NextResponse.json({ error: `Proposal already ${proposal.status}` }, { status: 409 })
    }

    await db
      .update(blockProposals)
      .set({
        status: 'rejected',
        reviewedByAdminId: auth.workerId,
        reviewedAt: new Date().toISOString(),
        rejectionReason: reason,
        updatedAt: new Date().toISOString(),
      })
      .where(eq(blockProposals.id, proposalId))

    return NextResponse.json({ ok: true })
  } catch (error) {
    console.error('Error rejecting block proposal:', error)
    return NextResponse.json({ error: 'Failed to reject block proposal' }, { status: 500 })
  }
}
