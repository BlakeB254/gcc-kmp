/**
 * Evidence photo fingerprints — DB reads/writes and the one entry point the
 * submission routes call (`checkEvidencePhotos`). Rules are in
 * photo-fingerprint.ts. Takes the executor so tests can use the recording db.
 *
 * Bounded and fail-open: hashing a photo gets FINGERPRINT_TIMEOUT_MS; a fetch
 * or decode failure records the photo as `unverified` (a review flag) and the
 * submission goes through. Only exact cross-job reuse refuses a submission.
 */
import { and, eq, inArray, ne, sql } from 'drizzle-orm'
import { photoFingerprints, photoJobBindings } from '@/db/schema'
import type { DbExecutor } from '@/lib/job-lifecycle'
import {
  FINGERPRINT_TIMEOUT_MS,
  NEAR_DUPLICATE_LOOKBACK_DAYS,
  NEAR_DUPLICATE_MAX_HAMMING,
  evaluateBeforeAfter,
  evaluatePhoto,
  type EvidenceContext,
  type FingerprintFacts,
  type OtherPhoto,
  type PhotoFlag,
} from '@/lib/photo-fingerprint'
import {
  computeFingerprint,
  fetchBlobBounded,
  withTimeout,
  type ComputedFingerprint,
} from '@/lib/photo-fingerprint-compute'

const FACT_COLUMNS = {
  url: photoFingerprints.url,
  status: photoFingerprints.status,
  sha256: photoFingerprints.sha256,
  dhash: photoFingerprints.dhash,
  exifCapturedAt: photoFingerprints.exifCapturedAt,
  firstJobId: photoFingerprints.firstJobId,
  firstScanId: photoFingerprints.firstScanId,
}

type FactRow = {
  url: string
  status: string
  sha256: string | null
  dhash: bigint | string | number | null
  exifCapturedAt: string | null
  firstJobId: number | null
  firstScanId: number | null
}

function toFacts(row: FactRow): FingerprintFacts {
  return {
    url: row.url,
    status: row.status === 'ok' ? 'ok' : 'unverified',
    sha256: row.sha256 ? String(row.sha256).trim() : null,
    dhash: row.dhash == null ? null : BigInt(row.dhash),
    exifCapturedAt: row.exifCapturedAt ?? null,
    firstJobId: row.firstJobId == null ? null : Number(row.firstJobId),
    firstScanId: row.firstScanId == null ? null : Number(row.firstScanId),
  }
}

/** Upserts a computed (or failed) fingerprint for `url`. A good row is never downgraded. */
async function saveFingerprint(
  exec: DbExecutor,
  url: string,
  computed: ComputedFingerprint | null,
  meta: { workerId?: number | null; error?: string },
): Promise<FingerprintFacts> {
  const now = new Date().toISOString()
  const values = computed
    ? {
        url,
        status: 'ok',
        sha256: computed.sha256,
        dhash: computed.dhash,
        byteSize: computed.byteSize,
        width: computed.width,
        height: computed.height,
        exifCapturedAt: computed.exifCapturedAt,
        uploadedByWorkerId: meta.workerId ?? null,
        error: null,
        updatedAt: now,
      }
    : { url, status: 'unverified', uploadedByWorkerId: meta.workerId ?? null, error: (meta.error ?? 'unknown').slice(0, 200), updatedAt: now }
  const [row] = await exec
    .insert(photoFingerprints)
    .values(values)
    .onConflictDoUpdate({
      target: photoFingerprints.url,
      set: computed
        ? {
            status: 'ok',
            sha256: computed.sha256,
            dhash: computed.dhash,
            byteSize: computed.byteSize,
            width: computed.width,
            height: computed.height,
            // EXIF read at upload (from the original) beats a later re-read
            // of the stripped blob.
            exifCapturedAt: sql`coalesce(${photoFingerprints.exifCapturedAt}, excluded.exif_captured_at)`,
            error: null,
            updatedAt: now,
          }
        : { updatedAt: now },
    })
    .returning(FACT_COLUMNS)
  return toFacts(row as FactRow)
}

