/**
 * POST /api/workers/upload-document — Worker self-uploads a verification document.
 *
 * Multipart form-data with two fields:
 *   - documentType: 'government_id' | 'proof_of_address'
 *   - file: image/jpeg, image/png, image/webp, or application/pdf
 *
 * Auth: cookie-based `worker-token` (same JWT issued by /set-password).
 *
 * PRIVACY: these are PII documents. They are stored PRIVATELY in Cloudflare
 * R2 under `verification/{workerId}/` (the same prefix as the mobile route,
 * /api/workers/me/verification/upload), and the media row holds the object
 * key, not a fetchable URL. Admins view them through
 * GET /api/admin/workers/:id/documents, which issues short-lived presigned
 * URLs. This route used to write public Vercel Blob URLs.
 *
 * Inserts a row into `media` (with the right category), then patches
 * the worker row's `verificationGovernmentIdId` / `verificationProofOfAddressId`
 * and resets `verificationStatus` to 'pending' so the admin queue picks it up.
 */
import { NextRequest, NextResponse } from 'next/server'
import { PutObjectCommand } from '@aws-sdk/client-s3'
import { eq } from 'drizzle-orm'
import { db } from '@/db'
import { workers, media } from '@/db/schema'
import { verifyToken } from '@/lib/auth'
import { processImage } from '@/lib/image-utils'
import { isR2Configured, r2BucketName, r2Client } from '@/lib/r2'
import { verificationDocKey } from '@/lib/verification-doc-key'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'
export const maxDuration = 30

type DocumentType = 'government_id' | 'proof_of_address'

const ACCEPTED_MIME = new Set([
  'image/jpeg',
  'image/jpg',
  'image/png',
  'image/webp',
  'application/pdf',
])

const MAX_BYTES = 10 * 1024 * 1024 // 10MB cap before processing

export async function POST(request: NextRequest) {
  // Auth — cookie only (this is a web-only flow during onboarding)
  const token = request.cookies.get('worker-token')?.value
  if (!token) {
    return NextResponse.json({ error: 'Not authenticated' }, { status: 401 })
  }
  const payload = await verifyToken(token)
  if (!payload || payload.type !== 'worker') {
    return NextResponse.json({ error: 'Not authenticated' }, { status: 401 })
  }
  const workerId = payload.id

  // No public-blob fallback — a misconfigured server must refuse the upload
  // rather than quietly publish someone's government ID.
  if (!isR2Configured()) {
    return NextResponse.json(
      { error: 'Document uploads are temporarily unavailable. Please try again later.' },
      { status: 503 },
    )
  }

  // Parse form
  let formData: FormData
  try {
    formData = await request.formData()
  } catch {
    return NextResponse.json({ error: 'Invalid form data' }, { status: 400 })
  }

  const documentType = formData.get('documentType') as DocumentType | null
  const file = formData.get('file')

  if (documentType !== 'government_id' && documentType !== 'proof_of_address') {
    return NextResponse.json({ error: 'documentType must be government_id or proof_of_address' }, { status: 400 })
  }
  if (!(file instanceof File)) {
    return NextResponse.json({ error: 'file is required' }, { status: 400 })
  }
  if (!ACCEPTED_MIME.has(file.type)) {
    return NextResponse.json({ error: 'Unsupported file type. Use JPEG, PNG, WebP, or PDF.' }, { status: 400 })
  }
  if (file.size > MAX_BYTES) {
    return NextResponse.json({ error: 'File too large. Max 10MB.' }, { status: 400 })
  }

  // Process: images go through sharp (auto-rotate, resize, strip EXIF, jpeg).
  // PDFs pass through unchanged.
  const isPdf = file.type === 'application/pdf'
  const raw = await file.arrayBuffer()
  const processed = isPdf ? Buffer.from(raw) : await processImage(raw)
  const ext = isPdf ? 'pdf' : 'jpg'
  const contentType = isPdf ? 'application/pdf' : 'image/jpeg'

  const key = verificationDocKey(workerId, documentType, ext)

  try {
    await r2Client().send(
      new PutObjectCommand({
        Bucket: r2BucketName(),
        Key: key,
        Body: processed,
        ContentType: contentType,
      }),
    )
  } catch (err) {
    console.error('[upload-document] R2 upload failed:', err)
    return NextResponse.json({ error: 'Upload failed. Please try again.' }, { status: 500 })
  }

  // Insert media row
  const category = documentType === 'government_id' ? 'worker_id' : 'proof_of_address'
  const [mediaRow] = await db
    .insert(media)
    .values({
      url: key,
      filename: key,
      mimeType: contentType,
      filesize: processed.length.toString(),
      category,
    })
    .returning({ id: media.id })

  // Patch worker row — link the new media id, reset status to pending review
  const update =
    documentType === 'government_id'
      ? {
          verificationGovernmentIdId: mediaRow.id,
          verificationGovernmentIdStatus: 'pending' as const,
          verificationStatus: 'pending' as const,
          updatedAt: new Date().toISOString(),
        }
      : {
          verificationProofOfAddressId: mediaRow.id,
          verificationProofOfAddressStatus: 'pending' as const,
          verificationStatus: 'pending' as const,
          updatedAt: new Date().toISOString(),
        }

  await db.update(workers).set(update).where(eq(workers.id, workerId))

  return NextResponse.json({
    success: true,
    documentType,
    mediaId: mediaRow.id,
  })
}
