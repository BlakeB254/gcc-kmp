/**
 * SQL for the scan indexer, written against a bare `SqlExec` so the same
 * statements run through neon-http in production and through a plain Postgres
 * client in local verification runs. Drizzle (`src/db/schema.ts`) owns these
 * tables; this only reads and writes them, with the exact upserts
 * backend/…/ScanIndexRepository.kt used.
 */
import { sql, type SQL } from 'drizzle-orm'
import type { ChunkRow, SessionRow } from './manifest'
import type { EventLogMetrics } from './event-log-metrics'
import type { IndexedChunk, IndexedSession, ObjectInfo, ReconAssetRow, ScanIndexDeps } from './indexer'
import type { WatchdogDeps } from './watchdog'

export type SqlExec = (query: SQL) => Promise<Record<string, unknown>[]>

/** Rows come back as a bare array on some drivers and `{ rows }` on others. */
export function rowsOf(res: unknown): Record<string, unknown>[] {
  if (Array.isArray(res)) return res
  const rows = (res as { rows?: Record<string, unknown>[] } | null)?.rows
  return Array.isArray(rows) ? rows : []
}

const ts = (ms: number | null) => (ms == null ? null : new Date(ms).toISOString())
const num = (v: unknown) => (v == null ? null : Number(v))

function* batches<T>(xs: T[], size: number): Generator<T[]> {
  for (let i = 0; i < xs.length; i += size) yield xs.slice(i, i + size)
}

export const ENQUEUE_RECONSTRUCTION_JOBS = sql`
  with queued as (
    insert into reconstruction_jobs (session_id, location_cell_id)
    select s.session_id, s.location_cell_id
    from scan_sessions s
    where s.reconstruction_version is null
      and s.completed_at is not null
      and s.location_cell_id is not null
      and exists (select 1 from capture_chunks c where c.session_id = s.session_id and c.format = 'jsonl')
      and exists (select 1 from capture_chunks c where c.session_id = s.session_id and c.format = 'bin')
      and exists (select 1 from capture_chunks c where c.session_id = s.session_id and c.format = 'mov')
    on conflict (session_id) do nothing
    returning 1
  )
  select count(*)::int as n from queued
`

/**
 * Kotlin's upsert, plus ownership guards on the update (RETURNING tells the
 * caller whether anything was written):
 *  - always: only a manifest from the same device updates the row;
 *  - granted manifest (worker known): the row must be that worker's. An
 *    unowned (legacy) row is stamped only with `claimUnowned` — the manifest
 *    object at the same manifest_r2_key provably written after its grant —
 *    never just because the key matches, and never a cross-cell re-claim;
 *  - legacy manifest once grants are live: never touches a worker's row
 *    unless it is the same object.
 * Before migration 0054 (grants not live) only the device guard applies, and
 * worker_id is not referenced at all so the statement still runs.
 */
