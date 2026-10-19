/**
 * Scan indexer: mirrors R2 (`gcc-scan-storage`) capture sessions into
 * `scan_sessions` / `capture_chunks` / `reconstruction_assets`, then enqueues
 * `reconstruction_jobs` for the Spark GPU worker. Driven by
 * GET /api/cron/scan-index.
 *
 * Port of backend/…/ScanIndexer.kt, which ran nowhere after 2026-09-08 — so
 * nothing new was indexed or queued for reconstruction. Row shapes, the '0:0'
 * cell fallback and the enqueue rule are unchanged (see ./manifest.ts and
 * ./db.ts). What differs is the schedule shape: Kotlin re-read every manifest
 * every 30 minutes in one long-lived process; a Vercel function has 60s. So
 * each run re-indexes only manifests whose session changed since its last
 * index, using the tables themselves as the watermark:
 *
 *  - new:     the manifest key has no `capture_chunks` row yet;
 *  - changed: an object the session owns (its manifest, a chunk row it already
 *             has, or anything under `/{sessionId}/capture/`) appeared, changed
 *             size, or was modified after its row's `indexed_at`;
 *  - metrics: the event log is now in R2 but the session's metrics are null
 *             (a log that uploaded after the manifest — Kotlin self-healed this
 *             on the next sweep; this does the same, a few per run).
 *
 * Work is taken in that order, in listing order within each class, until the
 * time budget runs out; what's left is still "new"/"changed" next run, so the
 * backlog drains without a stored cursor. Every write is an idempotent upsert.
 */
import {
  ManifestError,
  assertManifestKey,
  isUnderFolder,
  buildChunkRows,
  confineManifestKeys,
  buildSessionRow,
  dedupeChunks,
  parseManifest,
  parseReconstructedKey,
  type ChunkRow,
  type KeyGate,
  type KeyNamespace,
  type ScanManifest,
  type SessionRow,
} from './manifest'
import { grantGate, legacyGate, manifestOwner, writtenAfterGrant, type UploadGrant } from './grants'
import {
  EventLogUnreadable,
  MAX_EVENT_LOG_BYTES,
  extractEventLogMetrics,
  gunzipLines,
  type EventLogMetrics,
} from './event-log-metrics'

/** Manifests are ~100KB at most in prod; refuse to fetch anything absurd. */
export const MAX_MANIFEST_BYTES = 4 * 1024 * 1024

/**
 * Metrics recorded for a log that can never be read (too large, not gzip, a
 * decompression bomb): zero decodable events, everything else unknown. A
 * non-null event_count is what stops the indexer refetching it every run.
 */
export const UNREADABLE_LOG_METRICS: Partial<EventLogMetrics> = { eventCount: 0 }

export interface ObjectInfo {
  size: number
  lastModifiedMs: number
}

export interface IndexedChunk {
  sessionId: string
  byteSize: number | null
  indexedAtMs: number
}

export interface IndexedSession {
  sessionId: string
  eventLogR2Key: string | null
  hasEventMetrics: boolean
}

export interface ReconAssetRow {
  locationCellId: string
  assetType: string
  format: string
  r2Key: string
  version: number
}

/**
 * A manifest the indexer can never accept as uploaded (undecodable, missing a
 * required field, a key that doesn't match its ids, too large, or a session
 * owned by another device). Recorded in scan_manifest_rejects so it is read
 * once, not every run, and so the watchdog alerts on it once instead of
 * staying red. Keyed by r2_key; a re-upload (new size or mtime) is retried.
 */
export interface ManifestReject {
  r2Key: string
  reason: string
  size: number
  lastModifiedMs: number
}

/** Thrown by `getBytes` when the object holds more than `maxBytes`. */
export class ObjectTooLarge extends Error {}

