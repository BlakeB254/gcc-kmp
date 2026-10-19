/**
 * Server-side coverage attribution for block-route scans (security review of
 * sec/verified-completion):
 *
 *   A. PATCH /api/scans/{id}/phase stamped lap_one_verified_at on the client's
 *      own numbers (coveragePercent >= 95 …). The stamp now needs the route
 *      coverage the SERVER computes from events attributed to this scan.
 *   B. Completion counted every route event by the scan's worker/admin since
 *      the scan started. Events now carry route_scan_id, stamped at ingest
 *      from the caller's single active scan, and only those count.
 *
 * The database is a fake (src/test-support/fake-db.ts); nothing connects. The
 * SQL itself is asserted in lib/scan-completion.test.ts and was run against a
 * throwaway Postgres 17 (see the PR body).
 */
import { test, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { NextRequest } from 'next/server'

process.env.JWT_SECRET = 'test-secret-for-scan-attribution-0123456789'
process.env.DATABASE_URL ??= 'postgres://test:test@localhost/test'
process.env.ADMIN_EMAILS = 'admin@example.org'

import { __setDbForTests } from '@/db'
import { createFakeDb, callArg, type FakeQuery } from '@/test-support/fake-db'
import { createMobileToken } from '@/lib/mobile-auth'
import { routeRequiredCells } from '@/lib/scan-verification'
import { POST as startScan } from './route'
import { POST as ingestCoverage } from './coverage/route'
import { PATCH as advancePhase } from './[id]/phase/route'

const WORKER_ID = 7
const ADMIN_ID = 3
const STARTED = '2026-10-07T09:00:00.000Z'
const ROUTE = { type: 'LineString', coordinates: [[-87.7202, 41.8601], [-87.7182, 41.8601]] }
const ROUTE_CELLS = [...routeRequiredCells(ROUTE)]

afterEach(() => __setDbForTests(null))

type Answers = {
  /** findRouteScan (full row). */
  scan?: Record<string, unknown> | null
  /** findActiveScanConflict (has orderBy). */
  conflict?: Record<string, unknown> | null
  /** findCoverageAttributionScan (limit 2). */
  attribution?: Array<Record<string, unknown>>
  /** loadScanWorkedCells. */
  workedCells?: string[]
}

function limitOf(q: FakeQuery): unknown {
  return callArg(q, 'limit')
}

function install(answers: Answers, admin = false) {
  return createFakeDb((q: FakeQuery) => {
    if (q.op === 'select' && q.table === 'workers') {
      return [
        {
          email: admin ? 'admin@example.org' : 'w@example.org',
          emailVerified: true,
          tokenVersion: 0,
          id: WORKER_ID,
          verificationStatus: 'approved',
        },
      ]
    }
    if (q.table === 'route_scans') {
      if (q.op === 'select') {
        if (q.calls.some(([n]) => n === 'orderBy')) return answers.conflict ? [answers.conflict] : []
        if (limitOf(q) === 2) return answers.attribution ?? []
        return answers.scan ? [answers.scan] : []
      }
      if (q.op === 'update') return [{ ...answers.scan, ...(callArg(q, 'set') as object) }]
      if (q.op === 'insert') return [{ id: 99, ...(callArg(q, 'values') as object) }]
    }
    if (q.op === 'select' && q.table === 'routes') return [{ id: 3, geometry: ROUTE }]
    if (q.op === 'select' && q.table === 'scan_coverage_events') {
      return (answers.workedCells ?? []).map((cellId) => ({ cellId }))
    }
    if (q.op === 'insert' && q.table === 'scan_coverage_events') return [{ id: 1 }]
    return []
  })
}

async function call(
  handler: (req: NextRequest, ctx: { params: Promise<{ id: string }> }) => Promise<Response>,
  method: string,
  path: string,
  body: unknown,
  answers: Answers,
  { admin = false, callerId = WORKER_ID, id = '42' } = {},
) {
  const fake = install(answers, admin)
  __setDbForTests(fake.db)
  const { token } = await createMobileToken({
    id: callerId,
    email: admin ? 'admin@example.org' : 'w@example.org',
    emailVerified: true,
    tokenVersion: 0,
  })
  const req = new NextRequest(`http://localhost${path}`, {
    method,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
    body: JSON.stringify(body),
  })
  const res = await handler(req, { params: Promise.resolve({ id }) })
  return { res, json: await res.json(), log: fake.log }
}

const scoutingScan = {
  id: 42,
  routeId: 3,
  blockId: null,
  workerId: WORKER_ID,
  deviceType: 'gps',
  phase: 'scouting',
  coveragePercent: 0,
  scoutStartedAt: STARTED,
  scoutCompletedAt: null,
  cleanStartedAt: null,
  cleanCompletedAt: null,
  startLat: 41.8601,
  startLng: -87.7202,
  scoutPathGeoJson: null,
  cleanPathGeoJson: null,
  totalTrashVolumeLiters: null,
  totalTrashWeightLbs: null,
  pointsAwarded: 0,
  status: 'in_progress',
  performedByAdminId: null,
  lapOneVerifiedAt: null,
  lapOneCoverageRatio: null,
  verifiedCoverageRatio: null,
  createdAt: STARTED,
  updatedAt: STARTED,
}

const CLAIMS_96 = { phase: 'scout_review', coveragePercent: 96, closeCoveragePercent: 0, loopClosed: true }

function scanUpdates(log: FakeQuery[]) {
  return log.filter((q) => q.op === 'update' && q.table === 'route_scans')
}

// ── A. Lap-one stamp ────────────────────────────────────────────────────────

test('phase: a client claiming 96% with no attributed coverage events is refused and NOT stamped', async () => {
  const { res, json, log } = await call(advancePhase, 'PATCH', '/api/scans/42/phase', CLAIMS_96, {
    scan: scoutingScan,
    workedCells: [],
  })
  assert.equal(res.status, 409)
  assert.match(json.message, /server has recorded 0% of this route/)
  assert.equal(json.data.coverage.covered, 0)
  assert.equal(scanUpdates(log).length, 0, 'no UPDATE — lap_one_verified_at stays null')
})

test('phase: attributed events covering half the route → refused, not stamped', async () => {
  const half = ROUTE_CELLS.slice(0, Math.floor(ROUTE_CELLS.length / 2))
  const { res, log } = await call(advancePhase, 'PATCH', '/api/scans/42/phase', CLAIMS_96, {
    scan: scoutingScan,
    workedCells: half,
  })
  assert.equal(res.status, 409)
  assert.equal(scanUpdates(log).length, 0)
})

test('phase: attributed events covering the route + a valid client proof → stamped with the server ratio', async () => {
  const { res, log } = await call(advancePhase, 'PATCH', '/api/scans/42/phase', CLAIMS_96, {
    scan: scoutingScan,
    workedCells: ROUTE_CELLS,
  })
  assert.equal(res.status, 200)
  const [update] = scanUpdates(log)
  const set = callArg(update, 'set') as Record<string, unknown>
  assert.equal(typeof set.lapOneVerifiedAt, 'string')
  assert.equal(set.lapOneCoverageRatio, 1)
  assert.equal(set.phase, 'scout_review')
})

test('phase: full server coverage but NO client proof is still refused (proof stays a required consistency check)', async () => {
  const { res, log } = await call(advancePhase, 'PATCH', '/api/scans/42/phase', { phase: 'scout_review' }, {
    scan: scoutingScan,
    workedCells: ROUTE_CELLS,
  })
  assert.equal(res.status, 409)
  assert.equal(scanUpdates(log).length, 0)
})

// ── One active route scan ───────────────────────────────────────────────────

test('start: a second concurrent route scan for the same worker → 409, nothing inserted', async () => {
  const { res, json, log } = await call(
    startScan,
    'POST',
    '/api/scans',
    { routeId: 3, startLat: 41.86, startLng: -87.72 },
    { conflict: { id: 42, workerId: WORKER_ID, performedByAdminId: null } },
  )
  assert.equal(res.status, 409)
  assert.equal(json.data.activeScanId, 42)
  assert.match(json.message, /already have a route scan in progress \(scan 42\)/)
  assert.ok(!log.some((q) => q.op === 'insert' && q.table === 'route_scans'))
})

test('start: an admin on behalf of a worker who already has an active scan → 409 before any job is created', async () => {
  const { res, json, log } = await call(
    startScan,
    'POST',
    '/api/scans',
    { routeId: 3, startLat: 41.86, startLng: -87.72, onBehalfOfWorkerId: WORKER_ID },
    { conflict: { id: 42, workerId: WORKER_ID, performedByAdminId: null } },
    { admin: true, callerId: ADMIN_ID },
  )
  assert.equal(res.status, 409)
  assert.match(json.message, /This worker already has/)
  assert.ok(!log.some((q) => q.op === 'insert'), 'no job, no scan')
})

test('start: no active scan → 201', async () => {
  const { res, log } = await call(
    startScan,
    'POST',
    '/api/scans',
    { routeId: 3, startLat: 41.86, startLng: -87.72 },
    { conflict: null },
  )
  assert.equal(res.status, 201)
  assert.ok(log.some((q) => q.op === 'insert' && q.table === 'route_scans'))
})

// ── B. Attribution at ingest ────────────────────────────────────────────────

const event = (over: Record<string, unknown> = {}) => ({
  deviceId: 'dev-1',
  platform: 'ios',
  lat: 41.8601,
  lng: -87.72,
  distanceMeters: 2,
  scanMode: 'route',
  deviceTier: 'basic',
  quality: 'grazed',
  capturedAt: '2026-10-07T09:10:00.000Z',
  ...over,
})

function insertedEvents(log: FakeQuery[]) {
  return log
    .filter((q) => q.op === 'insert' && q.table === 'scan_coverage_events')
    .map((q) => callArg(q, 'values') as Record<string, unknown>)
}

test('coverage ingest: route events after the active scan started are stamped with it; spot/earlier events are not', async () => {
  const { res, log } = await call(
    ingestCoverage,
    'POST',
    '/api/scans/coverage',
    {
      // One fix per second: fixes sharing a timestamp are a duplicate burst
      // and dropped by the GPS plausibility filter (lib/gps-plausibility.ts).
      events: [
        event(),
        event({ scanMode: 'spot', capturedAt: '2026-10-07T09:10:01.000Z' }),
        event({ capturedAt: '2026-10-07T08:00:00.000Z' }),
        // A client cannot choose the scan: a smuggled routeScanId is overwritten.
        event({ routeScanId: 999, capturedAt: '2026-10-07T09:10:02.000Z' }),
      ],
    },
    { attribution: [{ id: 42, scoutStartedAt: STARTED }] },
  )
  assert.equal(res.status, 200)
  assert.deepEqual(
    insertedEvents(log).map((v) => v.routeScanId),
    [42, null, null, 42],
  )
})

test('coverage ingest: no active scan → events recorded with route_scan_id null', async () => {
  const { res, log } = await call(ingestCoverage, 'POST', '/api/scans/coverage', { events: [event(), event({ routeScanId: 42, capturedAt: '2026-10-07T09:10:01.000Z' })] }, {
    attribution: [],
  })
  assert.equal(res.status, 200)
  assert.deepEqual(insertedEvents(log).map((v) => v.routeScanId), [null, null])
})

test('coverage ingest: two active scans (legacy rows) → ambiguous, nothing attributed', async () => {
  const { log } = await call(ingestCoverage, 'POST', '/api/scans/coverage', { events: [event()] }, {
    attribution: [
      { id: 42, scoutStartedAt: STARTED },
      { id: 43, scoutStartedAt: STARTED },
    ],
  })
  assert.deepEqual(insertedEvents(log).map((v) => v.routeScanId), [null])
})

test('coverage ingest: an admin performing a scan has their events stamped with that scan', async () => {
  const { log } = await call(
    ingestCoverage,
    'POST',
    '/api/scans/coverage',
    { events: [event()] },
    { attribution: [{ id: 77, scoutStartedAt: STARTED }] },
    { admin: true, callerId: ADMIN_ID },
  )
  const [values] = insertedEvents(log)
  assert.equal(values.routeScanId, 77)
  assert.equal(values.workerId, ADMIN_ID, 'the event is still the caller\'s (points stay with who walked it)')
})
