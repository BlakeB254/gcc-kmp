import { test } from 'node:test'
import assert from 'node:assert/strict'
import { gzipSync } from 'node:zlib'
import { ObjectTooLarge, runScanIndex, type IndexedChunk, type IndexedSession, type ManifestReject, type ObjectInfo, type ScanIndexDeps } from './indexer'
import { grantCoversObject, grantGate, manifestOwner, writtenAfterGrant, type UploadGrant } from './grants'
import type { ChunkRow, SessionRow } from './manifest'
import { MAX_EVENT_LOG_BYTES, type EventLogMetrics } from './event-log-metrics'

const T0 = Date.UTC(2026, 9, 1)

function manifest(sessionId: string, over: Record<string, unknown> = {}) {
  return JSON.stringify({
    sessionId,
    deviceId: 'dev1',
    deviceModel: 'iPhone16,2',
    osVersion: '19.0',
    startedAtMs: T0,
    completedAtMs: T0 + 60_000,
    path: [],
    scannedCells: [],
    scannedCellSizeMeters: 10,
    observedItems: [],
    photoKeys: [],
    ...over,
  })
}

/** In-memory R2 + tables, with the same idempotency keys as Postgres. */
function world() {
  const r2 = new Map<string, { body: string | Uint8Array; info: ObjectInfo }>()
  const sessions = new Map<string, { row: SessionRow; metrics: Partial<EventLogMetrics> | null; indexedAtMs: number }>()
  const chunks = new Map<string, ChunkRow & { indexedAtMs: number }>()
  const recon = new Set<string>()
  const rejects = new Map<string, ManifestReject>()
  const grants = new Map<string, UploadGrant>()
  let clock = T0 + 3_600_000
  let gets = 0
  let enqueues = 0
  const put = (key: string, body: string | Uint8Array, at = clock) =>
    r2.set(key, { body, info: { size: typeof body === 'string' ? body.length : body.byteLength, lastModifiedMs: at } })
  const deps: ScanIndexDeps = {
    async listAll(prefix) {
      return new Map([...r2].filter(([k]) => k.startsWith(prefix)).sort(([a], [b]) => (a < b ? -1 : 1)).map(([k, v]) => [k, v.info]))
    },
    // Like the ranged GET: the cap is checked on what is fetched, not on the listing.
    async getBytes(key, maxBytes) {
      gets++
      const o = r2.get(key)
      if (!o) throw new Error('NoSuchKey')
      const bytes = typeof o.body === 'string' ? new TextEncoder().encode(o.body) : o.body
      if (bytes.byteLength > maxBytes) throw new ObjectTooLarge(`${key} too large`)
      return bytes
    },
    async grantCutoverMs() {
      const first = Math.min(...[...grants.values()].map((g) => g.issuedAtMs))
      return Number.isFinite(first) ? first + 2 * 3_600_000 : null
    },
    async grantsFor(keys) {
      return new Map(keys.filter((k) => grants.has(k)).map((k) => [k, grants.get(k)!]))
    },
    async loadChunks() {
      return new Map<string, IndexedChunk>([...chunks].map(([k, c]) => [k, { sessionId: c.sessionId, byteSize: c.byteSize, indexedAtMs: c.indexedAtMs }]))
    },
    async loadSessions() {
      return new Map<string, IndexedSession>([...sessions.values()].map((s) => [s.row.manifestR2Key, {
        sessionId: s.row.sessionId, eventLogR2Key: s.row.eventLogR2Key, hasEventMetrics: s.metrics != null,
      }]))
    },
    // Mirrors the guards in db.ts sessionUpsert.
    async upsertSession(row, metrics, grantsLive, claimUnowned) {
      const prev = sessions.get(row.sessionId)
      if (prev) {
        const sameObject = prev.row.manifestR2Key === row.manifestR2Key
        if (prev.row.deviceId !== row.deviceId) return false
        if (row.workerId != null) {
          if (!(prev.row.workerId === row.workerId || (claimUnowned && prev.row.workerId == null && sameObject))) return false
        } else {
          if (grantsLive && !(prev.row.workerId == null || sameObject)) return false
          row = { ...row, workerId: prev.row.workerId }
        }
      }
      sessions.set(row.sessionId, { row, metrics: metrics ?? prev?.metrics ?? null, indexedAtMs: clock })
      return true
    },
    async upsertChunks(rows) {
      const leftAlone: string[] = []
      for (const r of rows) {
        const prev = chunks.get(r.r2Key)
        if (prev && prev.sessionId !== r.sessionId) leftAlone.push(r.r2Key)
        else chunks.set(r.r2Key, { ...r, indexedAtMs: clock })
      }
      return leftAlone
    },
    async existingReconKeys(keys) {
      return new Set(keys.filter((k) => recon.has(k)))
    },
    async insertReconAssets(rows) {
      for (const r of rows) recon.add(r.r2Key)
      return rows.length
    },
    async enqueueReconstructionJobs() {
      enqueues++
      return 0
    },
    async loadRejects() {
      return new Map([...rejects].map(([k, r]) => [k, { size: r.size, lastModifiedMs: r.lastModifiedMs }]))
    },
    async recordRejects(rows) {
      for (const r of rows) rejects.set(r.r2Key, r)
    },
    async clearRejects(keys) {
      for (const k of keys) rejects.delete(k)
    },
  }
  return {
    r2, sessions, chunks, recon, rejects, grants, deps, put,
    grant: (key: string, workerId: number, deviceId: string | null, sessionId: string | null, at = clock, lastAt = at) =>
      grants.set(key, { workerId, deviceId, sessionId, issuedAtMs: at, lastIssuedAtMs: lastAt }),
    tick: (ms = 60_000) => { clock += ms },
    get clock() { return clock },
    get gets() { return gets },
    get enqueues() { return enqueues },
  }
}

