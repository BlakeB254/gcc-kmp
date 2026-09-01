/**
 * Cloudflare R2 client for raw scan-blob storage (Phase 2 of the scan-guidance/
 * world-model design, docs/superpowers/specs/2026-07-02-scan-guidance-world-
 * model-and-rewards-design.md §2). R2 is S3-compatible, so this uses the AWS S3
 * SDK against R2's endpoint — no R2-specific SDK needed.
 *
 * Setup steps (bucket/token creation) are in
 * docs/governance/production-readiness-and-scaling.md §5. Until the four env
 * vars below are set, `isR2Configured()` returns false and callers should
 * respond with a clear "not configured" error rather than crashing.
 */
import { GetObjectCommand, S3Client } from '@aws-sdk/client-s3'
import { getSignedUrl } from '@aws-sdk/s3-request-presigner'

function readR2Env() {
  return {
    accountId: process.env.R2_ACCOUNT_ID,
    accessKeyId: process.env.R2_ACCESS_KEY_ID,
    secretAccessKey: process.env.R2_SECRET_ACCESS_KEY,
    bucketName: process.env.R2_BUCKET_NAME,
  }
}

export function isR2Configured(): boolean {
  const env = readR2Env()
  return Boolean(env.accountId && env.accessKeyId && env.secretAccessKey && env.bucketName)
}

export function r2BucketName(): string {
  const { bucketName } = readR2Env()
  if (!bucketName) throw new Error('R2_BUCKET_NAME is not set')
  return bucketName
}

let cachedClient: S3Client | null = null

export function r2Client(): S3Client {
  if (cachedClient) return cachedClient

  const { accountId, accessKeyId, secretAccessKey } = readR2Env()
  if (!accountId || !accessKeyId || !secretAccessKey) {
    throw new Error('R2 is not configured — set R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, R2_BUCKET_NAME')
  }

  cachedClient = new S3Client({
    region: 'auto',
    endpoint: `https://${accountId}.r2.cloudflarestorage.com`,
    credentials: { accessKeyId, secretAccessKey },
  })
  return cachedClient
}

// Short-lived read URL for admin views of raw capture blobs — long enough to
// open/stream in a browser tab, short enough that a leaked link goes stale.
const VIEW_URL_TTL_SECONDS = 300

/** Presigned GET for viewing an R2 object (admin scan-viz). */
export async function getR2ViewUrl(key: string): Promise<string> {
  return getSignedUrl(r2Client(), new GetObjectCommand({ Bucket: r2BucketName(), Key: key }), {
    expiresIn: VIEW_URL_TTL_SECONDS,
  })
}

// Matches gridCell()'s exact output shape (site/src/lib/world-observations.ts):
// `${latIdx}:${lngIdx}`, both signed integers.
const LOCATION_CELL_ID_PATTERN = /^-?\d+:-?\d+$/
// Bounded alphanumeric identifier — covers CoverageDeviceId's 32-char hex
// format without hard-coupling to that exact scheme.
const DEVICE_ID_PATTERN = /^[0-9A-Za-z_-]{1,128}$/

/**
 * Raw per-session capture key, per the documented bucket layout (design spec
 * §2.4): `raw/{locationCellId}/{deviceId}/{capturedAt}.{ext}`. Every
 * caller-supplied component is validated against a strict allowlist — this is
 * an R2 object key derived directly from request-body input, so anything
 * permitting `/`, `\`, or `..` here would let a client write outside the
 * intended prefix (or overwrite another cell's/device's objects) rather than
 * just producing an odd filename.
 */
export function rawScanKey(locationCellId: string, deviceId: string, capturedAt: string, ext: string): string {
  if (!LOCATION_CELL_ID_PATTERN.test(locationCellId)) {
    throw new Error(`invalid locationCellId: ${locationCellId}`)
  }
  if (!DEVICE_ID_PATTERN.test(deviceId)) {
    throw new Error(`invalid deviceId: ${deviceId}`)
  }
  const safeCapturedAt = capturedAt.replace(/[^0-9A-Za-z._-]/g, '-')
  return `raw/${locationCellId}/${deviceId}/${safeCapturedAt}.${ext}`
}