export interface ScanIndexDeps {
  /** Every object under `prefix`, in listing order. */
  listAll(prefix: string): Promise<Map<string, ObjectInfo>>
  /**
   * The object's bytes, never more than `maxBytes` — enforced on the fetch
   * itself (ranged GET), not on the listing, which can be stale by the time
   * the object is read. Throws ObjectTooLarge past the cap.
   */
  getBytes(key: string, maxBytes: number): Promise<Uint8Array>
  /** Epoch-ms from which manifests must be granted (grants.ts); null = no grants yet. */
  grantCutoverMs(): Promise<number | null>
  grantsFor(keys: string[]): Promise<Map<string, UploadGrant>>
  /** capture_chunks keyed by r2_key. */
  loadChunks(): Promise<Map<string, IndexedChunk>>
  /** scan_sessions keyed by manifest_r2_key. */
  loadSessions(): Promise<Map<string, IndexedSession>>
  /**
   * Insert, or update the existing row only when it belongs to the same device
   * and is not another worker's (see db.ts sessionUpsert). `grantsLive` = the
   * grants migration is applied and in use. Returns false when refused.
   */
  upsertSession(
    row: SessionRow,
    metrics: Partial<EventLogMetrics> | null,
    grantsLive: boolean,
    /** May stamp the worker onto an existing unowned row (proof: written after its grant). */
    claimUnowned: boolean,
  ): Promise<boolean>
  /** Upsert; a key already owned by another session is left alone. Returns keys left alone. */
  upsertChunks(rows: ChunkRow[]): Promise<string[]>
  /** reconstruction_assets.r2_key values already recorded, among `keys`. */
  existingReconKeys(keys: string[]): Promise<Set<string>>
  /** Insert-if-absent; returns how many rows were inserted. */
  insertReconAssets(rows: ReconAssetRow[]): Promise<number>
  /** The Kotlin ENQUEUE_RECON_JOBS statement; returns rows queued. */
  enqueueReconstructionJobs(): Promise<number>
  /** scan_manifest_rejects keyed by r2_key (size + mtime of the rejected upload). */
  loadRejects(): Promise<Map<string, ObjectInfo>>
  /** Upsert on r2_key; keeps first_seen_at and alerted_at. */
  recordRejects(rows: ManifestReject[]): Promise<void>
  /** A rejected manifest that has since indexed cleanly. */
  clearRejects(keys: string[]): Promise<void>
  log?(msg: string): void
}

export interface ScanIndexOptions {
  /** Stop starting new manifests at this epoch-ms. */
  deadlineMs: number
  /** Upper bound on manifests processed per run. */
  maxManifests?: number
  /** Event-log metric retries per run. */
  maxMetricRetries?: number
  /** Re-index every manifest (Kotlin's full sweep), budget permitting. */
  full?: boolean
  now?: () => number
}

export interface ScanIndexResult {
  manifestsSeen: number
  sessionsIndexed: number
  chunksIndexed: number
  manifestsFailed: number
  failedKeys: string[]
  /** Of the failures, those recorded as permanently rejected this run. */
  manifestsRejected: number
  /** Rejected on an earlier run and unchanged since — not re-read. */
  manifestsKnownBad: number
  /** Keys manifests named outside their own folders (or, post-cutover, not granted to their worker), dropped. */
  foreignKeysDropped: number
  /** Chunk keys already owned by a different session, left alone. */
  chunksOwnedElsewhere: number
  /** Manifests indexed under the upload-grant rules (post-cutover). */
  sessionsGranted: number
  reconstructionsIndexed: number
  reconstructionsSkipped: number
  /** Recon asset keys in a batch whose insert failed (logged; retried next run). */
  reconstructionsFailed: number
  reconstructionJobsEnqueued: number
  /** Manifests still needing (re)index when the budget ran out. */
  pending: number
  durationMs: number
}

// reconstruction_assets.asset_type is varchar(20), format varchar(10),
// location_cell_id varchar(32), r2_key varchar(500).
const ASSET_TYPE_MAX = 20
const FORMAT_MAX = 10
const CELL_MAX = 32
const RECON_KEY_MAX = 500
const RECON_BATCH = 500

type WorkClass = 'new' | 'changed' | 'metrics'

/** Keys are attacker-sized; keep logs and the response readable. */
const short = (key: string) => (key.length > 200 ? `${key.slice(0, 200)}…(${key.length} chars)` : key)