const run = (w: ReturnType<typeof world>, opts: Partial<Parameters<typeof runScanIndex>[1]> = {}) =>
  runScanIndex(w.deps, { deadlineMs: Number.POSITIVE_INFINITY, now: () => w.clock, ...opts })

test('indexes new sessions, then leaves unchanged ones alone', async () => {
  const w = world()
  w.put('raw/1:2/dev1/a-manifest.json', manifest('a', { captureChunks: ['raw/1:2/a/capture/c.jsonl'] }))
  w.put('raw/1:2/a/capture/c.jsonl', 'x')
  w.put('raw/0:0/dev1/b-manifest.json', manifest('b'))
  w.tick()
  const first = await run(w)
  assert.equal(first.sessionsIndexed, 2)
  assert.equal(first.chunksIndexed, 3)
  assert.equal(first.pending, 0)
  assert.equal(w.sessions.get('b')!.row.locationCellId, '0:0')
  assert.equal(w.enqueues, 1)

  w.tick()
  const gets = w.gets
  const second = await run(w)
  assert.equal(second.sessionsIndexed, 0, 'nothing changed, nothing re-read')
  assert.equal(w.gets, gets)
  assert.equal(w.enqueues, 2, 'the enqueue pass runs every time')
})

test('a late capture object re-indexes its session', async () => {
  const w = world()
  w.put('raw/1:2/dev1/a-manifest.json', manifest('a', { captureChunks: ['raw/1:2/a/capture/c.jsonl'] }))
  w.put('raw/1:2/a/capture/c.jsonl', 'x')
  w.tick()
  await run(w)
  w.tick()
  w.put('raw/1:2/a/capture/c.mov', 'video') // finalized after the manifest froze
  w.tick()
  const r = await run(w)
  assert.equal(r.sessionsIndexed, 1)
  assert.equal(w.chunks.get('raw/1:2/a/capture/c.mov')!.artifactType, 'video')
})

test('a rewritten manifest re-indexes; a vanished chunk does not loop', async () => {
  const w = world()
  w.put('raw/1:2/dev1/a-manifest.json', manifest('a', { photoKeys: ['raw/1:2/dev1/p.jpg'] }))
  w.put('raw/1:2/dev1/p.jpg', 'jpeg')
  w.tick()
  await run(w)
  w.r2.delete('raw/1:2/dev1/p.jpg')
  w.tick()
  assert.equal((await run(w)).sessionsIndexed, 0)
  w.put('raw/1:2/dev1/a-manifest.json', manifest('a', { photoKeys: [] }))
  w.tick()
  assert.equal((await run(w)).sessionsIndexed, 1)
  assert.equal((await run(w)).sessionsIndexed, 0)
})

test('an event log that lands after the manifest fills the metrics on a later run', async () => {
  const w = world()
  w.put('raw/1:2/dev1/a-manifest.json', manifest('a', { eventLogKey: 'raw/1:2/a/events.jsonl.gz' }))
  w.tick()
  await run(w)
  assert.equal(w.sessions.get('a')!.metrics, null, 'log not uploaded yet: metrics stay null')
  w.put('raw/1:2/a/events.jsonl.gz', gzipSync('{"t":0,"type":"tap"}\n'))
  w.tick()
  const r = await run(w)
  assert.equal(r.sessionsIndexed, 1)
  assert.equal(w.sessions.get('a')!.metrics!.tapCount, 1)
})

