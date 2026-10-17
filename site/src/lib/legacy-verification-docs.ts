/**
 * One-time move of legacy PUBLIC verification documents (government ID,
 * proof of address) from Vercel Blob into private R2.
 *
 * Before 2026-10-17 the web onboarding page, the address-change form and the
 * pre-R2 mobile flow wrote these documents to public Blob URLs. New uploads go
 * to R2 (see verification-doc-key.ts); this moves what is already there.
 *
 * Preserve before deleting. Per document, in order:
 *   1. fetch the public bytes;
 *   2. write them to R2 at `verification/{workerId}/legacy-…`;
 *   3. read the R2 object's metadata back and require size AND MD5 (the ETag
 *      of a single-part PUT) to match the fetched bytes;
 *   4. repoint the media row — compare-and-set on the old URL, so a row that
 *      changed underneath is left alone;
 *   5. only then delete the public blob.
 * Any failure stops that document before step 5, so the public copy (and the
 * row pointing at it) stays intact for the next run. Blobs under the legacy
 * prefixes with no media row (orphans) get steps 1–3 and 5.
 *
 * Dry-run by default: `apply=false` only plans and reports.
 */
import crypto from 'crypto'

/** Blob path prefixes that only ever held verification documents. */
export const LEGACY_PREFIXES = ['worker-docs/', 'government-id/', 'proof-of-address/', 'workers/'] as const

export interface LegacyMediaRow {
  id: number
  url: string
  mimeType: string | null
  /** Worker whose verification/address-change columns reference this row, if any. */
  workerId: number | null
}

export interface BlobEntry {
  url: string
  pathname: string
}

export interface MigrationDeps {
  /** media rows (worker_id / proof_of_address) whose url is a public Blob URL. */
  listLegacyMediaRows(): Promise<LegacyMediaRow[]>
  /** Every media url in the table (any category) — used to tell orphans apart. */
  listAllMediaUrls(): Promise<Set<string>>
  listBlobs(prefix: string): Promise<BlobEntry[]>
  fetchPublic(url: string): Promise<{ bytes: Buffer; contentType: string | null } | null>
  putPrivate(key: string, bytes: Buffer, contentType: string): Promise<void>
  headPrivate(key: string): Promise<{ size: number; etag: string | null } | null>
  /** Compare-and-set: returns true only if the row still held `fromUrl`. */
  repointMedia(id: number, fromUrl: string, toKey: string): Promise<boolean>
  /** True if any media row (any category) still points at this public URL. */
  isUrlReferenced(url: string): Promise<boolean>
  deletePublic(url: string): Promise<void>
}

export type ItemStatus =
  | 'planned'
  | 'moved'
  | 'missing-public' // public URL already 404s — nothing to preserve or delete
  | 'verify-failed'
  | 'row-changed'
  | 'error'

export interface ManifestEntry {
  kind: 'media' | 'orphan'
  mediaId: number | null
  from: string // blob pathname (no host)
  to: string // private R2 key
  bytes?: number
  md5?: string
  status: ItemStatus
  error?: string
}

export interface MigrationResult {
  apply: boolean
  candidates: number
  processed: number
  remaining: number
  moved: number
  failed: number
  manifest: ManifestEntry[]
}

const BLOB_HOST_SUFFIX = '.public.blob.vercel-storage.com'

export function isPublicBlobUrl(value: string): boolean {
  try {
    const u = new URL(value)
    return u.protocol === 'https:' && u.hostname.endsWith(BLOB_HOST_SUFFIX)
  } catch {
    return false
  }
}

