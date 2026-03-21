import { NextResponse } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { verifyMobileToken } from '@/lib/mobile-auth'

export const runtime = 'nodejs'

/** GET /api/workers/me/verification — get current verification status */
export async function GET(request: Request) {
  const authHeader = request.headers.get('Authorization')
  if (!authHeader?.startsWith('Bearer ')) {
    return NextResponse.json({ error: 'Missing authorization' }, { status: 401 })
  }
  const user = await verifyMobileToken(authHeader.slice(7))
  if (!user) {
    return NextResponse.json({ error: 'Invalid token' }, { status: 401 })
  }

  const [worker] = await db
    .select({
      verificationStatus: workers.verificationStatus,
      governmentIdStatus: workers.verificationGovernmentIdStatus,
      proofOfAddressStatus: workers.verificationProofOfAddressStatus,
      reviewNotes: workers.verificationReviewNotes,
    })
    .from(workers)
    .where(eq(workers.id, user.workerId))

  if (!worker) {
    return NextResponse.json({ error: 'Worker not found' }, { status: 404 })
  }

  return NextResponse.json({
    status: worker.verificationStatus || 'pending',
    governmentIdStatus: worker.governmentIdStatus || 'pending',
    proofOfAddressStatus: worker.proofOfAddressStatus || 'pending',
    reviewNotes: worker.reviewNotes || null,
  })
}

/** POST /api/workers/me/verification — submit verification documents */
export async function POST(request: Request) {
  const authHeader = request.headers.get('Authorization')
  if (!authHeader?.startsWith('Bearer ')) {
    return NextResponse.json({ error: 'Missing authorization' }, { status: 401 })
  }
  const user = await verifyMobileToken(authHeader.slice(7))
  if (!user) {
    return NextResponse.json({ error: 'Invalid token' }, { status: 401 })
  }

  const body = await request.json()
  const { governmentIdUrl, proofOfAddressUrl } = body

  if (!governmentIdUrl || !proofOfAddressUrl) {
    return NextResponse.json({ error: 'Both governmentIdUrl and proofOfAddressUrl are required' }, { status: 400 })
  }

  const now = new Date().toISOString()

  await db
    .update(workers)
    .set({
      verificationGovernmentIdStatus: 'pending',
      verificationProofOfAddressStatus: 'pending',
      verificationStatus: 'pending',
      updatedAt: now,
    })
    .where(eq(workers.id, user.workerId))

  return NextResponse.json({ success: true, message: 'Documents submitted for review' })
}