test('a bad manifest is counted as failed and does not stop the sweep', async () => {
  const w = world()
  w.put('raw/1:2/dev1/a-manifest.json', '{"sessionId":"a"}')
  w.put('raw/1:2/dev1/b-manifest.json', manifest('b'))
  w.tick()
  const r = await run(w)
  assert.equal(r.manifestsFailed, 1)
  assert.deepEqual(r.failedKeys, ['raw/1:2/dev1/a-manifest.json'])
  assert.equal(r.sessionsIndexed, 1)
  assert.equal(w.enqueues, 1)
})

test('the budget bounds a run and the backlog resumes next run', async () => {
  const w = world()
  for (const id of ['a', 'b', 'c']) w.put(`raw/1:2/dev1/${id}-manifest.json`, manifest(id))
  w.tick()
  const first = await run(w, { maxManifests: 2 })
  assert.equal(first.sessionsIndexed, 2)
  assert.equal(first.pending, 1)
  const second = await run(w, { maxManifests: 2 })
  assert.equal(second.sessionsIndexed, 1)
  assert.equal(second.pending, 0)

  const late = world()
  late.put('raw/1:2/dev1/a-manifest.json', manifest('a'))
  const r = await runScanIndex(late.deps, { deadlineMs: 0, now: () => late.clock })
  assert.equal(r.sessionsIndexed, 0)
  assert.equal(r.pending, 1)
  assert.equal(late.enqueues, 1, 'enqueue still runs when the budget is spent')
})

test('full=1 re-reads every manifest', async () => {
  const w = world()
  w.put('raw/1:2/dev1/a-manifest.json', manifest('a'))
  w.tick()
  await run(w)
  assert.equal((await run(w, { full: true })).sessionsIndexed, 1)
})

test('reconstructed assets: inserted once, candidates and oversized names skipped', async () => {
  const w = world()
  w.put('reconstructed/1:2/1/splat.ply', 'x')
  w.put('reconstructed/1:2/1/verified-candidate/verified.ply', 'x')
  w.put('reconstructed/1:2/1/a_very_long_asset_type_name.ply', 'x')
  w.put('reconstructed/1:2/1/', '')
  const r = await run(w)
  assert.equal(r.reconstructionsIndexed, 1)
  assert.equal(r.reconstructionsSkipped, 1)
  assert.equal((await run(w)).reconstructionsIndexed, 0)
})

test('a manifest can only speak for its own folder', async () => {
  const w = world()
  w.put('raw/1:2/dev1/a-manifest.json', manifest('a', {
    photoKeys: ['raw/1:2/dev1/p.jpg', 'raw/1:2/dev2/victim.jpg', 'raw/1:2/dev1/../dev2/x.jpg', 'elsewhere/p.jpg'],
    captureChunks: ['raw/1:2/a/capture/c.jsonl', 'raw/1:2/b/capture/c.mov'],
    eventLogKey: 'raw/1:2/b/events.jsonl.gz',
  }))
  w.put('raw/1:2/dev1/wrong-manifest.json', manifest('a')) // filename names another session
  w.put('raw/1:2/devX/a2-manifest.json', manifest('a2')) // deviceId dev1 != folder devX
  w.put('raw/1:2/b/events.jsonl.gz', gzipSync('{"t":0,"type":"tap"}\n'))
  w.tick()
  const r = await run(w)
  assert.equal(r.sessionsIndexed, 1)
  assert.equal(r.manifestsFailed, 2)
  assert.equal(r.foreignKeysDropped, 5)
  assert.deepEqual([...w.chunks.keys()].sort(), ['raw/1:2/a/capture/c.jsonl', 'raw/1:2/dev1/a-manifest.json', 'raw/1:2/dev1/p.jpg'])
  const s = w.sessions.get('a')!
  assert.equal(s.row.eventLogR2Key, null, "another session's event log is never read")
  assert.equal(s.metrics, null)
  assert.equal(s.row.photoCount, 1)
  assert.equal(s.row.chunkCount, 1)
})

test('a session indexed for one device is not taken over by another', async () => {
  const w = world()
  w.put('raw/1:2/dev1/a-manifest.json', manifest('a'))
  w.tick()
  await run(w)
  w.put('raw/3:4/devEvil/a-manifest.json', manifest('a', { deviceId: 'devEvil' }))
  w.tick()
  const r = await run(w)
  assert.equal(r.manifestsFailed, 1)
  assert.equal(w.sessions.get('a')!.row.deviceId, 'dev1')
  assert.equal(w.sessions.get('a')!.row.manifestR2Key, 'raw/1:2/dev1/a-manifest.json')
  assert.ok(!w.chunks.has('raw/3:4/devEvil/a-manifest.json'), 'no chunk rows for a rejected manifest')
})