/** Every key a manifest names or could pick up late, for one grants lookup. */
function candidateKeys(manifestKey: string, m: ScanManifest, lateKeys: string[]): string[] {
  const keys = [manifestKey, ...m.photoKeys, ...(m.captureChunks ?? []), ...lateKeys]
  if (m.objectCapture?.r2Key) keys.push(m.objectCapture.r2Key)
  if (m.eventLogKey) keys.push(m.eventLogKey)
  return [...new Set(keys)]
}

/** Why `manifestKey` needs (re)indexing, or null when its rows are current. */
export function classifyManifest(
  manifestKey: string,
  raw: Map<string, ObjectInfo>,
  chunks: Map<string, IndexedChunk>,
  sessions: Map<string, IndexedSession>,
  sessionChunkKeys: Map<string, string[]>,
  rawKeysBySession: (sessionId: string) => string[],
): WorkClass | null {
  const own = chunks.get(manifestKey)
  if (!own) return 'new'
  // Appeared, resized or rewritten since its row was written. A vanished
  // object is deliberately not a trigger: a row the manifest no longer names
  // is never rewritten (Kotlin never deleted rows either), so it would read
  // as stale forever.
  const stale = (key: string, info: ObjectInfo | undefined) => {
    if (info === undefined) return false
    const c = chunks.get(key)
    if (!c) return true
    return info.size !== c.byteSize || info.lastModifiedMs > c.indexedAtMs
  }
  if (stale(manifestKey, raw.get(manifestKey))) return 'changed'
  for (const key of sessionChunkKeys.get(own.sessionId) ?? []) {
    if (stale(key, raw.get(key))) return 'changed'
  }
  for (const key of rawKeysBySession(own.sessionId)) {
    if (stale(key, raw.get(key))) return 'changed'
  }
  const s = sessions.get(manifestKey)
  if (s && s.eventLogR2Key && !s.hasEventMetrics && raw.has(s.eventLogR2Key)) return 'metrics'
  return null
}

