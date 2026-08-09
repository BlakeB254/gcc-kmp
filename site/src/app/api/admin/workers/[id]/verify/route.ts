import { NextResponse } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'
import { mapWorkerToMobile } from '@/lib/worker-mapper'
import {
  commitPublicDataMutation,
  type PublicDataInvalidator,
} from '@/lib/public-data/invalidation'

export const runtime = 'nodejs'

export function commitWorkerVerification<T>(
  mutation: () => Promise<T>,
  invalidate?: PublicDataInvalidator,
): Promise<T> {
  return commitPublicDataMutation('worker-verification', mutation, invalidate)
}

export async function POST(
  request: Request,
  { params }: { params: Promise<{ id: string }> }
) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const { id } = await params
  const workerId = parseInt(id, 10)
  if (isNaN(workerId)) {
    return NextResponse.json({ error: 'Invalid worker ID' }, { status: 400 })
  }

  try {
    const body = await request.json()
    const { status, governmentIdStatus, proofOfAddressStatus, reviewNotes } = body

    // Build update object with only provided fields
    const updates: Record<string, unknown> = {
      updatedAt: new Date(),
      verificationReviewedAt: new Date(),
    }

    // If approving, verify that required documents have been submitted
    if (status === 'approved') {
      const [worker] = await db.select().from(workers).where(eq(workers.id, workerId)).limit(1)
      if (!worker) {
        return NextResponse.json({ error: 'Worker not found' }, { status: 404 })
      }
      const hasGovId = worker.verificationGovernmentIdId || governmentIdStatus === 'approved'
      const hasProofOfAddress = worker.verificationProofOfAddressId || proofOfAddressStatus === 'approved'
      if (!hasGovId || !hasProofOfAddress) {
        return NextResponse.json(
          { error: 'Cannot approve: worker has not submitted required verification documents (government ID and proof of address)' },
          { status: 400 }
        )
      }
    }

    if (status) updates.verificationStatus = status
    if (governmentIdStatus) updates.verificationGovernmentIdStatus = governmentIdStatus
    if (proofOfAddressStatus) updates.verificationProofOfAddressStatus = proofOfAddressStatus
    if (reviewNotes !== undefined) updates.verificationReviewNotes = reviewNotes

    const updateWorker = () => db.update(workers).set(updates).where(eq(workers.id, workerId))
    if (status) {
      await commitWorkerVerification(updateWorker)
    } else {
      await updateWorker()
    }

    // Fetch updated worker
    const [updated] = await db
      .select()
      .from(workers)
      .where(eq(workers.id, workerId))
      .limit(1)

    if (!updated) {
      return NextResponse.json({ error: 'Worker not found' }, { status: 404 })
    }

    return NextResponse.json({ data: mapWorkerToMobile(updated as Record<string, unknown>) })
  } catch (error) {
    console.error('Error verifying worker:', error)
    return NextResponse.json({ error: 'Failed to verify worker' }, { status: 500 })
  }
}
