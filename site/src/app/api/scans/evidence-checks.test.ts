/**
 * Server-side evidence checks at the route level (fix/server-side-evidence-checks):
 *
 *   1. POST /api/scans/coverage drops implausible GPS fixes (never records
 *      them) and adds kept/dropped counts to the active scan.
 *   2. Hotspots: photo origin, distance to the route, capped volume, client
 *      verificationScore kept only as a claim, after photo required to clean.
 *   3. PATCH /api/jobs/{id}/evidence refuses a photo already used for another job.
 *   4. Rectify: no bonus for your own report or from away from the cell.
 *
 * The database is a fake (src/test-support/fake-db.ts); nothing connects.
 * The SQL itself ran against a throwaway Postgres 17 (see the PR body).
 */
import { test, afterEach, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { NextRequest } from 'next/server'

process.env.JWT_SECRET = 'test-secret-for-evidence-checks-0123456789'
process.env.DATABASE_URL ??= 'postgres://test:test@localhost/test'
process.env.ADMIN_EMAILS = 'admin@example.org'
// isOwnEvidenceUrl fails closed without a store, so tests configure ours:
// this token's store host is abc.public.blob.vercel-storage.com.
process.env.BLOB_READ_WRITE_TOKEN = 'vercel_blob_rw_abc_testsecret'

import { __setDbForTests } from '@/db'
import { createFakeDb, callArg, type FakeQuery } from '@/test-support/fake-db'
import { createMobileToken } from '@/lib/mobile-auth'
import { METERS_PER_DEG_LAT } from '@/lib/geo-utils'
import { cellCenter, gridCell } from '@/lib/grid-cell-client'
import { MAX_HOTSPOT_VOLUME_LITERS } from '@/lib/hotspot-checks'
import { POST as ingestCoverage } from './coverage/route'
import { POST as addHotspot } from './[id]/hotspots/route'
import { PATCH as updateHotspot } from './[id]/hotspots/[hid]/route'
import { POST as rectifyInScan } from './[id]/rectify/route'
import { POST as rectifyAnywhere } from '../observations/rectify/route'
import { PATCH as submitEvidence } from '../jobs/[id]/evidence/route'

const WORKER_ID = 7
const STARTED = '2026-10-07T09:00:00.000Z'
const ROUTE = { type: 'LineString', coordinates: [[-87.7202, 41.8601], [-87.7182, 41.8601]] }
const BLOB = 'https://abc.public.blob.vercel-storage.com'
const realFetch = globalThis.fetch

beforeEach(() => {
  // Fingerprinting fetches blobs; nothing may reach the network here.
  globalThis.fetch = (async () => new Response('', { status: 404 })) as typeof fetch
})
afterEach(() => {
  __setDbForTests(null)
  globalThis.fetch = realFetch
})

type Responder = (q: FakeQuery) => unknown[] | undefined

async function call(
  // Each route declares its own params shape; `never` accepts them all.
  handler: (req: NextRequest, ctx: { params: Promise<never> }) => Promise<Response>,
  method: string,
  body: unknown,
  respond: Responder,
  params: Record<string, string> = { id: '42' },
) {
  const fake = createFakeDb((q: FakeQuery) => {
    if (q.op === 'select' && q.table === 'workers') {
      return [{ email: 'w@example.org', emailVerified: true, tokenVersion: 0, id: WORKER_ID, verificationStatus: 'approved' }]
    }
    return respond(q) ?? (q.op === 'insert' ? [{ id: 1 }] : [])
  })
  __setDbForTests(fake.db)
  const { token } = await createMobileToken({ id: WORKER_ID, email: 'w@example.org', emailVerified: true, tokenVersion: 0 })
  const req = new NextRequest('http://localhost/api/x', {
    method,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
    body: JSON.stringify(body),
  })
  const res = await handler(req, { params: Promise.resolve(params) as Promise<never> })
  return { res, json: await res.json(), log: fake.log }
}

const has = (q: FakeQuery, name: string) => q.calls.some(([n]) => n === name)

// ── 1. Coverage ingest ──────────────────────────────────────────────────────

const T0 = Date.parse('2026-10-07T09:10:00.000Z')
const fixEvent = (metersNorth: number, seconds: number, over: Record<string, unknown> = {}) => ({
  deviceId: 'dev-1',
  platform: 'ios',
  lat: 41.8601 + metersNorth / METERS_PER_DEG_LAT,
  lng: -87.72,
  distanceMeters: 1.4,
  scanMode: 'route',
  deviceTier: 'basic',
  quality: 'grazed',
  capturedAt: new Date(T0 + seconds * 1000).toISOString(),
  ...over,
})

function coverageDb(anchor: unknown[] = []): Responder {
  return (q) => {
    if (q.op === 'select' && q.table === 'route_scans' && callArg(q, 'limit') === 2) {
      return [{ id: 42, scoutStartedAt: STARTED }]
    }
    if (q.op === 'select' && q.table === 'scan_coverage_events') return anchor
    return undefined
  }
}

const inserted = (log: FakeQuery[]) =>
  log.filter((q) => q.op === 'insert' && q.table === 'scan_coverage_events').map((q) => callArg(q, 'values') as Record<string, unknown>)

test('coverage: a teleport and a duplicate-timestamp fix are dropped, the walk is recorded, the scan is counted', async () => {
  const { res, json, log } = await call(
    ingestCoverage,
    'POST',
    { events: [fixEvent(0, 0), fixEvent(1.4, 1), fixEvent(300, 2), fixEvent(2.8, 2), fixEvent(2.9, 2.01)] },
    coverageDb(),
  )
  assert.equal(res.status, 200)
  assert.deepEqual(json, { inserted: 3, dropped: 2, success: true })
  assert.deepEqual(inserted(log).map((v) => Math.round(((v.lat as number) - 41.8601) * METERS_PER_DEG_LAT * 10) / 10), [0, 1.4, 2.8])
  assert.ok(inserted(log).every((v) => v.routeScanId === 42))
  const statsUpdate = log.find((q) => q.op === 'update' && q.table === 'route_scans')
  assert.ok(statsUpdate, 'kept/dropped counts are added to the active scan')
})

test('coverage: a batch whose every fix is implausible is still delivered (no retry loop), nothing recorded', async () => {
  const anchor = [{ lat: 41.8601, lng: -87.72, capturedAt: new Date(T0).toISOString() }]
  const { res, json, log } = await call(
    ingestCoverage,
    'POST',
    { events: [fixEvent(900, 1), fixEvent(901, 2)] },
    coverageDb(anchor),
  )
  assert.equal(res.status, 200)
  assert.deepEqual(json, { inserted: 0, dropped: 2, success: true })
  assert.equal(inserted(log).length, 0)
})

test('coverage: paid distance is min(client, server) — a re-anchor jump the client reports as walked pays 0', async () => {
  // Cached fix 400 m away, then a real walk; the phone reports 400 m for the first real fix.
  const events = [
    fixEvent(400, 0, { distanceMeters: 0 }),
    fixEvent(0, 1, { distanceMeters: 400 }),
    fixEvent(1.4, 2, { distanceMeters: 1.4 }),
    fixEvent(2.8, 3, { distanceMeters: 50 }), // client over-reports a 1.4 m step
  ]
  const { json, log } = await call(ingestCoverage, 'POST', { events }, coverageDb())
  assert.equal(json.inserted, 3)
  const paid = inserted(log).map((v) => Math.round((v.distanceMeters as number) * 10) / 10)
  assert.deepEqual(paid, [0, 1.4, 1.4])
})

test('coverage: a replay of fixes already on record from before 0053 (no dedupe_key) is refused (Grok round 2, M1)', async () => {
  const anchorRow = { lat: 41.8601 + 50 / METERS_PER_DEG_LAT, lng: -87.72, capturedAt: new Date(T0 + 50_000).toISOString() }
  const history = [fixEvent(0, 0), fixEvent(1.4, 1), fixEvent(2.8, 2)]
  const db: Responder = (q) => {
    if (q.op === 'select' && q.table === 'scan_coverage_events' && has(q, 'orderBy')) return [anchorRow]
    // loadRecordedCaptureTimes: the first two are on record (legacy rows, key NULL).
    if (q.op === 'select' && q.table === 'scan_coverage_events') return [{ capturedAt: history[0].capturedAt }, { capturedAt: history[1].capturedAt }]
    return coverageDb()(q)
  }
  const { json, log } = await call(ingestCoverage, 'POST', { events: history }, db)
  assert.deepEqual(json, { inserted: 1, dropped: 0, duplicates: 2, success: true })
  assert.equal(inserted(log).length, 1, 'only the fix not on record is written')
})

test('coverage: a fix the OS marked as mocked is dropped', async () => {
  const { json } = await call(ingestCoverage, 'POST', { events: [fixEvent(0, 0, { isMock: true }), fixEvent(1.4, 1)] }, coverageDb())
  assert.deepEqual(json, { inserted: 1, dropped: 1, success: true })
})

test('coverage: a retried batch inserts and pays nothing twice (Grok review MED)', async () => {
  // The unique dedupe_key turns the re-insert into ON CONFLICT DO NOTHING → no row back.
  const retry: Responder = (q) =>
    coverageDb()(q) ?? (q.op === 'insert' && q.table === 'scan_coverage_events' ? [] : undefined)
  const batch = { events: [fixEvent(0, 0), fixEvent(1.4, 1)] }
  const { res, json, log } = await call(ingestCoverage, 'POST', batch, retry)
  assert.equal(res.status, 200)
  assert.deepEqual(json, { inserted: 0, dropped: 0, duplicates: 2, success: true })
  assert.ok(!log.some((q) => q.table === 'cell_coverage_summary' && q.op === 'insert'), 'no distance added')
  assert.ok(!log.some((q) => q.table === 'points_ledger'), 'no points')
  const insert = log.find((q) => q.op === 'insert' && q.table === 'scan_coverage_events')!
  assert.ok(has(insert, 'onConflictDoNothing'), 'insert is idempotent')
  assert.match(String((callArg(insert, 'values') as Record<string, unknown>).dedupeKey), /^7\|dev-1\|2026-10-07T09:10:00\.000Z$/)
})

test('coverage: a failing GPS-stats update after the fixes are recorded does not 500 the batch', async () => {
  const failing: Responder = (q) => {
    if (q.op === 'update' && q.table === 'route_scans') throw new Error('column "gps_points_kept" does not exist')
    return coverageDb()(q)
  }
  const { res, json } = await call(ingestCoverage, 'POST', { events: [fixEvent(0, 0)] }, failing)
  assert.equal(res.status, 200)
  assert.equal(json.inserted, 1)
})

test('coverage: malformed-only batches still answer 422 as before', async () => {
  const { res } = await call(ingestCoverage, 'POST', { events: [{ deviceId: 1 }] }, coverageDb())
  assert.equal(res.status, 422)
})

// ── 2. Hotspots ─────────────────────────────────────────────────────────────

const scanRow = {
  id: 42,
  routeId: 3,
  blockId: null,
  workerId: WORKER_ID,
  performedByAdminId: null,
  scoutStartedAt: STARTED,
  status: 'in_progress',
  phase: 'scouting',
}

function hotspotDb(extra: Responder = () => undefined): Responder {
  return (q) => {
    const answer = extra(q)
    if (answer) return answer
    if (q.op === 'select' && q.table === 'route_scans') return [scanRow]
    if (q.op === 'select' && q.table === 'routes') return [{ geometry: ROUTE }]
    if (q.op === 'insert' && q.table === 'scan_hotspots') {
      return [{ id: 5, ...(callArg(q, 'values') as object), detectedAt: STARTED, cleanedAt: null, afterPhotoUrl: null }]
    }
    if (q.op === 'insert' && q.table === 'photo_fingerprints') {
      const v = callArg(q, 'values') as Record<string, unknown>
      return [{ url: v.url, status: v.status, sha256: null, dhash: null, exifCapturedAt: null, firstJobId: null, firstScanId: null }]
    }
    if (q.op === 'insert' && q.table === 'world_observations') return [{ id: 77 }]
    return undefined
  }
}

const hotspotBody = (over: Record<string, unknown> = {}) => ({
  lat: 41.8601 + 10 / METERS_PER_DEG_LAT,
  lng: -87.7192,
  beforePhotoUrl: `${BLOB}/spots/${WORKER_ID}/before.jpg`,
  estimatedVolumeLiters: 50_000,
  severity: 2,
  ...over,
})

test('hotspot: volume is capped (client figure kept beside it); photo fingerprinted and bound to the scan', async () => {
  const { res, log } = await call(addHotspot, 'POST', hotspotBody(), hotspotDb())
  assert.equal(res.status, 201)
  const values = callArg(log.find((q) => q.op === 'insert' && q.table === 'scan_hotspots')!, 'values') as Record<string, unknown>
  assert.equal(values.estimatedVolumeLiters, MAX_HOTSPOT_VOLUME_LITERS)
  assert.equal(values.clientEstimatedVolumeLiters, 50_000)
  const fp = log.filter((q) => q.table === 'photo_fingerprints')
  assert.ok(fp.some((q) => q.op === 'insert'), 'unseen photo gets a (here: unverified) fingerprint row')
  const bind = fp.find((q) => q.op === 'update')
  assert.deepEqual((callArg(bind!, 'set') as Record<string, unknown>).flags, ['unverified'])
  const obs = callArg(log.find((q) => q.op === 'insert' && q.table === 'world_observations')!, 'values') as Record<string, unknown>
  assert.equal(obs.reporterWorkerId, WORKER_ID)
})

test('hotspot: a photo that is not this worker’s upload is refused, nothing inserted', async () => {
  for (const url of [`${BLOB}/spots/99/x.jpg`, 'https://evil.example.com/spots/7/x.jpg']) {
    const { res, log } = await call(addHotspot, 'POST', hotspotBody({ beforePhotoUrl: url }), hotspotDb())
    assert.equal(res.status, 400)
    assert.ok(!log.some((q) => q.op === 'insert'))
  }
})

test('hotspot: more than the buffer from the route → 422, nothing inserted', async () => {
  const { res, log } = await call(addHotspot, 'POST', hotspotBody({ lat: 41.8601 + 200 / METERS_PER_DEG_LAT }), hotspotDb())
  assert.equal(res.status, 422)
  assert.ok(!log.some((q) => q.op === 'insert'))
})

const existingHotspot: Responder = (q) =>
  q.op === 'select' && q.table === 'scan_hotspots' ? [{ beforePhotoUrl: `${BLOB}/spots/7/before.jpg`, afterPhotoUrl: null }] : undefined

test('hotspot PATCH: cleaned needs an after photo; verificationScore is stored as a clamped claim', async () => {
  const noPhoto = await call(updateHotspot, 'PATCH', { status: 'cleaned' }, hotspotDb(existingHotspot), { id: '42', hid: '5' })
  assert.equal(noPhoto.res.status, 400)

  const ok = await call(
    updateHotspot,
    'PATCH',
    { status: 'cleaned', afterPhotoUrl: `${BLOB}/spots/7/after.jpg`, verificationScore: 9 },
    hotspotDb((q) => existingHotspot(q) ?? (q.op === 'update' && q.table === 'scan_hotspots' ? [{ id: 5, scanId: 42, lat: 41.86, lng: -87.72, status: 'cleaned', afterPhotoUrl: `${BLOB}/spots/7/after.jpg`, cleanedAt: STARTED }] : undefined)),
    { id: '42', hid: '5' },
  )
  assert.equal(ok.res.status, 200)
  const set = callArg(ok.log.find((q) => q.op === 'update' && q.table === 'scan_hotspots')!, 'set') as Record<string, unknown>
  assert.equal(set.verificationScore, 1)
})

test('hotspot PATCH: an after photo first used in another scan is flagged, not refused, and its first use is left alone', async () => {
  const after = `${BLOB}/spots/7/after.jpg`
  const { res, log } = await call(
    updateHotspot,
    'PATCH',
    { status: 'cleaned', afterPhotoUrl: after },
    hotspotDb((q) =>
      existingHotspot(q) ??
      (q.op === 'select' && q.table === 'photo_fingerprints' && callArg(q, 'limit') === 1 && q.calls.length === 3
        ? [{ url: after, status: 'ok', sha256: 'e'.repeat(64), dhash: '5', exifCapturedAt: null, firstJobId: null, firstScanId: 41 }]
        : undefined) ??
      (q.op === 'update' && q.table === 'scan_hotspots' ? [{ id: 5, scanId: 42, lat: 41.86, lng: -87.72, status: 'cleaned', afterPhotoUrl: after, cleanedAt: STARTED }] : undefined)),
    { id: '42', hid: '5' },
  )
  assert.equal(res.status, 200)
  assert.ok(!log.some((q) => q.op === 'update' && q.table === 'photo_fingerprints'), 'scan 41’s record is not rewritten')
})

test('hotspot PATCH: a stored before photo that is not this worker’s upload is flagged, never fetched (Grok HIGH 1)', async () => {
  const fetched: string[] = []
  globalThis.fetch = (async (url: string) => {
    fetched.push(String(url))
    return new Response('', { status: 404 })
  }) as typeof fetch
  // A row written before origin checks existed, pointing anywhere.
  const legacy: Responder = (q) =>
    q.op === 'select' && q.table === 'scan_hotspots' ? [{ beforePhotoUrl: 'http://169.254.169.254/latest/meta-data/', afterPhotoUrl: null }] : undefined
  const after = `${BLOB}/spots/7/after.jpg`
  const { res, log } = await call(
    updateHotspot,
    'PATCH',
    { status: 'cleaned', afterPhotoUrl: after },
    hotspotDb((q) =>
      legacy(q) ??
      (q.op === 'update' && q.table === 'scan_hotspots' ? [{ id: 5, scanId: 42, lat: 41.86, lng: -87.72, status: 'cleaned', afterPhotoUrl: after, cleanedAt: STARTED }] : undefined)),
    { id: '42', hid: '5' },
  )
  assert.equal(res.status, 200)
  assert.deepEqual(fetched, [after], 'only the own after photo is fetched')
  const saved = log.filter((q) => q.op === 'insert' && q.table === 'photo_fingerprints').map((q) => callArg(q, 'values') as Record<string, unknown>)
  assert.ok(saved.some((v) => String(v.url).startsWith('http://169.254') && v.status === 'unverified'))
})

test('job evidence: a photo another job won the atomic binding for → 409 (race-safe, Grok MED)', async () => {
  const job = { id: 10, workerId: WORKER_ID, status: 'in_progress', claimedAt: STARTED, startedAt: STARTED, beforePhotos: [], afterPhotos: [] }
  const { res, json, log } = await call(
    submitEvidence,
    'PATCH',
    { beforePhotoUrl: `${BLOB}/spots/7/b2.jpg`, afterPhotoUrl: `${BLOB}/spots/7/a2.jpg` },
    (q) => {
      if (q.op === 'select' && q.table === 'jobs') return [job]
      // Fingerprint rows say "unbound" (the other submission has not bound yet)…
      // …but the binding INSERT loses: job 55 got there first.
      if (q.op === 'insert' && q.table === 'photo_job_bindings') return []
      if (q.op === 'select' && q.table === 'photo_job_bindings') return [{ jobId: 55 }]
      return undefined
    },
    { id: '10' },
  )
  assert.equal(res.status, 409)
  assert.match(json.error, /job #55/)
  assert.ok(!log.some((q) => q.op === 'update' && q.table === 'jobs'))
})

test('hotspot PATCH: a foreign after photo is refused', async () => {
  const { res } = await call(
    updateHotspot,
    'PATCH',
    { status: 'cleaned', afterPhotoUrl: 'https://evil.example.com/a.jpg' },
    hotspotDb(existingHotspot),
    { id: '42', hid: '5' },
  )
  assert.equal(res.status, 400)
})

// ── 3. Job evidence ─────────────────────────────────────────────────────────

test('job evidence: a photo already bound to another job → 409, evidence not written', async () => {
  const job = { id: 10, workerId: WORKER_ID, status: 'in_progress', claimedAt: STARTED, startedAt: STARTED, beforePhotos: [], afterPhotos: [] }
  const { res, json, log } = await call(
    submitEvidence,
    'PATCH',
    { beforePhotoUrl: `${BLOB}/spots/7/b.jpg`, afterPhotoUrl: `${BLOB}/spots/7/a.jpg` },
    (q) => {
      if (q.op === 'select' && q.table === 'jobs') return [job]
      if (q.op === 'select' && q.table === 'photo_fingerprints' && callArg(q, 'limit') === 1) {
        return [{ url: `${BLOB}/spots/7/b.jpg`, status: 'ok', sha256: 'f'.repeat(64), dhash: '1', exifCapturedAt: null, firstJobId: 99, firstScanId: null }]
      }
      return undefined
    },
    { id: '10' },
  )
  assert.equal(res.status, 409)
  assert.match(json.error, /job #99/)
  assert.ok(!log.some((q) => q.op === 'update' && q.table === 'jobs'))
})

// ── 4. Rectify ──────────────────────────────────────────────────────────────

const CELL_LAT = 41.8601
const CELL_LNG = -87.72
const center = cellCenter(gridCell(CELL_LAT, CELL_LNG))

function rectifyDb(reporterWorkerId: number | null, fixes: unknown[], claimWins = true): Responder {
  return (q) => {
    // claimDirtyObservation: one statement claiming the row and resolving its report.
    if (q.op === 'execute' && /with claimed as/.test(q.sqlText ?? '')) return [{ claimed: claimWins ? 1 : 0, resolved: 0 }]
    if (q.op === 'select' && q.table === 'route_scans') return [{ workerId: WORKER_ID }]
    if (q.op === 'select' && q.table === 'world_observations' && has(q, 'leftJoin')) return [{ id: 5, reporterWorkerId }]
    if (q.op === 'select' && q.table === 'scan_coverage_events') return fixes
    if (q.op === 'insert' && q.table === 'world_observations') return [{ id: 77 }]
    return undefined
  }
}

const points = (log: FakeQuery[]) => log.filter((q) => q.op === 'insert' && q.table === 'points_ledger')

const fixNow = (p: { lat: number; lng: number }) => ({ ...p, capturedAt: new Date().toISOString() })

for (const [name, handler] of [
  ['scan', rectifyInScan],
  ['environment', rectifyAnywhere],
] as const) {
  test(`rectify (${name}): someone else's report + at the cell → bonus`, async () => {
    const { json, log } = await call(handler as never, 'POST', { lat: CELL_LAT, lng: CELL_LNG }, rectifyDb(8, [fixNow(center)]))
    assert.equal(json.wasRectification, true)
    assert.equal(points(log).length, 1)
  })

  test(`rectify (${name}): own report → clean observation recorded, no bonus`, async () => {
    const { json, log } = await call(handler as never, 'POST', { lat: CELL_LAT, lng: CELL_LNG }, rectifyDb(WORKER_ID, [fixNow(center)]))
    assert.equal(json.bonusWithheld, 'own_report')
    assert.equal(json.pointsAwarded, 0)
    assert.equal(points(log).length, 0)
    assert.ok(log.some((q) => q.op === 'insert' && q.table === 'world_observations'))
  })

  test(`rectify (${name}): no recorded fix near the cell → no bonus`, async () => {
    const far = fixNow({ lat: center.lat + 1, lng: center.lng })
    const { json, log } = await call(handler as never, 'POST', { lat: CELL_LAT, lng: CELL_LNG }, rectifyDb(8, [far]))
    assert.equal(json.bonusWithheld, 'not_at_location')
    assert.equal(points(log).length, 0)
  })

  test(`rectify (${name}): the claim resolves the dirty row's community report in the same statement (Grok round 2, H2)`, async () => {
    const { log } = await call(handler as never, 'POST', { lat: CELL_LAT, lng: CELL_LNG }, rectifyDb(8, [fixNow(center)]))
    const claim = log.find((q) => q.op === 'execute' && /with claimed as/.test(q.sqlText ?? ''))
    assert.ok(claim, 'claim ran')
    assert.match(claim!.sqlText!, /update community_reports set status = 'resolved', show_on_map = false/)
    assert.match(claim!.sqlText!, /select community_report_id from claimed/)
  })

  test(`rectify (${name}): a concurrent duplicate that loses the atomic claim is not paid twice`, async () => {
    const { json, log } = await call(handler as never, 'POST', { lat: CELL_LAT, lng: CELL_LNG }, rectifyDb(8, [fixNow(center)], false))
    assert.equal(json.pointsAwarded, 0)
    assert.equal(json.bonusWithheld, 'already_rectified')
    assert.equal(points(log).length, 0)
  })

  test(`rectify (${name}): the request's own fresh fix counts when the recorded trajectory is just behind`, async () => {
    // Newest recorded fix 60 m away 40 s ago (not yet flushed since); the app sends where it is now.
    const behind = { lat: center.lat + 60 / METERS_PER_DEG_LAT, lng: center.lng, capturedAt: new Date(Date.now() - 40_000).toISOString() }
    const body = { lat: CELL_LAT, lng: CELL_LNG, fix: fixNow(center) }
    const { json, log } = await call(handler as never, 'POST', body, rectifyDb(8, [behind]))
    assert.equal(json.wasRectification, true)
    assert.equal(points(log).length, 1)
  })
}