export function sessionUpsert(
  row: SessionRow,
  m: Partial<EventLogMetrics> | null,
  grantsLive = false,
  claimUnowned = false,
): SQL {
  const granted = row.workerId != null
  return sql`
    insert into scan_sessions
      (session_id, device_id, device_model, platform, os_version, lap_kind, route_id,
       location_cell_id, started_at, completed_at, photo_count, chunk_count,
       observed_item_count, scanned_cell_count, has_object_capture, object_capture_r2_key,
       manifest_r2_key, total_bytes, geo_ref_event_count,
       event_log_r2_key, event_count, gate_flip_count, tap_count, confirm_count,
       reject_count, raycast_miss_count, detection_count, upload_attempt_count,
       upload_failure_count, error_count,
       video_coverage_pct, max_thermal_state, dropped_video_chunks, ${granted ? sql`worker_id, ` : sql``}indexed_at)
    values (${row.sessionId}, ${row.deviceId}, ${row.deviceModel}, ${row.platform}, ${row.osVersion},
       ${row.lapKind}, ${row.routeId}, ${row.locationCellId}, ${ts(row.startedAtMs)}, ${ts(row.completedAtMs)},
       ${row.photoCount}, ${row.chunkCount}, ${row.observedItemCount}, ${row.scannedCellCount},
       ${row.hasObjectCapture}, ${row.objectCaptureR2Key}, ${row.manifestR2Key}, ${row.totalBytes},
       ${row.geoRefEventCount}, ${row.eventLogR2Key},
       ${m?.eventCount ?? null}, ${m?.gateFlipCount ?? null}, ${m?.tapCount ?? null}, ${m?.confirmCount ?? null},
       ${m?.rejectCount ?? null}, ${m?.raycastMissCount ?? null}, ${m?.detectionCount ?? null},
       ${m?.uploadAttemptCount ?? null}, ${m?.uploadFailureCount ?? null}, ${m?.errorCount ?? null},
       ${row.videoCoveragePct}, ${row.maxThermalState}, ${row.droppedVideoChunks}, ${granted ? sql`${row.workerId}::int, ` : sql``}now())
    on conflict (session_id) do update set
       device_id = excluded.device_id,
       device_model = excluded.device_model,
       platform = excluded.platform,
       os_version = excluded.os_version,
       lap_kind = excluded.lap_kind,
       route_id = excluded.route_id,
       location_cell_id = excluded.location_cell_id,
       started_at = excluded.started_at,
       completed_at = excluded.completed_at,
       photo_count = excluded.photo_count,
       chunk_count = excluded.chunk_count,
       observed_item_count = excluded.observed_item_count,
       scanned_cell_count = excluded.scanned_cell_count,
       has_object_capture = excluded.has_object_capture,
       object_capture_r2_key = excluded.object_capture_r2_key,
       manifest_r2_key = excluded.manifest_r2_key,
       total_bytes = excluded.total_bytes,
       geo_ref_event_count = excluded.geo_ref_event_count,
       event_log_r2_key = coalesce(excluded.event_log_r2_key, scan_sessions.event_log_r2_key),
       event_count = coalesce(excluded.event_count, scan_sessions.event_count),
       gate_flip_count = coalesce(excluded.gate_flip_count, scan_sessions.gate_flip_count),
       tap_count = coalesce(excluded.tap_count, scan_sessions.tap_count),
       confirm_count = coalesce(excluded.confirm_count, scan_sessions.confirm_count),
       reject_count = coalesce(excluded.reject_count, scan_sessions.reject_count),
       raycast_miss_count = coalesce(excluded.raycast_miss_count, scan_sessions.raycast_miss_count),
       detection_count = coalesce(excluded.detection_count, scan_sessions.detection_count),
       upload_attempt_count = coalesce(excluded.upload_attempt_count, scan_sessions.upload_attempt_count),
       upload_failure_count = coalesce(excluded.upload_failure_count, scan_sessions.upload_failure_count),
       error_count = coalesce(excluded.error_count, scan_sessions.error_count),
       video_coverage_pct = coalesce(excluded.video_coverage_pct, scan_sessions.video_coverage_pct),
       max_thermal_state = coalesce(excluded.max_thermal_state, scan_sessions.max_thermal_state),
       dropped_video_chunks = coalesce(excluded.dropped_video_chunks, scan_sessions.dropped_video_chunks),
       ${granted ? sql`worker_id = excluded.worker_id, ` : sql``}indexed_at = now()
    where scan_sessions.device_id = excluded.device_id
      ${granted
        ? claimUnowned
          ? sql`and (scan_sessions.worker_id = excluded.worker_id
                     or (scan_sessions.worker_id is null and scan_sessions.manifest_r2_key = excluded.manifest_r2_key))`
          : sql`and scan_sessions.worker_id = excluded.worker_id`
        : grantsLive
          ? sql`and (scan_sessions.worker_id is null or scan_sessions.manifest_r2_key = excluded.manifest_r2_key)`
          : sql``}
    returning session_id
  `
}

/**
 * Rows must already be unique on r2_key (see dedupeChunks). A key already
 * indexed for a different session is never moved to this one; RETURNING lists
 * the keys actually written.
 */
export function chunkUpsert(rows: ChunkRow[]): SQL {
  const values = rows.map((r) => sql`(${r.sessionId}, ${r.locationCellId}, ${r.artifactType}, ${r.r2Key},
    ${r.format}, ${r.byteSize}::bigint, ${ts(r.capturedAtMs)}::timestamptz, now())`)
  return sql`
    insert into capture_chunks
      (session_id, location_cell_id, artifact_type, r2_key, format, byte_size, captured_at, indexed_at)
    values ${sql.join(values, sql`, `)}
    on conflict (r2_key) do update set
       session_id = excluded.session_id,
       location_cell_id = excluded.location_cell_id,
       artifact_type = excluded.artifact_type,
       format = excluded.format,
       byte_size = excluded.byte_size,
       captured_at = excluded.captured_at,
       indexed_at = now()
    where capture_chunks.session_id = excluded.session_id
    returning r2_key
  `
}