/**
 * Called by POST /api/spots/upload with both the phone's original bytes
 * (EXIF) and the re-encoded bytes it stored. Never throws.
 */
export async function recordUploadFingerprint(
  exec: DbExecutor,
  url: string,
  stored: Buffer,
  original: Buffer,
  workerId: number,
): Promise<void> {
  try {
    const computed = await withTimeout(computeFingerprint(stored, original), FINGERPRINT_TIMEOUT_MS)
    await saveFingerprint(exec, url, computed, { workerId })
  } catch (error) {
    console.warn('photo fingerprint at upload failed (will retry at submission):', error)
  }
}

/** Loads a photo's bytes for hashing. The default is the bounded, host-locked blob fetch. */
export type PhotoLoader = (url: string) => Promise<Buffer>

const defaultLoader: PhotoLoader = (url) => fetchBlobBounded(url)

/**
 * The fingerprint row for `url`, hashing it now if it has none (or a previous
 * attempt left it unverified). Failure → an `unverified` row.
 *
 * `mayFetch` false (the URL is not an upload the submitter owns) never loads
 * the URL: a stored good row is used as-is, otherwise the photo is recorded
 * `unverified` without any request leaving the server.
 */
export async function ensureFingerprint(
  exec: DbExecutor,
  url: string,
  options: { mayFetch: boolean; load?: PhotoLoader },
): Promise<FingerprintFacts> {
  const [existing] = await exec.select(FACT_COLUMNS).from(photoFingerprints).where(eq(photoFingerprints.url, url)).limit(1)
  if (existing && existing.status === 'ok') return toFacts(existing as FactRow)
  if (!options.mayFetch) {
    if (existing) return toFacts(existing as FactRow)
    return saveFingerprint(exec, url, null, { error: 'not an upload by this worker; not fetched' })
  }
  try {
    const load = options.load ?? defaultLoader
    const computed = await withTimeout(load(url).then((bytes) => computeFingerprint(bytes)), FINGERPRINT_TIMEOUT_MS)
    return await saveFingerprint(exec, url, computed, {})
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    if (existing) return toFacts(existing as FactRow)
    return saveFingerprint(exec, url, null, { error: message })
  }
}

async function exactMatches(exec: DbExecutor, self: FingerprintFacts): Promise<OtherPhoto[]> {
  if (!self.sha256) return []
  const rows = await exec
    .select({ url: photoFingerprints.url, firstJobId: photoFingerprints.firstJobId, firstScanId: photoFingerprints.firstScanId })
    .from(photoFingerprints)
    .where(and(eq(photoFingerprints.sha256, self.sha256), ne(photoFingerprints.url, self.url)))
    .limit(20)
  return rows.map((r) => ({ ...r, distance: 0 }))
}

async function nearMatches(exec: DbExecutor, self: FingerprintFacts): Promise<OtherPhoto[]> {
  if (self.dhash == null) return []
  const distance = sql<number>`bit_count((${photoFingerprints.dhash} # ${self.dhash.toString()}::bigint)::bit(64))::int`
  const rows = await exec
    .select({
      url: photoFingerprints.url,
      firstJobId: photoFingerprints.firstJobId,
      firstScanId: photoFingerprints.firstScanId,
      distance,
    })
    .from(photoFingerprints)
    .where(
      and(
        ne(photoFingerprints.url, self.url),
        sql`${photoFingerprints.dhash} is not null`,
        sql`${photoFingerprints.createdAt} > now() - make_interval(days => ${NEAR_DUPLICATE_LOOKBACK_DAYS}::int)`,
        sql`${distance} <= ${NEAR_DUPLICATE_MAX_HAMMING}::int`,
      ),
    )
    .orderBy(distance)
    .limit(5)
  return rows.map((r) => ({ ...r, distance: Number(r.distance) }))
}

