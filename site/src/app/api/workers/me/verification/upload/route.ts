/**
 * POST /api/workers/me/verification/upload — Upload verification documents
 * (government ID, proof of address) from the mobile app.
 *
 * PRIVACY: these are PII documents, so unlike /api/spots/upload (public
 * Vercel Blob) they are stored PRIVATELY in Cloudflare R2 under
 * `verification/{workerId}/{timestamp}-{index}.jpg`. The returned "urls" are
 * R2 object keys, not fetchable URLs — admins view them through
 * GET /api/admin/verification-docs, which issues short-lived presigned URLs.
 *
 * Accepts the same multipart shape as /api/spots/upload (any number of image
 * files as form-data fields) and returns the same response envelope
 * (`{ data: { urls }, success, message }`) so the mobile client's upload
 * parsing can be reused unchanged.
 */
import { NextRequest, NextResponse } from 'next/server'
import { PutObjectCommand } from '@aws-sdk/client-s3'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { isR2Configured, r2BucketName, r2Client } from '@/lib/r2'
import { processImage } from '@/lib/image-utils'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'
export const maxDuration = 30

export async function POST(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json(
      { data: null, success: false, message: 'Authorization required' },
      { status: 401 }
    )
  }

  // No public-blob fallback here — a misconfigured server must refuse the
  // upload rather than quietly publish someone's government ID.
  if (!isR2Configured()) {
    return NextResponse.json(
      {
        data: null,
        success: false,
        message:
          'Private document storage (R2) is not configured — verification uploads are disabled. See docs/governance/production-readiness-and-scaling.md §5',
      },
      { status: 503 }
    )
  }

  try {
    const formData = await request.formData()
    const urls: string[] = []
    const timestamp = Date.now()
    let index = 0

    for (const [, value] of formData.entries()) {
      if (!(value instanceof File)) continue

      // Auto-rotate from EXIF, strip metadata, cap at 2048px (same pipeline
      // as spot photos).
      const raw = await value.arrayBuffer()
      const processed = await processImage(raw)

      const key = `verification/${auth.workerId}/${timestamp}-${index}.jpg`
      await r2Client().send(
        new PutObjectCommand({
          Bucket: r2BucketName(),
          Key: key,
          Body: processed,
          ContentType: 'image/jpeg',
        })
      )

      urls.push(key)
      index++
    }

    if (urls.length === 0) {
      return NextResponse.json(
        { data: null, success: false, message: 'No files provided' },
        { status: 400 }
      )
    }

    return NextResponse.json({
      data: { urls },
      success: true,
      message: null,
    })
  } catch (error) {
    console.error('[verification/upload] failed:', error)
    return NextResponse.json(
      { data: null, success: false, message: 'Failed to upload documents' },
      { status: 500 }
    )
  }
}