export function reconAssetInsert(rows: ReconAssetRow[]): SQL {
  const values = rows.map((r) => sql`(${r.locationCellId}, ${r.assetType}, ${r.format}, ${r.r2Key}, ${r.version}::int)`)
  return sql`
    with ins as (
      insert into reconstruction_assets
        (location_cell_id, asset_type, format, r2_key, version, source_scan_coverage_event_ids, created_at)
      select v.cell, v.asset_type, v.format, v.r2_key, v.version, '[]'::jsonb, now()
      from (values ${sql.join(values, sql`, `)}) as v(cell, asset_type, format, r2_key, version)
      where not exists (select 1 from reconstruction_assets a where a.r2_key = v.r2_key)
      returning 1
    )
    select count(*)::int as n from ins
  `
}

/**
 * Tables this PR adds (scan_manifest_rejects: 0052, capture_upload_grants:
 * 0054) may reach Neon after the code does. Until then the callers behave as
 * before — no reject memory, no grants — instead of failing every run.
 */
export async function withoutTable<T>(table: string, migration: string, fn: () => Promise<T>, fallback: T): Promise<T> {
  try {
    return await fn()
  } catch (e) {
    const err = e as { code?: string; message?: string }
    if (err.code === '42P01' || (err.message ?? '').includes(`"${table}" does not exist`)) {
      console.warn(`[scan-index] ${table} is missing — apply migration ${migration}`)
      return fallback
    }
    throw e
  }
}

const withoutRejectsTable = <T>(fn: () => Promise<T>, fallback: T) =>
  withoutTable('scan_manifest_rejects', '0052', fn, fallback)

/** The DB half of ScanIndexDeps. */
export function scanIndexDb(exec: SqlExec): Pick<
  ScanIndexDeps,
  | 'loadChunks' | 'loadSessions' | 'upsertSession' | 'upsertChunks' | 'existingReconKeys' | 'insertReconAssets'
  | 'enqueueReconstructionJobs' | 'loadRejects' | 'recordRejects' | 'clearRejects'