/** Binding keys for a photo: its URL, and its bytes when they are known. */
export function photoBindingKeys(facts: Pick<FingerprintFacts, 'url' | 'sha256'>): string[] {
  return [`url:${facts.url}`, ...(facts.sha256 ? [`sha256:${facts.sha256}`] : [])]
}

/**
 * Atomically binds each key to `jobId` (INSERT … ON CONFLICT DO NOTHING on the
 * photo_job_bindings primary key). Returns the other job a key already
 * belongs to, or null when every key is free or already this job's. Two
 * concurrent submissions of the same photo cannot both win: exactly one
 * INSERT succeeds per key, and the loser reads the winner's job id.
 */
export async function claimPhotoForJob(exec: DbExecutor, keys: string[], jobId: number): Promise<number | null> {
  for (const key of keys) {
    const [won] = await exec
      .insert(photoJobBindings)
      .values({ photoKey: key, jobId })
      .onConflictDoNothing({ target: photoJobBindings.photoKey })
      .returning({ jobId: photoJobBindings.jobId })
    if (won) continue
    const [holder] = await exec
      .select({ jobId: photoJobBindings.jobId })
      .from(photoJobBindings)
      .where(eq(photoJobBindings.photoKey, key))
      .limit(1)
    if (holder && Number(holder.jobId) !== jobId) return Number(holder.jobId)
  }
  return null
}

export type EvidencePhotoInput = { url: string; role: 'before' | 'after' }

export type EvidencePhotoResult = {
  url: string
  role: 'before' | 'after'
  status: 'ok' | 'unverified'
  flags: PhotoFlag[]
}

export type EvidenceCheck = {
  /** Non-null → refuse the submission with this message (exact cross-job reuse). */
  reject: string | null
  photos: EvidencePhotoResult[]
}

function reuseMessage(jobId: number): string {
  return `This photo was already submitted as evidence for job #${jobId}. Take a new photo for this job.`
}

/**
 * Fingerprints and checks the photos of one submission, then (unless it is
 * refused) binds them to `ctx` and records their flags. `pairs` lists
 * before/after URL pairs of the same piece of work — including an
 * already-stored before photo when its after photo arrives.
 *
 * `isOwn` decides, per URL, whether the submitter uploaded it (the caller's
 * isOwnEvidenceUrl rule). Every URL — submitted or stored — goes through it
 * before anything is fetched; a URL that fails is flagged `not_own_upload`
 * and never requested.
 */