test('a chunk owned by one session is never moved to another', async () => {
  const w = world()
  w.put('raw/1:2/dev1/a-manifest.json', manifest('a', { photoKeys: ['raw/1:2/dev1/shared.jpg'] }))
  w.put('raw/1:2/dev1/shared.jpg', 'jpeg')
  w.tick()
  await run(w)
  w.put('raw/1:2/dev1/b-manifest.json', manifest('b', { photoKeys: ['raw/1:2/dev1/shared.jpg'] }))
  w.tick()
  const r = await run(w)
  assert.equal(r.sessionsIndexed, 1)
  assert.equal(r.chunksOwnedElsewhere, 1)
  assert.equal(w.chunks.get('raw/1:2/dev1/shared.jpg')!.sessionId, 'a')
})

test('an unreadable event log is recorded once and never refetched', async () => {
  for (const body of [
    gzipSync(Buffer.alloc(65 * 1024 * 1024)), // decompression bomb: ~64KB in, 65MiB out
    new TextEncoder().encode('not gzip at all'),
  ]) {
    const w = world()
    w.put('raw/1:2/dev1/a-manifest.json', manifest('a', { eventLogKey: 'raw/1:2/a/events.jsonl.gz' }))
    w.put('raw/1:2/a/events.jsonl.gz', body)
    w.tick()
    await run(w)
    assert.deepEqual(w.sessions.get('a')!.metrics, { eventCount: 0 })
    w.tick()
    const gets = w.gets
    assert.equal((await run(w)).sessionsIndexed, 0)
    assert.equal(w.gets, gets, 'not fetched again')
  }
})

test('an oversized event log or manifest is never downloaded', async () => {
  const w = world()
  w.put('raw/1:2/dev1/a-manifest.json', manifest('a', { eventLogKey: 'raw/1:2/a/events.jsonl.gz' }))
  w.put('raw/1:2/a/events.jsonl.gz', new Uint8Array(MAX_EVENT_LOG_BYTES + 1))
  w.put('raw/1:2/dev1/b-manifest.json', ' '.repeat(4 * 1024 * 1024 + 1))
  w.tick()
  const r = await run(w)
  assert.equal(w.gets, 1, 'only the small manifest was read')
  assert.equal(r.manifestsFailed, 1)
  assert.deepEqual(w.sessions.get('a')!.metrics, { eventCount: 0 })
})

test('a permanently bad manifest is recorded, skipped while unchanged, retried when re-uploaded', async () => {
  const w = world()
  w.put('raw/1:2/dev1/a-manifest.json', '{"sessionId":"a"}') // no path: never decodes
  w.tick()
  const first = await run(w)
  assert.equal(first.manifestsRejected, 1)
  assert.match(w.rejects.get('raw/1:2/dev1/a-manifest.json')!.reason, /expected/)

  w.tick()
  const gets = w.gets
  const second = await run(w)
  assert.equal(second.manifestsFailed, 0, 'not re-read, not re-counted')
  assert.equal(second.manifestsKnownBad, 1)
  assert.equal(w.gets, gets)

  w.put('raw/1:2/dev1/a-manifest.json', manifest('a')) // fixed upload
  w.tick()
  const third = await run(w)
  assert.equal(third.sessionsIndexed, 1)
  assert.equal(w.rejects.size, 0, 'cleared once it indexes')
})

test('transient fetch errors are not recorded as rejects', async () => {
  const w = world()
  w.put('raw/1:2/dev1/a-manifest.json', manifest('a'))
  w.tick()
  const deps = { ...w.deps, getBytes: async () => { throw new Error('ECONNRESET') } }
  const r = await runScanIndex(deps, { deadlineMs: Number.POSITIVE_INFINITY, now: () => w.clock })
  assert.equal(r.manifestsFailed, 1)
  assert.equal(r.manifestsRejected, 0)
  assert.equal(w.rejects.size, 0)
})