> {
  return {
    loadRejects: () => withoutRejectsTable(async () => {
      const rows = await exec(sql`
        select r2_key, size::float8 as size, (extract(epoch from last_modified) * 1000)::float8 as last_modified_ms
        from scan_manifest_rejects
      `)
      return new Map<string, ObjectInfo>(rows.map((r) => [String(r.r2_key), { size: Number(r.size), lastModifiedMs: Number(r.last_modified_ms) }]))
    }, new Map<string, ObjectInfo>()),

    // A re-upload that still fails (new size/mtime/reason) alerts again.
    recordRejects: (rejects) => withoutRejectsTable(async () => {
      const values = rejects.map((r) => sql`(${r.r2Key}, ${r.reason}, ${r.size}::bigint, ${ts(r.lastModifiedMs)}::timestamptz)`)
      await exec(sql`
        insert into scan_manifest_rejects (r2_key, reason, size, last_modified)
        values ${sql.join(values, sql`, `)}
        on conflict (r2_key) do update set
          reason = excluded.reason, size = excluded.size, last_modified = excluded.last_modified,
          alerted_at = case
            when (scan_manifest_rejects.size, scan_manifest_rejects.last_modified, scan_manifest_rejects.reason)
                 is distinct from (excluded.size, excluded.last_modified, excluded.reason)
            then null else scan_manifest_rejects.alerted_at end
      `)
    }, undefined),

    clearRejects: (keys) => withoutRejectsTable(async () => {
      await exec(sql`delete from scan_manifest_rejects where r2_key in ${keys}`)
    }, undefined),

    async loadChunks() {
      const rows = await exec(sql`
        select r2_key, session_id, byte_size::float8 as byte_size,
               (extract(epoch from indexed_at) * 1000)::float8 as indexed_at_ms
        from capture_chunks
      `)
      const out = new Map<string, IndexedChunk>()
      for (const r of rows) {
        out.set(String(r.r2_key), {
          sessionId: String(r.session_id),
          byteSize: num(r.byte_size),
          indexedAtMs: Number(r.indexed_at_ms),
        })
      }
      return out
    },

    async loadSessions() {
      const rows = await exec(sql`
        select manifest_r2_key, session_id, event_log_r2_key, (event_count is not null) as has_metrics
        from scan_sessions
      `)
      const out = new Map<string, IndexedSession>()
      for (const r of rows) {
        out.set(String(r.manifest_r2_key), {
          sessionId: String(r.session_id),
          eventLogR2Key: r.event_log_r2_key == null ? null : String(r.event_log_r2_key),
          hasEventMetrics: r.has_metrics === true || r.has_metrics === 't',
        })
      }
      return out
    },

    async upsertSession(row, metrics, grantsLive, claimUnowned) {
      return (await exec(sessionUpsert(row, metrics, grantsLive, claimUnowned))).length > 0
    },

    async upsertChunks(rows) {
      const leftAlone: string[] = []
      for (const part of batches(rows, 500)) {
        const written = new Set((await exec(chunkUpsert(part))).map((r) => String(r.r2_key)))
        for (const r of part) if (!written.has(r.r2Key)) leftAlone.push(r.r2Key)
      }
      return leftAlone
    },

    async existingReconKeys(keys) {
      const found = new Set<string>()
      for (const part of batches(keys, 1000)) {
        const rows = await exec(sql`select r2_key from reconstruction_assets where r2_key in ${part}`)
        for (const r of rows) found.add(String(r.r2_key))
      }
      return found
    },

    async insertReconAssets(rows) {
      let n = 0
      for (const part of batches(rows, 500)) n += Number((await exec(reconAssetInsert(part)))[0]?.n ?? 0)
      return n
    },

    async enqueueReconstructionJobs() {
      return Number((await exec(ENQUEUE_RECONSTRUCTION_JOBS))[0]?.n ?? 0)
    },
  }
}

/** The DB half of WatchdogDeps. */
export function watchdogDb(exec: SqlExec): Pick<
  WatchdogDeps,
  'indexedManifestKeys' | 'civicWatermarkMs' | 'stuckReconJobs' | 'manifestRejects' | 'markRejectsAlerted'
> {
  return {
    manifestRejects: () => withoutRejectsTable(async () => {
      const rows = await exec(sql`select r2_key, reason, alerted_at is not null as alerted from scan_manifest_rejects order by r2_key`)
      return rows.map((r) => ({ r2Key: String(r.r2_key), reason: String(r.reason), alerted: r.alerted === true || r.alerted === 't' }))
    }, []),

    markRejectsAlerted: (keys) => withoutRejectsTable(async () => {
      await exec(sql`update scan_manifest_rejects set alerted_at = now() where r2_key in ${keys} and alerted_at is null`)
    }, undefined),

    async indexedManifestKeys() {
      const rows = await exec(sql`select r2_key from capture_chunks where artifact_type = 'manifest'`)
      return new Set(rows.map((r) => String(r.r2_key)))
    },

    // civic_reports stores the portal's Chicago wall-clock times as if UTC
    // (civic-sync.ts); read them back as Chicago time to get the true instant.
    async civicWatermarkMs() {
      const rows = await exec(sql`
        select (extract(epoch from
                  (max(last_modified_date) at time zone 'UTC') at time zone 'America/Chicago'
                ) * 1000)::float8 as ms
        from civic_reports
      `)
      return rows[0]?.ms == null ? null : Number(rows[0].ms)
    },

    // Claimable exactly as recon_worker.py leases: queued, or leased with an
    // expired lease. updated_at moves on every lease, retry and completion.
    async stuckReconJobs(beforeMs) {
      const rows = await exec(sql`
        select count(*)::int as n, (extract(epoch from min(updated_at)) * 1000)::float8 as oldest_ms
        from reconstruction_jobs
        where (status = 'queued' or (status = 'leased' and leased_until < now()))
          and updated_at < ${new Date(beforeMs).toISOString()}::timestamptz
      `)
      const r = rows[0]
      return { count: Number(r?.n ?? 0), oldestMs: r?.oldest_ms == null ? null : Number(r.oldest_ms) }
    },
  }
}
