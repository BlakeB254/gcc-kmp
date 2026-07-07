import { NextResponse } from 'next/server'
import { GetObjectCommand } from '@aws-sdk/client-s3'
import { getSignedUrl } from '@aws-sdk/s3-request-presigner'
import { db } from '@/db'
import { workers, media } from '@/db/schema'
import { eq, inArray } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'
import { isR2Configured, r2BucketName, r2Client } from '@/lib/r2'

export const runtime = 'nodejs'

const VIEW_URL_TTL_SECONDS = 300

/**
 * Documents uploaded through /api/workers/me/verification/upload are stored
 * privately in R2 and referenced by object key (`verification/...`) rather
 * than a fetchable URL. Swap those for short-lived presigned GET URLs so the
 * existing admin UI can render them unchanged; legacy public blob URLs pass
 * through as-is.
 */
async function viewableUrl(stored: string): Promise<string | null> {
  if (!stored.startsWith('verification/')) return stored
  if (!isR2Configured()) {
    console.error('[admin/documents] R2 not configured but a verification/ key is stored:', stored)
    return null
  }
  return getSignedUrl(
    r2Client(),
    new GetObjectCommand({ Bucket: r2BucketName(), Key: stored }),
    { expiresIn: VIEW_URL_TTL_SECONDS }
  )
}

/**
 * GET /api/admin/workers/:id/documents
 * Returns the verification document image URLs for a worker.
 * Resolves the media IDs stored in verification_government_id_id
 * and verification_proof_of_address_id to viewable URLs (presigned R2
 * URLs for private documents, Vercel Blob URLs for legacy uploads).
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

    const govUrl = govMedia?.url ? await viewableUrl(govMedia.url) : null
    const poaUrl = poaMedia?.url ? await viewableUrl(poaMedia.url) : null

    return NextResponse.json({
      data: {
        governmentId: govMedia && govUrl ? {
          url: govUrl,
          filename: govMedia.filename,
          mimeType: govMedia.mimeType,
        } : null,
        proofOfAddress: poaMedia && poaUrl ? {
          url: poaUrl,
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
