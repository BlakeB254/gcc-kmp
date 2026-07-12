import { NextRequest, NextResponse } from 'next/server'
import { PutObjectCommand } from '@aws-sdk/client-s3'
import { getSignedUrl } from '@aws-sdk/s3-request-presigner'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { isR2Configured, r2BucketName, r2Client, rawScanKey, rawSessionArtifactKey } from '@/lib/r2'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

const UPLOAD_URL_TTL_SECONDS = 300
// ext -> fixed server-side MIME type (never client-supplied — stored-XSS vector).
const EXTENSION_CONTENT_TYPES: Record<string, string> = {
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  heic: 'image/heic',
  // 16-bit grayscale depth frames from the raw area-capture path (Task 7) —
  // PNG rather than a custom binary so Open3D/any RGBD tool can read it with
  // zero GCC-specific tooling, per the data-lake design's open-format goal.
  png: 'image/png',
  mp4: 'video/mp4',
  mov: 'video/quicktime',
  bin: 'application/octet-stream',
  ply: 'application/octet-stream',
  json: 'application/json',
  jsonl: 'application/x-ndjson',
  usdz: 'model/vnd.usdz+zip',
  // Flight-recorder event stream (scan-session-replay PRD Phase 1):
  // gzipped JSONL uploaded as `…/events.jsonl.gz` alongside the manifest.
  gz: 'application/gzip',
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
    // Session-artifact path (Pillar 1 capture chunks): both must be present
    // to opt into the new key shape — otherwise this is byte-identical to
    // the legacy locationCellId/deviceId/capturedAt behavior.
    const sessionId = typeof body?.sessionId === 'string' ? body.sessionId : ''
    const artifactPath = typeof body?.artifactPath === 'string' ? body.artifactPath : ''
    const useSessionArtifactKey = Boolean(sessionId && artifactPath)

    if (!locationCellId || (!useSessionArtifactKey && (!deviceId || !capturedAt))) {
      return NextResponse.json(
        { data: null, success: false, message: 'locationCellId, deviceId, and capturedAt are required' },
        { status: 400 }
      )
    }
    if (!LOCATION_CELL_ID_PATTERN.test(locationCellId)) {
      return NextResponse.json({ data: null, success: false, message: 'Invalid locationCellId' }, { status: 400 })
    }
    if (!useSessionArtifactKey && !DEVICE_ID_PATTERN.test(deviceId)) {
      return NextResponse.json({ data: null, success: false, message: 'Invalid deviceId' }, { status: 400 })
    }
    const contentType = EXTENSION_CONTENT_TYPES[ext]
    if (!contentType) {
      return NextResponse.json(
        { data: null, success: false, message: `ext must be one of: ${Object.keys(EXTENSION_CONTENT_TYPES).join(', ')}` },
        { status: 400 }
      )
    }

    let r2Key: string
    if (useSessionArtifactKey) {
      try {
        r2Key = rawSessionArtifactKey(locationCellId, sessionId, artifactPath)
      } catch (validationError) {
        console.error('[captures/upload-url] invalid session-artifact key components', validationError)
        return NextResponse.json({ data: null, success: false, message: 'Invalid sessionId or artifactPath' }, { status: 400 })
      }
    } else {
      r2Key = rawScanKey(locationCellId, deviceId, capturedAt, ext)
    }
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
