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
import { S3Client } from '@aws-sdk/client-s3'

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