test('an object that grew after it was listed is capped at fetch time', async () => {
  const w = world()
  w.put('raw/1:2/dev1/a-manifest.json', manifest('a', { eventLogKey: 'raw/1:2/a/events.jsonl.gz' }))
  w.put('raw/1:2/a/events.jsonl.gz', gzipSync('{"t":0,"type":"tap"}\n'))
  w.put('raw/1:2/dev1/b-manifest.json', manifest('b'))
  const deps: ScanIndexDeps = {
    ...w.deps,
    // The listing still shows the small objects; the bytes behind them are now huge.
    getBytes: async (key, max) => {
      if (key.endsWith('events.jsonl.gz') || key.includes('/b-')) throw new ObjectTooLarge(`${key} grew past ${max}`)
      return w.deps.getBytes(key, max)
    },
  }
  w.tick()
  const r = await runScanIndex(deps, { deadlineMs: Number.POSITIVE_INFINITY, now: () => w.clock })
  assert.equal(r.manifestsRejected, 1, 'the manifest that grew is rejected')
  assert.deepEqual(w.sessions.get('a')!.metrics, { eventCount: 0 }, 'the log that grew is unreadable, not retried')
})

// ---- upload grants (post-cutover manifests)

/** A world where grants exist and the cutover is in the past. */
function grantedWorld() {
  const w = world()
  w.grant('raw/0:0/seed/seed.jpg', 99, 'seed', null, T0 - 10 * 3_600_000) // first grant → cutover T0-8h
  return w
}

test('a post-cutover manifest without a grant is refused', async () => {
  const w = grantedWorld()
  w.put('raw/1:2/dev1/a-manifest.json', manifest('a'))
  w.tick()
  const r = await run(w)
  assert.equal(r.sessionsIndexed, 0)
  assert.equal(r.manifestsRejected, 1)
  assert.match(w.rejects.get('raw/1:2/dev1/a-manifest.json')!.reason, /without a valid upload grant/)
})

test("a granted manifest keeps only its own worker's keys and is stamped with that worker", async () => {
  const w = grantedWorld()
  w.put('raw/1:2/dev1/a-manifest.json', manifest('a', {
    photoKeys: ['raw/1:2/dev1/mine.jpg', 'raw/1:2/dev1/theirs.jpg', 'raw/1:2/dev1/ungranted.jpg'],
    captureChunks: ['raw/1:2/a/capture/c.jsonl', 'raw/1:2/a/capture/theirs.mov'],
  }))
  w.put('raw/1:2/a/capture/late.mov', 'v')
  w.put('raw/1:2/a/capture/late-theirs.mov', 'v')
  for (const k of ['dev1/mine.jpg', 'dev1/theirs.jpg', 'dev1/ungranted.jpg', 'a/capture/c.jsonl', 'a/capture/theirs.mov']) w.put(`raw/1:2/${k}`, 'x')
  w.grant('raw/1:2/dev1/a-manifest.json', 7, 'dev1', null)
  w.grant('raw/1:2/dev1/mine.jpg', 7, 'dev1', null)
  w.grant('raw/1:2/dev1/theirs.jpg', 8, 'dev1', null) // worker B on the same device folder
  w.grant('raw/1:2/a/capture/c.jsonl', 7, 'dev1', 'a')
  w.grant('raw/1:2/a/capture/theirs.mov', 8, 'dev1', 'a')
  w.grant('raw/1:2/a/capture/late.mov', 7, 'dev1', 'a')
  w.grant('raw/1:2/a/capture/late-theirs.mov', 8, 'dev1', 'a')
  w.tick()
  const r = await run(w)
  assert.equal(r.sessionsIndexed, 1)
  assert.equal(r.sessionsGranted, 1)
  assert.equal(r.foreignKeysDropped, 3, 'named keys; the ungranted late object is skipped too')
  assert.equal(w.sessions.get('a')!.row.workerId, 7)
  assert.deepEqual([...w.chunks.keys()].sort(), [
    'raw/1:2/a/capture/c.jsonl',
    'raw/1:2/a/capture/late.mov',
    'raw/1:2/dev1/a-manifest.json',
    'raw/1:2/dev1/mine.jpg',
  ])
})

test("a manifest granted to worker B cannot take over worker A's session", async () => {
  const w = grantedWorld()
  w.put('raw/1:2/dev1/a-manifest.json', manifest('a'))
  w.grant('raw/1:2/dev1/a-manifest.json', 7, 'dev1', null)
  w.tick()
  await run(w)
  w.put('raw/3:4/dev1/a-manifest.json', manifest('a'))
  w.grant('raw/3:4/dev1/a-manifest.json', 8, 'dev1', null)
  w.tick()
  const r = await run(w)
  assert.equal(r.manifestsRejected, 1)
  assert.equal(w.sessions.get('a')!.row.workerId, 7)
})

test('a grant for another device does not cover the manifest', async () => {
  const w = grantedWorld()
  w.put('raw/1:2/dev1/a-manifest.json', manifest('a'))
  w.grant('raw/1:2/dev1/a-manifest.json', 7, 'devOther', null)
  w.tick()
  assert.equal((await run(w)).manifestsRejected, 1)
})

