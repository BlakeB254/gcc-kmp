import { NextResponse } from 'next/server'
import { db } from '@/db'
import { workers, media } from '@/db/schema'
import { eq, inArray } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'

export const runtime = 'nodejs'

/**
 * GET /api/admin/workers/:id/documents
 * Returns the verification document image URLs for a worker.
 * Resolves the media IDs stored in verification_government_id_id
 * and verification_proof_of_address_id to actual Vercel Blob URLs.
 */
export async function GET(
  request: Request,
  { params }: { params: Promise<{ id: string }> }
) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const { id } = await params
  const workerId = parseInt(id, 10)
  if (isNaN(workerId)) {
    return NextResponse.json(
      { data: null, success: false, message: 'Invalid worker ID' },
      { status: 400 },
    )
  }

  try {
    const [worker] = await db
      .select({
        governmentIdMediaId: workers.verificationGovernmentIdId,
        proofOfAddressMediaId: workers.verificationProofOfAddressId,
      })
      .from(workers)
      .where(eq(workers.id, workerId))
      .limit(1)

    if (!worker) {
      return NextResponse.json(
        { data: null, success: false, message: 'Worker not found' },
        { status: 404 },
      )
    }

    // Collect non-null media IDs
    const mediaIds: number[] = []
    if (worker.governmentIdMediaId) mediaIds.push(worker.governmentIdMediaId)
    if (worker.proofOfAddressMediaId) mediaIds.push(worker.proofOfAddressMediaId)

    // Resolve media IDs to URLs in a single query
    const mediaRecords = mediaIds.length > 0
      ? await db
          .select({ id: media.id, url: media.url, filename: media.filename, mimeType: media.mimeType })
          .from(media)
          .where(inArray(media.id, mediaIds))
      : []

    const mediaMap = Object.fromEntries(mediaRecords.map((m) => [m.id, m]))

    const govMedia = worker.governmentIdMediaId ? mediaMap[worker.governmentIdMediaId] : null
    const poaMedia = worker.proofOfAddressMediaId ? mediaMap[worker.proofOfAddressMediaId] : null

    return NextResponse.json({
      data: {
        governmentId: govMedia ? {
          url: govMedia.url,
          filename: govMedia.filename,
          mimeType: govMedia.mimeType,
        } : null,
        proofOfAddress: poaMedia ? {
          url: poaMedia.url,
          filename: poaMedia.filename,
          mimeType: poaMedia.mimeType,
        } : null,
      },
      success: true,
      message: null,
    })
  } catch (error) {
    console.error('Error fetching worker documents:', error)
    return NextResponse.json(
      { data: null, success: false, message: 'Failed to fetch documents' },
      { status: 500 },
    )
  }
}
