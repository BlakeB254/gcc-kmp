/**
 * Production bindings for the scan indexer, the 311 mirror and the watchdog:
 * R2 through the S3 SDK, Neon through the app's drizzle `db`, SODA through
 * fetch. Kept apart from the logic so the logic stays unit-testable with fakes
 * and runnable against a local Postgres.
 */
import { GetObjectCommand, HeadObjectCommand, ListObjectsV2Command } from '@aws-sdk/client-s3'
import { db } from '@/db'
import { isR2Configured, r2BucketName, r2Client } from '@/lib/r2'
import { civicSyncDb } from './civic-db'
import { rowsOf, scanIndexDb, watchdogDb, type SqlExec } from './db'
import type { CivicSyncDeps } from './civic-sync'
import { ObjectTooLarge, type ObjectInfo, type ScanIndexDeps } from './indexer'
import { grantCutoverMs, grantsFor, type ObjectProbe } from './grants'
import type { WatchdogDeps } from './watchdog'

export const neonExec: SqlExec = async (query) => rowsOf(await db.execute(query))

export async function listR2(prefix: string): Promise<Map<string, ObjectInfo>> {
  const out = new Map<string, ObjectInfo>()
  let ContinuationToken: string | undefined
  do {
    const page = await r2Client().send(new ListObjectsV2Command({ Bucket: r2BucketName(), Prefix: prefix, ContinuationToken }))
    for (const o of page.Contents ?? []) {
      if (o.Key) out.set(o.Key, { size: o.Size ?? 0, lastModifiedMs: o.LastModified ? o.LastModified.getTime() : 0 })
    }
    ContinuationToken = page.IsTruncated ? page.NextContinuationToken : undefined
  } while (ContinuationToken)
  return out
}

/**
 * Ranged GET of at most `maxBytes` + 1 bytes, so an object swapped for a huge
 * one after it was listed still costs at most the cap. One byte over the cap
 * is how an oversized object is told apart from one exactly at it.
 */
export async function getR2Bytes(key: string, maxBytes: number): Promise<Uint8Array> {
  let res
  try {
    res = await r2Client().send(
      new GetObjectCommand({ Bucket: r2BucketName(), Key: key, Range: `bytes=0-${maxBytes}` }),
    )
  } catch (e) {
    // A zero-byte object can't satisfy any range.
    if ((e as { name?: string }).name === 'InvalidRange') return new Uint8Array(0)
    throw e
  }
  const total = Number(/\/(\d+)$/.exec(res.ContentRange ?? '')?.[1] ?? res.ContentLength ?? 0)
  if (total > maxBytes) {
    res.Body?.transformToByteArray().catch(() => {}) // drain the (capped) body
    throw new ObjectTooLarge(`${key} is ${total} bytes (cap ${maxBytes})`)
  }
  if (!res.Body) throw new Error(`empty body for ${key}`)
  const bytes = await res.Body.transformToByteArray()
  if (bytes.byteLength > maxBytes) throw new ObjectTooLarge(`${key} exceeds ${maxBytes} bytes`)
  return bytes
}

export const r2Reader: Pick<ScanIndexDeps, 'listAll' | 'getBytes'> = {
  listAll: listR2,
  getBytes: getR2Bytes,
}

/**
 * What is at `key`: `exists` (HEAD 200), `absent` (a clean 404), or `unknown`
 * — timeout, 5xx, 403, anything else. Unknown must never be read as either
 * answer: the upload routes turn it into a retryable 503.
 */
export async function r2ObjectState(key: string): Promise<ObjectProbe> {
  try {
    const head = await r2Client().send(
      new HeadObjectCommand({ Bucket: r2BucketName(), Key: key }),
      { abortSignal: AbortSignal.timeout(5_000) },
    )
    return { state: 'exists', lastModifiedMs: head.LastModified ? head.LastModified.getTime() : null }
  } catch (e) {
    const err = e as { name?: string; $metadata?: { httpStatusCode?: number } }
    if (err.$metadata?.httpStatusCode === 404 || (err.name === 'NotFound' && err.$metadata?.httpStatusCode == null)) {
      return { state: 'absent' }
    }
    console.warn(`[upload-url] R2 HEAD ${key} inconclusive: ${err.name ?? 'error'} ${err.$metadata?.httpStatusCode ?? ''}`)
    return { state: 'unknown' }
  }
}

/** Seam for route tests (no network): replace `state`. */
export const r2Probe = { state: r2ObjectState }

/** SODA GET; X-App-Token lifts the shared anonymous throttle when set. */
export async function fetchSoda(url: string, timeoutMs: number): ReturnType<CivicSyncDeps['fetchJson']> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  const token = process.env.SOCRATA_APP_TOKEN?.trim()
  if (token) headers['X-App-Token'] = token
  const res = await fetch(url, { headers, signal: AbortSignal.timeout(timeoutMs), cache: 'no-store' })
  if (res.status !== 200) return { ok: false, status: res.status, text: await res.text() }
  return { ok: true, body: await res.json() }
}

export const scanIndexDeps: ScanIndexDeps = {
  ...r2Reader,
  ...scanIndexDb(neonExec),
  grantCutoverMs: () => grantCutoverMs(neonExec),
  grantsFor: (keys) => grantsFor(neonExec, keys),
  log: (msg) => console.warn(`[cron/scan-index] ${msg}`),
}

export const civicSyncDeps: CivicSyncDeps = { ...civicSyncDb(neonExec), fetchJson: fetchSoda }

export const watchdogDeps: WatchdogDeps = {
  ...watchdogDb(neonExec),
  listRaw: async () => (isR2Configured() ? listR2('raw/') : null),
}