export async function checkEvidencePhotos(
  exec: DbExecutor,
  photos: EvidencePhotoInput[],
  ctx: EvidenceContext,
  options: { isOwn: (url: string) => boolean; pairs?: Array<[string, string]>; load?: PhotoLoader },
): Promise<EvidenceCheck> {
  // In parallel, so a submission waits at most one FINGERPRINT_TIMEOUT_MS.
  const urls = [...new Set([...photos.map((p) => p.url), ...(options.pairs ?? []).flat()])]
  const own = new Map(urls.map((url) => [url, options.isOwn(url)]))
  const facts = new Map<string, FingerprintFacts>(
    await Promise.all(
      urls.map(async (url) => [url, await ensureFingerprint(exec, url, { mayFetch: own.get(url)!, load: options.load })] as const),
    ),
  )

  const flagsByUrl = new Map<string, Set<PhotoFlag>>()
  let reject: string | null = null
  for (const { url } of photos) {
    const self = facts.get(url)!
    let verdict
    try {
      verdict = evaluatePhoto(self, ctx, await exactMatches(exec, self), await nearMatches(exec, self))
    } catch (error) {
      // A comparison query failing must not fail the submission.
      console.warn('photo fingerprint comparison failed:', error)
      verdict = evaluatePhoto({ ...self, status: 'unverified' }, ctx, [], [])
    }
    const flags = new Set(verdict.flags)
    if (!own.get(url)) flags.add('not_own_upload')
    flagsByUrl.set(url, flags)
    reject ??= verdict.reject
  }
  for (const [beforeUrl, afterUrl] of options.pairs ?? []) {
    const pairFlags = evaluateBeforeAfter(facts.get(beforeUrl)!, facts.get(afterUrl)!)
    for (const url of [beforeUrl, afterUrl]) {
      if (!flagsByUrl.has(url)) continue
      for (const f of pairFlags) flagsByUrl.get(url)!.add(f)
    }
  }

  // Exact cross-job reuse, decided atomically (the read above is only a fast path).
  if (!reject && ctx.kind === 'job') {
    for (const { url } of photos) {
      const holder = await claimPhotoForJob(exec, photoBindingKeys(facts.get(url)!), ctx.id)
      if (holder != null) {
        flagsByUrl.get(url)!.add('reused_other_job')
        reject = reuseMessage(holder)
        break
      }
    }
  }

  const results: EvidencePhotoResult[] = photos.map(({ url, role }) => ({
    url,
    role,
    status: facts.get(url)!.status,
    flags: [...(flagsByUrl.get(url) ?? [])],
  }))
  if (reject) return { reject, photos: results }

  for (const r of results) {
    // A row already bound to OTHER work keeps that work's binding and flags:
    // flags are stored per URL, and this use must not rewrite the first one's
    // record (or dock its points). This use's flags still go back to the caller.
    const self = facts.get(r.url)!
    const boundElsewhere =
      (ctx.kind === 'job' && self.firstJobId != null && self.firstJobId !== ctx.id) ||
      (ctx.kind === 'scan' && self.firstScanId != null && self.firstScanId !== ctx.id)
    if (boundElsewhere) continue
    await exec
      .update(photoFingerprints)
      .set({
        firstJobId: ctx.kind === 'job' ? sql`coalesce(${photoFingerprints.firstJobId}, ${ctx.id}::int)` : undefined,
        firstScanId: ctx.kind === 'scan' ? sql`coalesce(${photoFingerprints.firstScanId}, ${ctx.id}::int)` : undefined,
        flags: r.flags,
        updatedAt: new Date().toISOString(),
      })
      .where(eq(photoFingerprints.url, r.url))
  }
  return { reject: null, photos: results }
}

export type StoredPhotoFlags = {
  status: string
  /** Flags recorded when the photo was first submitted (for its first job/scan). */
  flags: string[]
  firstJobId: number | null
  firstScanId: number | null
}

/** Stored fingerprint status, flags and binding, keyed by URL. */
export async function loadPhotoFlags(exec: DbExecutor, urls: string[]): Promise<Map<string, StoredPhotoFlags>> {
  const out = new Map<string, StoredPhotoFlags>()
  const unique = [...new Set(urls)].filter(Boolean)
  if (unique.length === 0) return out
  const rows = await exec
    .select({
      url: photoFingerprints.url,
      status: photoFingerprints.status,
      flags: photoFingerprints.flags,
      firstJobId: photoFingerprints.firstJobId,
      firstScanId: photoFingerprints.firstScanId,
    })
    .from(photoFingerprints)
    .where(inArray(photoFingerprints.url, unique))
  for (const r of rows) {
    out.set(r.url, {
      status: r.status,
      flags: Array.isArray(r.flags) ? r.flags : [],
      firstJobId: r.firstJobId ?? null,
      firstScanId: r.firstScanId ?? null,
    })
  }
  return out
}

/**
 * The flags that apply to a photo as evidence for `ctx`: its stored flags if
 * it was first submitted for this work, else `reused_other_job` /
 * `reused_other_scan` — the binding itself is the evidence of reuse.
 */
export function flagsForUse(stored: StoredPhotoFlags | undefined, ctx: { kind: 'job' | 'scan'; id: number }): string[] {
  if (!stored) return []
  if (ctx.kind === 'job' && stored.firstJobId != null && stored.firstJobId !== ctx.id) return ['reused_other_job']
  if (ctx.kind === 'scan' && stored.firstScanId != null && stored.firstScanId !== ctx.id) return ['reused_other_scan']
  return stored.flags
}
