import { test } from 'node:test'
import assert from 'node:assert/strict'
import { PgDialect } from 'drizzle-orm/pg-core'
import type { SQL } from 'drizzle-orm'
import { scanIndexDb, watchdogDb, type SqlExec } from './db'
import { civicSyncDb } from './civic-db'
import type { CivicRow } from './civic-sync'

/** Records each statement's SQL text and params; answers from `respond`. */
function recordingExec(respond: (sql: string) => Record<string, unknown>[] = () => []) {
  const dialect = new PgDialect()
  const queries: { sql: string; params: unknown[] }[] = []
  const exec: SqlExec = async (q: SQL) => {
    const { sql, params } = dialect.sqlToQuery(q)
    const norm = sql.replace(/\s+/g, ' ').trim()
    queries.push({ sql: norm, params })
    return respond(norm)
  }
  return { exec, queries }
}

test('enqueue rule: completed, unreconstructed, celled sessions with jsonl + bin + mov chunks, once per session', async () => {
  const { exec, queries } = recordingExec(() => [{ n: 3 }])
  assert.equal(await scanIndexDb(exec).enqueueReconstructionJobs(), 3)
  const q = queries[0].sql
  assert.match(q, /insert into reconstruction_jobs \(session_id, location_cell_id\) select s\.session_id, s\.location_cell_id from scan_sessions s/)
  for (const cond of [
    's.reconstruction_version is null',
    's.completed_at is not null',
    's.location_cell_id is not null',
    "c.format = 'jsonl'",
    "c.format = 'bin'",
    "c.format = 'mov'",
    'on conflict (session_id) do nothing',
  ]) assert.ok(q.includes(cond), cond)
  // The '0:0' sentinel is a real cell id: it is NOT excluded from the queue.
  assert.ok(!q.includes("'0:0'"))
})

test('session upsert keeps earlier event metrics, and only updates for the same device', async () => {
  const { exec, queries } = recordingExec()
  const written = await scanIndexDb(exec).upsertSession({
    sessionId: 's', deviceId: 'd', deviceModel: null, platform: null, osVersion: null, lapKind: null, routeId: null,
    locationCellId: '0:0', startedAtMs: 0, completedAtMs: 1000, photoCount: 0, chunkCount: 0, observedItemCount: 0,
    scannedCellCount: 0, hasObjectCapture: false, objectCaptureR2Key: null, manifestR2Key: 'raw/x-manifest.json',
    totalBytes: null, geoRefEventCount: 0, eventLogR2Key: null, videoCoveragePct: null, maxThermalState: null,
    droppedVideoChunks: null, workerId: null,
  }, null, false, false)
  const { sql, params } = queries[0]
  assert.equal(written, false, 'no RETURNING row: the conflict guard refused it')
  assert.match(sql, /on conflict \(session_id\) do update set/)
  assert.match(sql, /where scan_sessions\.device_id = excluded\.device_id returning session_id/)
  assert.match(sql, /event_count = coalesce\(excluded\.event_count, scan_sessions\.event_count\)/)
  assert.match(sql, /device_id = excluded\.device_id/)
  assert.equal(params.length, 33)
  assert.equal(params[8], '1970-01-01T00:00:00.000Z')
  assert.equal(params[9], '1970-01-01T00:00:01.000Z')
})

test('chunk upsert is keyed on r2_key, batched, and never moves a key between sessions', async () => {
  const { exec, queries } = recordingExec((sql) => (sql.startsWith('insert into capture_chunks') ? [{ r2_key: 'k0' }] : []))
  const rows = Array.from({ length: 501 }, (_, i) => ({
    sessionId: 's', locationCellId: '1:2', artifactType: 'photo', r2Key: `k${i}`, format: 'jpg', byteSize: i, capturedAtMs: null,
  }))
  const leftAlone = await scanIndexDb(exec).upsertChunks(rows)
  assert.equal(queries.length, 2)
  assert.match(queries[0].sql, /on conflict \(r2_key\) do update set/)
  assert.match(queries[0].sql, /where capture_chunks\.session_id = excluded\.session_id returning r2_key/)
  assert.equal(leftAlone.length, 500, 'k0 was written in each batch; every other key reported')
})

test('reconstruction assets insert only absent keys with an empty provenance array', async () => {
  const { exec, queries } = recordingExec((sql) => (sql.startsWith('select r2_key') ? [{ r2_key: 'a' }] : [{ n: 1 }]))
  const db = scanIndexDb(exec)
  assert.deepEqual(await db.existingReconKeys(['a', 'b']), new Set(['a']))
  assert.equal(await db.insertReconAssets([{ locationCellId: '1:2', assetType: 'splat', format: 'ply', r2Key: 'b', version: 1 }]), 1)
  assert.match(queries[1].sql, /'\[\]'::jsonb/)
  assert.match(queries[1].sql, /where not exists \(select 1 from reconstruction_assets a where a\.r2_key = v\.r2_key\)/)
})

