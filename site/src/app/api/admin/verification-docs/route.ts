/**
 * GET /api/admin/verification-docs?key=verification/{workerId}/{file}
 *
 * Exchanges a private R2 object key for a short-lived presigned GET URL so
 * admins can view worker verification documents (government ID, proof of
 * address) uploaded via /api/workers/me/verification/upload.
 *
 * Only keys under the `verification/` prefix are accepted — this endpoint
 * must not become a general-purpose signed proxy for the whole bucket.
 */
import { NextRequest, NextResponse } from 'next/server'
import { GetObjectCommand } from '@aws-sdk/client-s3'
import { getSignedUrl } from '@aws-sdk/s3-request-presigner'
import { requireAdmin } from '@/lib/admin-auth'
import { isR2Configured, r2BucketName, r2Client } from '@/lib/r2'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

const VIEW_URL_TTL_SECONDS = 300

// Exactly: verification/{numeric workerId}/{safe filename}. No extra path
// segments, no `..`, no slashes in the filename component.
const VERIFICATION_KEY_PATTERN = /^verification\/\d+\/[0-9A-Za-z._-]{1,200}$/

export async function GET(request: NextRequest) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  if (!isR2Configured()) {
    return NextResponse.json(
      { data: null, success: false, message: 'Private document storage (R2) is not configured' },
      { status: 503 }
    )
  }

  const key = request.nextUrl.searchParams.get('key') || ''
  if (!VERIFICATION_KEY_PATTERN.test(key) || key.includes('..')) {
    return NextResponse.json(
      { data: null, success: false, message: 'key must be a verification/ object key' },
      { status: 400 }
    )
  }

  try {
    const url = await getSignedUrl(
      r2Client(),
      new GetObjectCommand({ Bucket: r2BucketName(), Key: key }),
      { expiresIn: VIEW_URL_TTL_SECONDS }
    )

    return NextResponse.json({
      data: { url, expiresInSeconds: VIEW_URL_TTL_SECONDS },
      success: true,
      message: null,
    })
  } catch (error) {
    console.error('[admin/verification-docs] failed to presign', error)
    return NextResponse.json(
      { data: null, success: false, message: 'Failed to create view URL' },
      { status: 500 }
    )
  }
}
