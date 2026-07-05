import { NextRequest, NextResponse } from 'next/server'
import { PutObjectCommand } from '@aws-sdk/client-s3'
import { getSignedUrl } from '@aws-sdk/s3-request-presigner'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { isR2Configured, r2BucketName, r2Client, rawScanKey } from '@/lib/r2'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

const UPLOAD_URL_TTL_SECONDS = 300
// ext -> fixed server-side MIME type (never client-supplied — stored-XSS vector).
const EXTENSION_CONTENT_TYPES: Record<string, string> = {
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  heic: 'image/heic',
  mp4: 'video/mp4',
  bin: 'application/octet-stream',
  ply: 'application/octet-stream',
  json: 'application/json',
  usdz: 'model/vnd.usdz+zip',
}
const LOCATION_CELL_ID_PATTERN = /^-?\d+:-?\d+$/
const DEVICE_ID_PATTERN = /^[0-9A-Za-z_-]{1,128}$/

/**
 * Presigned R2 PUT URLs for ENVIRONMENT-SCAN session artifacts (photos +
 * session manifest JSON). Sibling of /api/scans/[id]/upload-url — that
 * variant is scoped to a route-scan row; environment scans have no scan row,
 * so the accountability anchors here are the authenticated worker plus the
 * same strict key-component allowlists. One scan session = one manifest +
 * its photos under raw/{locationCellId}/{deviceId}/, ready for the DGX
 * reconstruction pipeline to discover.
 */
export async function POST(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json({ data: null, success: false, message: 'Not authenticated' }, { status: 401 })
  }

  if (!isR2Configured()) {
    return NextResponse.json(
      {
        data: null,
        success: false,
        message: 'Raw scan storage is not configured yet — see docs/governance/production-readiness-and-scaling.md §5',
      },
      { status: 501 }
    )
  }

  try {
    const body = await request.json().catch(() => null)
    const locationCellId = typeof body?.locationCellId === 'string' ? body.locationCellId : ''
    const deviceId = typeof body?.deviceId === 'string' ? body.deviceId : ''
    const capturedAt = typeof body?.capturedAt === 'string' ? body.capturedAt : ''
    const ext = typeof body?.ext === 'string' ? body.ext.toLowerCase() : ''

    if (!locationCellId || !deviceId || !capturedAt) {
      return NextResponse.json(
        { data: null, success: false, message: 'locationCellId, deviceId, and capturedAt are required' },
        { status: 400 }
      )
    }
    if (!LOCATION_CELL_ID_PATTERN.test(locationCellId)) {
      return NextResponse.json({ data: null, success: false, message: 'Invalid locationCellId' }, { status: 400 })
    }
    if (!DEVICE_ID_PATTERN.test(deviceId)) {
      return NextResponse.json({ data: null, success: false, message: 'Invalid deviceId' }, { status: 400 })
    }
    const contentType = EXTENSION_CONTENT_TYPES[ext]
    if (!contentType) {
      return NextResponse.json(
        { data: null, success: false, message: `ext must be one of: ${Object.keys(EXTENSION_CONTENT_TYPES).join(', ')}` },
        { status: 400 }
      )
    }

    const r2Key = rawScanKey(locationCellId, deviceId, capturedAt, ext)
    const uploadUrl = await getSignedUrl(
      r2Client(),
      new PutObjectCommand({ Bucket: r2BucketName(), Key: r2Key, ContentType: contentType }),
      { expiresIn: UPLOAD_URL_TTL_SECONDS }
    )

    return NextResponse.json({
      data: { uploadUrl, r2Key, expiresInSeconds: UPLOAD_URL_TTL_SECONDS },
      success: true,
      message: null,
    })
  } catch (error) {
    console.error('[captures/upload-url] failed to issue presigned URL', error)
    return NextResponse.json({ data: null, success: false, message: 'Failed to issue upload URL' }, { status: 500 })
  }
}