test('legacy manifests (written before the cutover) keep the folder rules and no worker', async () => {
  const w = world()
  w.put('raw/1:2/dev1/old-manifest.json', manifest('old', { photoKeys: ['raw/1:2/dev1/p.jpg'] }), T0)
  w.grant('raw/9:9/x/y.jpg', 1, 'x', null, T0 + 3_600_000) // cutover T0+3h, after the manifest
  w.tick()
  const r = await run(w)
  assert.equal(r.sessionsIndexed, 1)
  assert.equal(r.sessionsGranted, 0)
  assert.equal(w.sessions.get('old')!.row.workerId, null)
  assert.ok(w.chunks.has('raw/1:2/dev1/p.jpg'))
})

test('grant helpers: owner needs a grant for the same device; gate needs same worker and device/session', () => {
  const m = { deviceId: 'dev1', sessionId: 'a' } as Parameters<typeof manifestOwner>[1]
  const g = (workerId: number, sessionId: string | null): UploadGrant =>
    ({ workerId, deviceId: 'dev1', sessionId, issuedAtMs: T0, lastIssuedAtMs: T0 })
  const grants = new Map<string, UploadGrant>([
    ['raw/1:2/dev1/a-manifest.json', g(7, null)],
    ['raw/1:2/dev1/p.jpg', g(7, null)],
    ['raw/1:2/a/c.mov', g(7, 'a')],
    ['raw/1:2/a/x.mov', g(7, 'other')],
  ])
  const raw = new Map([...grants.keys()].map((k) => [k, { size: 1, lastModifiedMs: T0 + 60_000 }]))
  assert.equal(manifestOwner('raw/1:2/dev1/a-manifest.json', m, grants, raw), 7)
  assert.equal(manifestOwner('raw/1:2/dev1/none-manifest.json', m, grants, raw), null)
  const gate = grantGate(m, 7, grants, raw)
  assert.equal(gate('raw/1:2/dev1/p.jpg', 'device'), true)
  assert.equal(gate('raw/1:2/a/c.mov', 'session'), true)
  assert.equal(gate('raw/1:2/a/x.mov', 'session'), false, 'granted for another session')
  assert.equal(grantGate(m, 8, grants, raw)('raw/1:2/dev1/p.jpg', 'device'), false, 'another worker')
})

test('a grant counts only for an object written inside its window', () => {
  const g: UploadGrant = { workerId: 7, deviceId: 'dev1', sessionId: null, issuedAtMs: T0, lastIssuedAtMs: T0 + 60_000 }
  const at = (ms: number) => ({ size: 1, lastModifiedMs: ms })
  const min = 60_000
  assert.equal(grantCoversObject(g, at(T0 + min)), true)
  assert.equal(grantCoversObject(g, at(T0 - 14 * min)), true, 'clock skew before issue')
  assert.equal(grantCoversObject(g, at(T0 - 16 * min)), false, 'written before the grant existed')
  assert.equal(grantCoversObject(g, at(T0 + min + 44 * min)), true, 'within TTL (30m) + skew (15m) of the last issue')
  assert.equal(grantCoversObject(g, at(T0 + min + 46 * min)), false, 'after the URL could have been used')
  assert.equal(grantCoversObject(g, undefined), false, 'no object, nothing to vouch for')
})

test('an object written outside its grant window does not make a manifest granted', async () => {
  const w = grantedWorld()
  w.put('raw/1:2/dev1/a-manifest.json', manifest('a'))
  w.grant('raw/1:2/dev1/a-manifest.json', 7, 'dev1', null, w.clock - 5 * 3_600_000) // issued 5h before the PUT
  w.tick()
  const r = await run(w)
  assert.equal(r.manifestsRejected, 1)
  assert.match(w.rejects.get('raw/1:2/dev1/a-manifest.json')!.reason, /valid upload grant/)
})

test('a granted manifest cannot claim an unowned legacy session from a different object', async () => {
  const w = world()
  w.put('raw/1:2/dev1/a-manifest.json', manifest('a'), T0) // legacy, indexed without a worker
  w.tick()
  await run(w)
  assert.equal(w.sessions.get('a')!.row.workerId, null)
  w.grant('raw/9:9/seed/s.jpg', 1, 'seed', null, T0 + 3_600_000) // grants go live; cutover T0+3h
  w.tick(5 * 3_600_000)
  w.put('raw/5:5/dev1/a-manifest.json', manifest('a')) // another cell, granted to worker 8
  w.grant('raw/5:5/dev1/a-manifest.json', 8, 'dev1', null)
  w.tick()
  const r = await run(w)
  assert.equal(r.manifestsRejected, 1)
  assert.equal(w.sessions.get('a')!.row.manifestR2Key, 'raw/1:2/dev1/a-manifest.json')
  assert.equal(w.sessions.get('a')!.row.workerId, null)
})

