import { NextResponse } from 'next/server'
import { db } from '@/db'
import { workers, media } from '@/db/schema'
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
  if (typeof governmentIdUrl !== 'string' || typeof proofOfAddressUrl !== 'string') {
    return NextResponse.json({ error: 'Document URLs must be strings' }, { status: 400 })
  }

  const now = new Date().toISOString()

  // Persist the submitted document references as media rows and link them on
  // the worker, so the admin review flow (/api/admin/workers/:id/documents)
  // can actually find them. Values are either private R2 object keys from
  // /api/workers/me/verification/upload (preferred, e.g.
  // `verification/{workerId}/...jpg`) or legacy public blob URLs.
  const [govMedia] = await db
    .insert(media)
    .values({
      url: governmentIdUrl,
      filename: `verification-submission/${user.workerId}/government_id-${Date.now()}`,
      mimeType: 'image/jpeg',
      category: 'worker_id',
    })
    .returning({ id: media.id })

  const [poaMedia] = await db
    .insert(media)
    .values({
      url: proofOfAddressUrl,
      filename: `verification-submission/${user.workerId}/proof_of_address-${Date.now()}`,
      mimeType: 'image/jpeg',
      category: 'proof_of_address',
    })
    .returning({ id: media.id })

  await db
    .update(workers)
    .set({
      verificationGovernmentIdId: govMedia.id,
      verificationGovernmentIdStatus: 'pending',
      verificationProofOfAddressId: poaMedia.id,
      verificationProofOfAddressStatus: 'pending',
      verificationStatus: 'pending',
      updatedAt: now,
    })
    .where(eq(workers.id, user.workerId))

  return NextResponse.json({ success: true, message: 'Documents submitted for review' })
}