export async function runScanIndex(deps: ScanIndexDeps, opts: ScanIndexOptions): Promise<ScanIndexResult> {
  const now = opts.now ?? Date.now
  const start = now()
  const log = deps.log ?? (() => {})
  const [raw, reconstructed, chunks, sessions, rejects, cutoverMs] = await Promise.all([
    deps.listAll('raw/'),
    deps.listAll('reconstructed/'),
    deps.loadChunks(),
    deps.loadSessions(),
    deps.loadRejects(),
    deps.grantCutoverMs(),
  ])

  const sessionChunkKeys = new Map<string, string[]>()
  for (const [key, c] of chunks) {
    const list = sessionChunkKeys.get(c.sessionId)
    if (list) list.push(key)
    else sessionChunkKeys.set(c.sessionId, [key])
  }
  // Late capture objects are found by `/{sessionId}/capture/` in the key; index
  // the listing once by that segment instead of scanning it per manifest.
  const captureKeysBySession = new Map<string, string[]>()
  for (const key of raw.keys()) {
    const m = /\/([^/]+)\/capture\//.exec(key)
    if (!m) continue
    const list = captureKeysBySession.get(m[1])
    if (list) list.push(key)
    else captureKeysBySession.set(m[1], [key])
  }
  const rawKeysBySession = (id: string) => captureKeysBySession.get(id) ?? []

  const manifestKeys = [...raw.keys()].filter((k) => k.endsWith('manifest.json'))
  const work: Record<WorkClass, string[]> = { new: [], changed: [], metrics: [] }
  let knownBad = 0
  for (const key of manifestKeys) {
    const cls = opts.full ? (chunks.has(key) ? 'changed' : 'new')
      : classifyManifest(key, raw, chunks, sessions, sessionChunkKeys, rawKeysBySession)
    if (cls === 'new' && !opts.full) {
      const rejected = rejects.get(key)
      const info = raw.get(key)!
      if (rejected && rejected.size === info.size && rejected.lastModifiedMs === info.lastModifiedMs) {
        knownBad++
        continue
      }
    }
    if (cls) work[cls].push(key)
  }
  const queue = [...work.new, ...work.changed, ...work.metrics.slice(0, opts.maxMetricRetries ?? 5)]
  const maxManifests = opts.maxManifests ?? Number.POSITIVE_INFINITY

  const result: ScanIndexResult = {
    manifestsSeen: manifestKeys.length,
    sessionsIndexed: 0,
    chunksIndexed: 0,
    manifestsFailed: 0,
    failedKeys: [],
    manifestsRejected: 0,
    manifestsKnownBad: knownBad,
    foreignKeysDropped: 0,
    chunksOwnedElsewhere: 0,
    sessionsGranted: 0,
    reconstructionsIndexed: 0,
    reconstructionsSkipped: 0,
    reconstructionsFailed: 0,
    reconstructionJobsEnqueued: 0,
    pending: 0,
    durationMs: 0,
  }

  const newRejects: ManifestReject[] = []
  const cleared: string[] = []
  let processed = 0
  for (const manifestKey of queue) {
    if (processed >= maxManifests || now() >= opts.deadlineMs) break
    processed++
    try {
      const listedSize = raw.get(manifestKey)?.size ?? 0
      if (listedSize > MAX_MANIFEST_BYTES) throw new ManifestError(`manifest is ${listedSize} bytes`)
      let text: string
      try {
        text = new TextDecoder().decode(await deps.getBytes(manifestKey, MAX_MANIFEST_BYTES))
      } catch (e) {
        if (e instanceof ObjectTooLarge) throw new ManifestError(e.message)
        throw e
      }
      const parsed = parseManifest(text)
      assertManifestKey(manifestKey, parsed)
      // Uploaded after the grants cutover: the manifest's own key must have
      // been granted to a worker (for an object written in the grant's
      // window), and it may only claim keys validly granted to that same
      // worker. A legacy manifest keeps the folder rules, but anything added
      // to its session after the cutover needs a grant from one single worker.
      let workerId: number | null = null
      let claimUnowned = false
      let gate: KeyGate | undefined
      if (cutoverMs != null) {
        const keys = candidateKeys(manifestKey, parsed, rawKeysBySession(parsed.sessionId))
        if (raw.get(manifestKey)!.lastModifiedMs >= cutoverMs) {
          const grants = await deps.grantsFor(keys)
          workerId = manifestOwner(manifestKey, parsed, grants, raw)
          if (workerId == null) throw new ManifestError('uploaded after the grants cutover without a valid upload grant for its device')
          gate = grantGate(parsed, workerId, grants, raw)
          claimUnowned = writtenAfterGrant(manifestKey, grants, raw)
        } else {
          const late = keys.filter((k) => (raw.get(k)?.lastModifiedMs ?? -Infinity) >= cutoverMs)
          if (late.length > 0) {
            const nsOf = (k: string): KeyNamespace => (isUnderFolder(k, parsed.sessionId) ? 'session' : 'device')
            gate = legacyGate(parsed, late.map((key) => ({ key, ns: nsOf(key) })), cutoverMs, await deps.grantsFor(late), raw)
          }
        }
      }
      const { manifest, dropped } = confineManifestKeys(parsed, gate)
      if (dropped.length > 0) {
        result.foreignKeysDropped += dropped.length
        log(`${short(manifestKey)}: dropped ${dropped.length} key(s) outside the session's folders or not granted to its worker: ${dropped.slice(0, 5).map(short).join(', ')}`)
      }
      const chunkRows = buildChunkRows(
        manifest,
        manifestKey,
        (k) => raw.get(k)?.size,
        rawKeysBySession(manifest.sessionId),
        gate,
      )
      // Gated on the listing: a manifest can name a log whose upload is still
      // retrying. A transient failure leaves the metrics null (the upsert's
      // COALESCE keeps earlier values, and a later run retries); a log that
      // can never be read is recorded as such so it is not refetched forever.
      let metrics: Partial<EventLogMetrics> | null = null
      const logInfo = manifest.eventLogKey != null ? raw.get(manifest.eventLogKey) : undefined
      if (manifest.eventLogKey != null && logInfo) {
        try {
          if (logInfo.size > MAX_EVENT_LOG_BYTES) throw new EventLogUnreadable(`event log is ${logInfo.size} bytes`)
          metrics = extractEventLogMetrics(gunzipLines(await deps.getBytes(manifest.eventLogKey, MAX_EVENT_LOG_BYTES)))
        } catch (e) {
          if (e instanceof EventLogUnreadable || e instanceof ObjectTooLarge) metrics = UNREADABLE_LOG_METRICS
          log(`failed to extract event log ${manifest.eventLogKey}: ${(e as Error).message}`)
        }
      }
      if (!(await deps.upsertSession(buildSessionRow(manifest, manifestKey, chunkRows, workerId), metrics, cutoverMs != null, claimUnowned))) {
        throw new ManifestError(`session ${manifest.sessionId} is already indexed for another device or worker`)
      }
      if (workerId != null) result.sessionsGranted++
      const leftAlone = await deps.upsertChunks(dedupeChunks(chunkRows))
      if (leftAlone.length > 0) {
        result.chunksOwnedElsewhere += leftAlone.length
        log(`${manifestKey}: ${leftAlone.length} key(s) already belong to another session: ${leftAlone.slice(0, 5).join(', ')}`)
      }
      result.sessionsIndexed++
      result.chunksIndexed += chunkRows.length
      if (rejects.has(manifestKey)) cleared.push(manifestKey)
    } catch (e) {
      result.manifestsFailed++
      if (result.failedKeys.length < 20) result.failedKeys.push(short(manifestKey))
      const why = (e as Error).message
      log(`failed to index manifest ${short(manifestKey)}: ${why}`)
      // Only a verdict on the manifest itself is permanent; R2 or DB errors
      // are retried next run.
      if (e instanceof ManifestError) {
        const info = raw.get(manifestKey)!
        newRejects.push({ r2Key: manifestKey, reason: why.slice(0, 500), size: info.size, lastModifiedMs: info.lastModifiedMs })
      }
    }
  }
  result.pending = queue.length - processed
  // Bookkeeping only: if it fails the manifests are simply re-read next run,
  // so it must never cost the reconstruction pass and the enqueue below.
  if (newRejects.length > 0) {
    try {
      await deps.recordRejects(newRejects)
      result.manifestsRejected = newRejects.length
    } catch (e) {
      log(`failed to record ${newRejects.length} rejected manifest(s): ${(e as Error).message}`)
    }
  }
  if (cleared.length > 0) {
    try {
      await deps.clearRejects(cleared)
    } catch (e) {
      log(`failed to clear ${cleared.length} recovered manifest(s): ${(e as Error).message}`)
    }
  }

  const reconRows: ReconAssetRow[] = []
  for (const key of reconstructed.keys()) {
    if (key.endsWith('/')) continue
    const p = parseReconstructedKey(key)
    if (!p) continue
    if (p.assetType.length > ASSET_TYPE_MAX || p.format.length > FORMAT_MAX || p.cell.length > CELL_MAX || key.length > RECON_KEY_MAX) {
      result.reconstructionsSkipped++
      continue
    }
    reconRows.push({ locationCellId: p.cell, assetType: p.assetType, format: p.format, r2Key: key, version: p.version })
  }
  // Batch by batch, each isolated: a bad batch is logged and skipped so the
  // enqueue below always runs. (r2_key has no unique index on this table — the
  // recon worker owns it — so the insert is NOT EXISTS-guarded, not ON CONFLICT.)
  for (let i = 0; i < reconRows.length; i += RECON_BATCH) {
    const part = reconRows.slice(i, i + RECON_BATCH)
    try {
      const existing = await deps.existingReconKeys(part.map((r) => r.r2Key))
      const missing = part.filter((r) => !existing.has(r.r2Key))
      if (missing.length > 0) result.reconstructionsIndexed += await deps.insertReconAssets(missing)
    } catch (e) {
      result.reconstructionsFailed += part.length
      log(`reconstruction assets batch ${i / RECON_BATCH} failed (${part.length} keys): ${(e as Error).message}`)
    }
  }

  // Every completed, unreconstructed session with a full capture set
  // (pose jsonl + depth bin + video mov) becomes a job. Idempotent on session_id.
  result.reconstructionJobsEnqueued = await deps.enqueueReconstructionJobs()
  result.durationMs = now() - start
  return result
}