test('a legacy session is frozen against late objects not granted to one worker', async () => {
  const w = world()
  w.put('raw/1:2/dev1/a-manifest.json', manifest('a', {
    photoKeys: ['raw/1:2/dev1/old.jpg', 'raw/1:2/dev1/late-ungranted.jpg', 'raw/1:2/dev1/late-granted.jpg'],
  }), T0)
  w.put('raw/1:2/dev1/old.jpg', 'o', T0)
  w.grant('raw/9:9/seed/s.jpg', 1, 'seed', null, T0) // cutover T0+2h
  w.tick(5 * 3_600_000)
  w.put('raw/1:2/dev1/late-ungranted.jpg', 'x')
  w.put('raw/1:2/dev1/late-granted.jpg', 'y')
  w.grant('raw/1:2/dev1/late-granted.jpg', 7, 'dev1', null)
  w.put('raw/1:2/a/capture/late.mov', 'v') // late sibling, ungranted
  w.tick()
  const r = await run(w)
  assert.equal(r.sessionsIndexed, 1)
  assert.equal(r.sessionsGranted, 0, 'still a legacy session')
  assert.ok(w.chunks.has('raw/1:2/dev1/old.jpg'))
  assert.ok(w.chunks.has('raw/1:2/dev1/late-granted.jpg'))
  assert.ok(!w.chunks.has('raw/1:2/dev1/late-ungranted.jpg'))
  assert.ok(!w.chunks.has('raw/1:2/a/capture/late.mov'))
})

test('late objects granted to two different workers: none accepted', async () => {
  const w = world()
  w.put('raw/1:2/dev1/a-manifest.json', manifest('a', { photoKeys: ['raw/1:2/dev1/x.jpg', 'raw/1:2/dev1/y.jpg'] }), T0)
  w.grant('raw/9:9/seed/s.jpg', 1, 'seed', null, T0)
  w.tick(5 * 3_600_000)
  w.put('raw/1:2/dev1/x.jpg', 'x')
  w.put('raw/1:2/dev1/y.jpg', 'y')
  w.grant('raw/1:2/dev1/x.jpg', 7, 'dev1', null)
  w.grant('raw/1:2/dev1/y.jpg', 8, 'dev1', null)
  w.tick()
  await run(w)
  assert.ok(!w.chunks.has('raw/1:2/dev1/x.jpg'))
  assert.ok(!w.chunks.has('raw/1:2/dev1/y.jpg'))
})

test('a 2,000-character manifest key is refused cleanly and the run completes', async () => {
  const w = world()
  const longKey = `raw/1:2/dev1/${'x'.repeat(2000)}-manifest.json`
  w.put(longKey, manifest('x'.repeat(2000)))
  w.put('raw/1:2/dev1/a-manifest.json', manifest('a', { photoKeys: [`raw/1:2/dev1/${'p'.repeat(2000)}.jpg`] }))
  w.tick()
  const r = await run(w)
  assert.equal(r.sessionsIndexed, 1)
  assert.equal(r.manifestsRejected, 1)
  assert.match(w.rejects.get(longKey)!.reason, /characters/)
  assert.equal(r.foreignKeysDropped, 1, 'an over-long named key is dropped')
  assert.equal(w.enqueues, 1)
})

test('a failing rejects table never stops the run', async () => {
  const w = world()
  w.put('raw/1:2/dev1/a-manifest.json', '{"sessionId":"a"}')
  w.put('raw/1:2/dev1/b-manifest.json', manifest('b'))
  w.tick()
  const deps: ScanIndexDeps = {
    ...w.deps,
    recordRejects: async () => { throw new Error('value too long for type character varying(500)') },
  }
  const r = await runScanIndex(deps, { deadlineMs: Number.POSITIVE_INFINITY, now: () => w.clock })
  assert.equal(r.sessionsIndexed, 1)
  assert.equal(r.manifestsFailed, 1)
  assert.equal(r.manifestsRejected, 0)
  assert.equal(w.enqueues, 1, 'the enqueue still ran')
})

test('manifest values wider than their scan_sessions columns are refused, not retried forever', async () => {
  const w = world()
  w.put('raw/1:2/dev1/a-manifest.json', manifest('a', { osVersion: '1'.repeat(33) }))
  w.tick()
  const r = await run(w)
  assert.equal(r.manifestsRejected, 1)
  assert.match(w.rejects.get('raw/1:2/dev1/a-manifest.json')!.reason, /osVersion/)
})

