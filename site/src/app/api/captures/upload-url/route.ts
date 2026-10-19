import { NextRequest, NextResponse } from 'next/server'
import { PutObjectCommand } from '@aws-sdk/client-s3'
import { getSignedUrl } from '@aws-sdk/s3-request-presigner'
import { requireMobileAuth } from '@/lib/mobile-auth'
import {
  isDeviceId,
  LOCATION_CELL_ID_PATTERN,
  isR2Configured,
  r2BucketName,
  r2Client,
  rawScanKey,
  rawSessionArtifactKey,
} from '@/lib/r2'
import { grantRefusal, grantUploadKey, manifestSessionOf } from '@/lib/scan-index/grants'
import { neonExec, r2Probe } from '@/lib/scan-index/deps'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

// Generous TTL: a large video chunk on a weak cellular uplink can take many
// minutes end-to-end, and the client's retry loop may start its PUT well
// after presigning (TestFlight 162: 11.9MB chunk vs a 5-minute URL). R2
// validates expiry at request start, so this bounds retry staleness, not
// upload duration — 30 minutes is safe for a single-use PUT URL.
const UPLOAD_URL_TTL_SECONDS = 1800
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
  confidence: 'application/vnd.gcc.depth-confidence-v1',
  ply: 'application/octet-stream',
  json: 'application/json',
  jsonl: 'application/x-ndjson',
  usdz: 'model/vnd.usdz+zip',
  // Flight-recorder event stream (scan-session-replay PRD Phase 1):
  // gzipped JSONL uploaded as `…/events.jsonl.gz` alongside the manifest.
  gz: 'application/gzip',
}

/**
 * Presigned R2 PUT URLs for ENVIRONMENT-SCAN session artifacts (photos +
 * session manifest JSON). Sibling of /api/scans/[id]/upload-url — that
 * variant is scoped to a route-scan row; environment scans have no scan row,
 * so the accountability anchors here are the authenticated worker plus the
 * same strict key-component allowlists. One scan session = one manifest +
 * its photos under raw/{locationCellId}/{deviceId}/, ready for the DGX
 * reconstruction pipeline to discover.
 *
 * Every issued key is recorded in capture_upload_grants against the worker
 * (lib/scan-index/grants.ts). A session folder belongs to the first worker
 * granted in it; a device folder does not (shared test handsets), so there
 * only the exact key is owned. Refused with 409 (the app drops the file): a
 * worker's exact key, or a key where someone else's object already exists
 * with no grant (legacy data can't be claimed). Refused with 503 +
 * Retry-After (the app keeps the file): another account's session on a
 * shared handset, or R2 unable to say whether the key is free. The scan
 * indexer only accepts what was granted to one worker.
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

    if (!locationCellId || !deviceId || (!useSessionArtifactKey && !capturedAt)) {
      return NextResponse.json(
        { data: null, success: false, message: 'locationCellId, deviceId, and capturedAt are required' },
        { status: 400 }
      )
    }
    if (!LOCATION_CELL_ID_PATTERN.test(locationCellId)) {
      return NextResponse.json({ data: null, success: false, message: 'Invalid locationCellId' }, { status: 400 })
    }
    // The app sends its deviceId on both key shapes; it is recorded on the
    // upload grant, so it is held to the same allowlist either way.
    if (!isDeviceId(deviceId)) {
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
      try {
        r2Key = rawScanKey(locationCellId, deviceId, capturedAt, ext)
      } catch (validationError) {
        console.error('[captures/upload-url] invalid scan key components', validationError)
      return NextResponse.json({ data: null, success: false, message: 'Invalid capturedAt' }, { status: 400 })
      }
    }
    const grant = await grantUploadKey(neonExec, {
      r2Key,
      workerId: auth.workerId,
      deviceId,
      sessionId: useSessionArtifactKey ? sessionId : null,
      folder: useSessionArtifactKey ? sessionId : deviceId,
      claimFolder: useSessionArtifactKey,
      relatedSession: useSessionArtifactKey ? null : manifestSessionOf(capturedAt, ext),
    }, (key) => r2Probe.state(key))
    const refusal = grantRefusal(grant)
    if (refusal) {
      return NextResponse.json(
        { data: null, success: false, message: refusal.message },
        {
          status: refusal.status,
          headers: refusal.retryAfter ? { 'Retry-After': String(refusal.retryAfter) } : undefined,
        }
      )
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
