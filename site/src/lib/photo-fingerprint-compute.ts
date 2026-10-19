/**
 * Evidence photo fingerprints — hashing I/O (sha256, dHash via sharp, EXIF,
 * bounded blob fetch). Rules are in photo-fingerprint.ts; DB in
 * photo-fingerprint-db.ts.
 */
import { createHash } from 'node:crypto'
import sharp from 'sharp'
import {
  FINGERPRINT_MAX_BYTES,
  FINGERPRINT_TIMEOUT_MS,
  dhashFromGray9x8,
  exifCaptureTime,
} from '@/lib/photo-fingerprint'
import { ownBlobHost } from '@/lib/job-lifecycle'

export type ComputedFingerprint = {
  sha256: string
  dhash: bigint
  byteSize: number
  width: number | null
  height: number | null
  exifCapturedAt: string | null
}

/**
 * Fingerprint of `stored` (the bytes the blob store serves). `original`, when
 * given, is the upload as the phone sent it — the only place EXIF survives,
 * since /api/spots/upload re-encodes without metadata.
 */
export async function computeFingerprint(stored: Buffer, original?: Buffer): Promise<ComputedFingerprint> {
  const sha256 = createHash('sha256').update(stored).digest('hex')
  const meta = await sharp(stored).metadata()
  const gray = await sharp(stored)
    .rotate()
    .grayscale()
    .resize(9, 8, { fit: 'fill', kernel: 'lanczos3' })
    .raw()
    .toBuffer()
  let exifCapturedAt = exifCaptureTime(meta.exif)
  if (!exifCapturedAt && original) {
    exifCapturedAt = exifCaptureTime((await sharp(original).metadata()).exif)
  }
  return {
    sha256,
    dhash: dhashFromGray9x8(gray),
    byteSize: stored.length,
    width: meta.width ?? null,
    height: meta.height ?? null,
    exifCapturedAt,
  }
}

export type BlobFetcher = (url: string, init: RequestInit & { signal: AbortSignal }) => Promise<Response>

const VERCEL_BLOB_PUBLIC_SUFFIX = '.public.blob.vercel-storage.com'

/**
 * Whether the server may fetch `url` for hashing: https on OUR blob store
 * only (the host derived from BLOB_READ_WRITE_TOKEN — job-lifecycle.ts
 * ownBlobHost — or, with no token configured, a Vercel Blob public host), no
 * credentials or port. Callers still run isOwnEvidenceUrl first; this is the
 * fetch-side backstop so nothing else can ever be requested.
 */
export function isFetchableBlobUrl(value: string, storeHost: string | null = ownBlobHost()): boolean {
  let url: URL
  try {
    url = new URL(value)
  } catch {
    return false
  }
  if (url.protocol !== 'https:' || url.username || url.password || url.port) return false // ggignore
  const host = url.hostname.toLowerCase()
  return storeHost ? host === storeHost : host.endsWith(VERCEL_BLOB_PUBLIC_SUFFIX)
}

/**
 * Fetches one of our blobs for hashing, bounded in time and size:
 * refuses any URL off our blob store, never follows a redirect (a 3xx is a
 * failure, and a response whose final URL differs is refused), and reads the
 * body as a stream with a running byte cap — a missing or lying
 * Content-Length cannot make it buffer more than FINGERPRINT_MAX_BYTES.
 */
export async function fetchBlobBounded(
  url: string,
  fetcher: BlobFetcher = fetch,
  timeoutMs = FINGERPRINT_TIMEOUT_MS,
  storeHost: string | null = ownBlobHost(),
): Promise<Buffer> {
  if (!isFetchableBlobUrl(url, storeHost)) throw new Error('not our blob store')
  const res = await fetcher(url, { signal: AbortSignal.timeout(timeoutMs), redirect: 'error' })
  if (res.status >= 300 && res.status < 400) throw new Error(`blob fetch redirected (${res.status})`)
  if (!res.ok) throw new Error(`blob fetch ${res.status}`)
  if (res.url && res.url !== url) throw new Error('blob fetch landed on another URL')
  const declared = Number(res.headers.get('content-length'))
  if (Number.isFinite(declared) && declared > FINGERPRINT_MAX_BYTES) throw new Error('blob too large')
  if (!res.body) return Buffer.alloc(0)
  const reader = res.body.getReader()
  const chunks: Uint8Array[] = []
  let total = 0
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    total += value.byteLength
    if (total > FINGERPRINT_MAX_BYTES) {
      await reader.cancel().catch(() => {})
      throw new Error('blob too large')
    }
    chunks.push(value)
  }
  return Buffer.concat(chunks)
}

/** Rejects with 'timeout' if `work` has not settled within `ms`. */
export function withTimeout<T>(work: Promise<T>, ms: number): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new Error('timeout')), ms)
  })
  return Promise.race([work, timeout]).finally(() => clearTimeout(timer))
}
