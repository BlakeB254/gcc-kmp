import { NextRequest, NextResponse } from 'next/server'
import { PutObjectCommand } from '@aws-sdk/client-s3'
import { getSignedUrl } from '@aws-sdk/s3-request-presigner'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { canAccessRouteScan, findRouteScan } from '@/lib/mobile-route-scan-response'
import { isR2Configured, r2BucketName, r2Client, rawScanKey } from '@/lib/r2'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

const UPLOAD_URL_TTL_SECONDS = 300
const ALLOWED_EXTENSIONS = new Set(['jpg', 'jpeg', 'heic', 'mp4', 'bin', 'ply'])

type RouteContext = { params: Promise<{ id: string }> }

/**
 * Issues a presigned R2 PUT URL for a raw scan capture blob (depth frames,
 * capture video, point cloud) — Phase 2 ingestion path, design spec §2.2. The
 * metadata write (scanCoverageEvents/worldObservations) is independent of this
 * upload and happens via the existing endpoints; the client uploads the blob
 * to the returned URL directly, without proxying it through this API.
 */
export async function POST(request: NextRequest, context: RouteContext) {
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
    const { id } = await context.params
    const scanId = parseInt(id, 10)
    if (Number.isNaN(scanId)) {
      return NextResponse.json({ data: null, success: false, message: 'Invalid scan ID' }, { status: 400 })
    }

    const scan = await findRouteScan(scanId)
    if (!scan) {
      return NextResponse.json({ data: null, success: false, message: 'Scan not found' }, { status: 404 })
    }
    if (!canAccessRouteScan(scan, auth)) {
      return NextResponse.json({ data: null, success: false, message: 'Scan belongs to a different worker' }, { status: 403 })
    }

    const body = await request.json().catch(() => null)
    const locationCellId = typeof body?.locationCellId === 'string' ? body.locationCellId : ''
    const deviceId = typeof body?.deviceId === 'string' ? body.deviceId : ''
    const capturedAt = typeof body?.capturedAt === 'string' ? body.capturedAt : ''
    const ext = typeof body?.ext === 'string' ? body.ext.toLowerCase() : ''
    const contentType = typeof body?.contentType === 'string' ? body.contentType : 'application/octet-stream'

    if (!locationCellId || !deviceId || !capturedAt) {
      return NextResponse.json(
        { data: null, success: false, message: 'locationCellId, deviceId, and capturedAt are required' },
        { status: 400 }
      )
    }
    if (!ALLOWED_EXTENSIONS.has(ext)) {
      return NextResponse.json(
        { data: null, success: false, message: `ext must be one of: ${[...ALLOWED_EXTENSIONS].join(', ')}` },
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
    console.error('[scans/upload-url] failed to issue presigned URL', error)
    return NextResponse.json({ data: null, success: false, message: 'Failed to issue upload URL' }, { status: 500 })
  }
}