function pathnameOf(url: string): string {
  return decodeURIComponent(new URL(url).pathname.replace(/^\//, ''))
}

/** A path under one of the legacy prefixes that is a verification document. */
export function isLegacyDocPath(pathname: string): boolean {
  if (pathname.startsWith('workers/')) return pathname.includes('/address-change/')
  return LEGACY_PREFIXES.some((p) => pathname.startsWith(p))
}

function extFor(pathname: string, contentType: string | null): string {
  const fromPath = pathname.match(/\.([A-Za-z0-9]{2,5})$/)?.[1]?.toLowerCase()
  if (fromPath && ['jpg', 'jpeg', 'png', 'webp', 'pdf', 'heic'].includes(fromPath)) {
    return fromPath === 'jpeg' ? 'jpg' : fromPath
  }
  if (contentType === 'application/pdf') return 'pdf'
  if (contentType === 'image/png') return 'png'
  if (contentType === 'image/webp') return 'webp'
  return 'jpg'
}

/** Worker id from the referencing row, else from `worker-docs/{id}/` or `workers/{id}/`, else 0. */
export function workerIdFor(row: { workerId: number | null }, pathname: string): number {
  if (row.workerId && row.workerId > 0) return row.workerId
  const m = pathname.match(/^(?:worker-docs|workers)\/(\d+)\//)
  return m ? Number(m[1]) : 0
}

/** Private key for a legacy document. Matches the admin presign pattern. */
export function legacyKey(workerId: number, tag: string, ext: string): string {
  return `verification/${workerId}/legacy-${tag}.${ext}`
}

function shortHash(s: string): string {
  return crypto.createHash('sha256').update(s).digest('hex').slice(0, 16)
}

function md5Hex(bytes: Buffer): string {
  return crypto.createHash('md5').update(bytes).digest('hex')
}

interface Candidate {
  kind: 'media' | 'orphan'
  mediaId: number | null
  /** Every document row sharing this URL — all are repointed before the delete. */
  mediaIds: number[]
  url: string
  pathname: string
  workerId: number
  mimeType: string | null
}

export async function planCandidates(deps: MigrationDeps): Promise<Candidate[]> {
  const rows = await deps.listLegacyMediaRows()
  const candidates: Candidate[] = []
  const claimed = new Set<string>()
  const byUrl = new Map<string, Candidate>()
  for (const r of [...rows].sort((a, b) => a.id - b.id)) {
    if (!isPublicBlobUrl(r.url)) continue
    const pathname = pathnameOf(r.url)
    // Only document paths: a worker_id/proof_of_address row that points at a
    // spot photo or avatar must never cause that public file to be deleted.
    if (!isLegacyDocPath(pathname)) continue
    const existing = byUrl.get(r.url)
    if (existing) {
      existing.mediaIds.push(r.id)
      if (!existing.workerId && r.workerId) existing.workerId = workerIdFor(r, pathname)
      continue
    }
    const c: Candidate = {
      kind: 'media', mediaId: r.id, mediaIds: [r.id], url: r.url, pathname,
      workerId: workerIdFor(r, pathname), mimeType: r.mimeType,
    }
    byUrl.set(r.url, c)
    candidates.push(c)
    claimed.add(r.url)
  }
  const allMediaUrls = await deps.listAllMediaUrls()
  for (const prefix of LEGACY_PREFIXES) {
    for (const b of await deps.listBlobs(prefix)) {
      if (!isLegacyDocPath(b.pathname) || claimed.has(b.url) || allMediaUrls.has(b.url)) continue
      candidates.push({
        kind: 'orphan', mediaId: null, mediaIds: [], url: b.url, pathname: b.pathname,
        workerId: workerIdFor({ workerId: null }, b.pathname), mimeType: null,
      })
      claimed.add(b.url)
    }
  }
  return candidates
}

export async function migrateLegacyDocs(
  deps: MigrationDeps,
  opts: { apply: boolean; limit: number },
): Promise<MigrationResult> {
  const candidates = await planCandidates(deps)
  const batch = candidates.slice(0, Math.max(0, opts.limit))
  const manifest: ManifestEntry[] = []

  for (const c of batch) {
    const tag = c.kind === 'media' ? `m${c.mediaId}` : `orphan-${shortHash(c.pathname)}`
    let to = legacyKey(c.workerId, tag, extFor(c.pathname, c.mimeType))
    const entry: ManifestEntry = { kind: c.kind, mediaId: c.mediaId, from: c.pathname, to, status: 'planned' }
    manifest.push(entry)
    if (!opts.apply) continue

    try {
      const fetched = await deps.fetchPublic(c.url)
      if (!fetched) {
        entry.status = 'missing-public'
        continue
      }
      to = legacyKey(c.workerId, tag, extFor(c.pathname, fetched.contentType ?? c.mimeType))
      entry.to = to
      const contentType = fetched.contentType || c.mimeType || 'application/octet-stream'
      const md5 = md5Hex(fetched.bytes)
      entry.bytes = fetched.bytes.length
      entry.md5 = md5

      await deps.putPrivate(to, fetched.bytes, contentType)
      const head = await deps.headPrivate(to)
      const etag = head?.etag?.replace(/"/g, '').toLowerCase() ?? null
      if (!head || head.size !== fetched.bytes.length || etag !== md5) {
        entry.status = 'verify-failed'
        continue
      }

      let lost = false
      for (const id of c.mediaIds) {
        if (!(await deps.repointMedia(id, c.url, to))) lost = true
      }
      // Never delete while ANY row (any category) still points at the URL.
      if (lost || (await deps.isUrlReferenced(c.url))) {
        entry.status = 'row-changed'
        continue
      }

      await deps.deletePublic(c.url)
      entry.status = 'moved'
    } catch (e) {
      entry.status = 'error'
      entry.error = e instanceof Error ? e.message.slice(0, 200) : String(e).slice(0, 200)
    }
  }

  const moved = manifest.filter((m) => m.status === 'moved').length
  const failed = manifest.filter((m) => ['verify-failed', 'row-changed', 'error'].includes(m.status)).length
  return {
    apply: opts.apply,
    candidates: candidates.length,
    processed: batch.length,
    remaining: candidates.length - (opts.apply ? moved + manifest.filter((m) => m.status === 'missing-public').length : 0),
    moved,
    failed,
    manifest,
  }
}