test('two workers on one shared handset, each with their own session, both index under their own worker', async () => {
  const w = grantedWorld()
  for (const [session, worker] of [['s1', 7], ['s2', 8]] as const) {
    const manifestKey = `raw/1:2/shared/${session}-manifest.json`
    w.put(manifestKey, manifest(session, {
      deviceId: 'shared',
      photoKeys: [`raw/1:2/shared/${session}-p.jpg`],
      captureChunks: [`raw/1:2/${session}/capture/c.jsonl`],
    }))
    w.put(`raw/1:2/shared/${session}-p.jpg`, 'p')
    w.put(`raw/1:2/${session}/capture/c.jsonl`, 'c')
    w.grant(manifestKey, worker, 'shared', null)
    w.grant(`raw/1:2/shared/${session}-p.jpg`, worker, 'shared', null)
    w.grant(`raw/1:2/${session}/capture/c.jsonl`, worker, 'shared', session)
  }
  w.tick()
  const r = await run(w)
  assert.equal(r.sessionsIndexed, 2)
  assert.equal(r.sessionsGranted, 2)
  assert.equal(r.foreignKeysDropped, 0)
  assert.equal(w.sessions.get('s1')!.row.workerId, 7)
  assert.equal(w.sessions.get('s2')!.row.workerId, 8)
  assert.equal(w.chunks.get('raw/1:2/shared/s2-p.jpg')!.sessionId, 's2')
})

test('a legacy row is stamped with a worker only if its manifest was provably rewritten after the grant', async () => {
  for (const [label, writeOffsetMs, expectWorker] of [
    ['rewritten after the grant', 60_000, 7],
    ['object predates the grant (inside the skew allowance)', -5 * 60_000, null],
  ] as const) {
    const w = world()
    w.put('raw/1:2/dev1/a-manifest.json', manifest('a'), T0) // legacy, indexed unowned
    w.tick()
    await run(w)
    w.grant('raw/9:9/seed/s.jpg', 1, 'seed', null, T0 + 3_600_000) // cutover T0+3h
    w.tick(6 * 3_600_000)
    const issued = w.clock
    w.grant('raw/1:2/dev1/a-manifest.json', 7, 'dev1', null, issued)
    w.put('raw/1:2/dev1/a-manifest.json', manifest('a', { photoKeys: [] }), issued + writeOffsetMs)
    w.tick(10 * 60_000)
    await run(w)
    assert.equal(w.sessions.get('a')!.row.workerId, expectWorker, label)
  }
})

test("a grant only vouches for keys in its own folder shape", () => {
  const m = { deviceId: 'dev1', sessionId: 'a' } as Parameters<typeof manifestOwner>[1]
  const at = { size: 1, lastModifiedMs: T0 + 60_000 }
  // A session-shaped grant (session id set) sitting on a device-folder key, and
  // a device-shaped grant on a session-folder key.
  const grants = new Map<string, UploadGrant>([
    ['raw/1:2/dev1/p.jpg', { workerId: 7, deviceId: 'dev1', sessionId: 'a', issuedAtMs: T0, lastIssuedAtMs: T0 }],
    ['raw/1:2/a/c.mov', { workerId: 7, deviceId: 'dev1', sessionId: null, issuedAtMs: T0, lastIssuedAtMs: T0 }],
  ])
  const raw = new Map([['raw/1:2/dev1/p.jpg', at], ['raw/1:2/a/c.mov', at]])
  const gate = grantGate(m, 7, grants, raw)
  assert.equal(gate('raw/1:2/dev1/p.jpg', 'device'), false)
  assert.equal(gate('raw/1:2/a/c.mov', 'session'), false)
  assert.equal(writtenAfterGrant('raw/1:2/dev1/p.jpg', grants, raw), true)
})

test('a failing reconstruction-asset batch is logged and the enqueue still runs', async () => {
  const w = world()
  w.put('reconstructed/1:2/1/splat.ply', 'x')
  w.put(`reconstructed/${'9'.repeat(20)}:${'9'.repeat(20)}/1/mesh.ply`, 'x') // cell wider than varchar(32)
  const deps: ScanIndexDeps = { ...w.deps, insertReconAssets: async () => { throw new Error('boom') } }
  const r = await runScanIndex(deps, { deadlineMs: Number.POSITIVE_INFINITY, now: () => w.clock })
  assert.equal(r.reconstructionsFailed, 1)
  assert.equal(r.reconstructionsSkipped, 1)
  assert.equal(w.enqueues, 1)
})