/** Reconstructed-asset key, per §2.4: `reconstructed/{locationCellId}/{version}/{assetType}.{format}`. */
export function reconstructedAssetKey(locationCellId: string, version: number, assetType: string, format: string): string {
  if (!LOCATION_CELL_ID_PATTERN.test(locationCellId)) {
    throw new Error(`invalid locationCellId: ${locationCellId}`)
  }
  return `reconstructed/${locationCellId}/${version}/${assetType}.${format}`
}

// Each `/`-joined segment of artifactPath must match this on its own — unlike
// rawScanKey's capturedAt sanitization (which rewrites bad characters to
// `-`), this is deliberately a hard reject: an artifactPath composes
// caller-supplied segments into a path with slashes PRESERVED, so silently
// coercing a `..` segment could still resolve to a directory traversal after
// S3-key normalization. Reject instead of sanitize.
const ARTIFACT_PATH_SEGMENT_PATTERN = /^[0-9A-Za-z._-]+$/
const ARTIFACT_PATH_MAX_SEGMENTS = 5
const ARTIFACT_PATH_MAX_LENGTH = 200

/**
 * Raw session-artifact key for Pillar 1 capture chunks (background HEVC
 * video / pose-sidecar JSONL / LiDAR depth files), per the session-capture
 * plan: `raw/{locationCellId}/{sessionId}/{artifactPath}`. Sibling of
 * `rawScanKey` — same locationCellId validation, sessionId sanitized the
 * same permissive way as `rawScanKey`'s capturedAt — but artifactPath is
 * itself a `/`-joined relative path (e.g. `capture/chunk-0.mp4`), so unlike
 * every other key component here it is validated PER SEGMENT and slashes
 * are preserved rather than stripped/escaped. Each segment must match the
 * same strict allowlist as a single path component; `..` is rejected
 * outright rather than sanitized, since a caller-supplied path is exactly
 * the kind of input directory-traversal attacks target.
 */
/**
 * MetricKit crash/hang diagnostic key (scan-session-replay PRD Phase 1):
 * `diagnostics/metrickit/{deviceId}/{fileName}`. Deliberately OUTSIDE the
 * `raw/` prefix — the backend scan indexer sweeps `raw/` on a schedule and
 * these are not scan artifacts. fileName is hard-validated (single segment,
 * strict allowlist) like artifactPath segments: it composes into an R2 key
 * straight from request-body input.
 */
export function metricKitDiagnosticKey(deviceId: string, fileName: string): string {
  if (!DEVICE_ID_PATTERN.test(deviceId)) {
    throw new Error(`invalid deviceId: ${deviceId}`)
  }
  if (fileName === '..' || fileName.length > 128 || !ARTIFACT_PATH_SEGMENT_PATTERN.test(fileName)) {
    throw new Error(`invalid diagnostic fileName: ${fileName}`)
  }
  return `diagnostics/metrickit/${deviceId}/${fileName}`
}

export function rawSessionArtifactKey(locationCellId: string, sessionId: string, artifactPath: string): string {
  if (!LOCATION_CELL_ID_PATTERN.test(locationCellId)) {
    throw new Error(`invalid locationCellId: ${locationCellId}`)
  }
  const safeSessionId = sessionId.replace(/[^0-9A-Za-z._-]/g, '-')
  if (artifactPath.length === 0 || artifactPath.length > ARTIFACT_PATH_MAX_LENGTH) {
    throw new Error(`invalid artifactPath length: ${artifactPath.length}`)
  }
  const segments = artifactPath.split('/')
  if (segments.length === 0 || segments.length > ARTIFACT_PATH_MAX_SEGMENTS) {
    throw new Error(`invalid artifactPath segment count: ${segments.length}`)
  }
  for (const segment of segments) {
    if (segment === '..' || !ARTIFACT_PATH_SEGMENT_PATTERN.test(segment)) {
      throw new Error(`invalid artifactPath segment: ${segment}`)
    }
  }
  return `raw/${locationCellId}/${safeSessionId}/${segments.join('/')}`
}