test('loadChunks reads sizes and index times as numbers', async () => {
  const { exec } = recordingExec(() => [{ r2_key: 'k', session_id: 's', byte_size: '12', indexed_at_ms: 5000 }])
  const chunks = await scanIndexDb(exec).loadChunks()
  assert.deepEqual(chunks.get('k'), { sessionId: 's', byteSize: 12, indexedAtMs: 5000 })
})

test('civic watermark is MAX(last_modified_date); upsert keyed on sr_number', async () => {
  const { exec, queries } = recordingExec((sql) => (sql.includes('max(last_modified_date)') ? [{ ms: 1234 }] : [{ n: 1 }]))
  const db = civicSyncDb(exec)
  assert.equal(await db.currentWatermarkMs(), 1234)
  const row: CivicRow = {
    srNumber: 'SR1', srType: 'Graffiti Removal Request', status: 'Open', ownerDepartment: null, createdDateMs: 0,
    lastModifiedDateMs: 0, closedDateMs: null, streetAddress: null, communityArea: 25, lat: 41.8, lng: -87.7,
    locationCellId: '1:2',
  }
  assert.equal(await db.upsert([row]), 1)
  assert.match(queries[1].sql, /on conflict \(sr_number\) do update set/)
  assert.match(queries[1].sql, /synced_at = now\(\)/)
  assert.equal(await db.upsert([]), 0)
  assert.equal(queries.length, 2, 'an empty page writes nothing')
})

test('stuck recon jobs are the ones recon_worker could claim', async () => {
  const { exec, queries } = recordingExec(() => [{ n: 2, oldest_ms: 10 }])
  assert.deepEqual(await watchdogDb(exec).stuckReconJobs(0), { count: 2, oldestMs: 10 })
  assert.match(queries[0].sql, /status = 'queued' or \(status = 'leased' and leased_until < now\(\)\)/)
})

test('a granted session stamps worker_id and cannot be taken over by another worker', async () => {
  const { exec, queries } = recordingExec(() => [{ session_id: 's' }])
  const row = {
    sessionId: 's', deviceId: 'd', deviceModel: null, platform: null, osVersion: null, lapKind: null, routeId: null,
    locationCellId: '1:2', startedAtMs: 0, completedAtMs: 1, photoCount: 0, chunkCount: 0, observedItemCount: 0,
    scannedCellCount: 0, hasObjectCapture: false, objectCaptureR2Key: null, manifestR2Key: 'k', totalBytes: null,
    geoRefEventCount: 0, eventLogR2Key: null, videoCoveragePct: null, maxThermalState: null, droppedVideoChunks: null,
  }
  assert.equal(await scanIndexDb(exec).upsertSession({ ...row, workerId: 7 }, null, true, false), true)
  assert.match(queries[0].sql, /worker_id = excluded\.worker_id/)
  assert.match(queries[0].sql, /and scan_sessions\.worker_id = excluded\.worker_id returning/, 'no stamping of an unowned row by default')
  await scanIndexDb(exec).upsertSession({ ...row, workerId: 7 }, null, true, true)
  assert.match(queries.at(-1)!.sql, /or \(scan_sessions\.worker_id is null and scan_sessions\.manifest_r2_key = excluded\.manifest_r2_key\)/,
    'only with proof the manifest was written after its grant')
  await scanIndexDb(exec).upsertSession({ ...row, workerId: null }, null, false, false)
  assert.ok(!queries.at(-1)!.sql.includes('worker_id'), 'before migration 0054 a legacy manifest never references worker_id')
  await scanIndexDb(exec).upsertSession({ ...row, workerId: null }, null, true, false)
  assert.match(queries.at(-1)!.sql, /and \(scan_sessions\.worker_id is null or scan_sessions\.manifest_r2_key = excluded\.manifest_r2_key\)/,
    "once grants are live a legacy manifest can't touch a worker's row from another object")
})

test('a re-upload that still fails re-alerts: alerted_at resets when size/mtime/reason change', async () => {
  const { exec, queries } = recordingExec()
  await scanIndexDb(exec).recordRejects([{ r2Key: 'k', reason: 'r', size: 1, lastModifiedMs: 0 }])
  assert.match(queries[0].sql, /alerted_at = case when \(scan_manifest_rejects\.size, scan_manifest_rejects\.last_modified, scan_manifest_rejects\.reason\) is distinct from \(excluded\.size, excluded\.last_modified, excluded\.reason\) then null else scan_manifest_rejects\.alerted_at end/)
})

test('the 311 watermark is read back as Chicago wall time for the watchdog', async () => {
  const { exec, queries } = recordingExec(() => [{ ms: 1 }])
  await watchdogDb(exec).civicWatermarkMs()
  assert.match(queries[0].sql, /\(max\(last_modified_date\) at time zone 'UTC'\) at time zone 'America\/Chicago'/)
})
