import assert from 'node:assert/strict'
import test from 'node:test'

import { completeJob } from './job-lifecycle'
import {
  findActiveScanConflict,
  findCoverageAttributionScan,
  findQualifyingScanForJob,
  isUniqueViolation,
  loadScanRouteCoverage,
  loadScanWorkedCells,
  markScanComplete,
} from './scan-completion'
import { routeRequiredCells } from './scan-verification'
import { recordingDb, when } from './testing/recording-db'

const NOW = '2026-10-07T12:00:00.000Z'
const STARTED = '2026-10-07T09:00:00.000Z'
const flat = (sql: string) => sql.replace(/[()]/g, '')

test('loadScanWorkedCells: only route-mode GRAZED/CLOSE events attributed to THIS scan (no worker/time-window heuristic)', async () => {
  const { db, queries } = recordingDb(when(/from "scan_coverage_events"/, [['100:200'], ['100:201']]))
  const cells = await loadScanWorkedCells(db, 42)
  assert.deepEqual([...cells], ['100:200', '100:201'])
  const q = queries[0]
  assert.match(q.sql, /^select distinct/)
  const sql = flat(q.sql)
  assert.match(sql, /"route_scan_id" = \$\d+/)
  assert.match(sql, /"scan_mode" = \$\d+/)
  assert.match(sql, /"quality" in \$\d+, \$\d+/)
  assert.doesNotMatch(sql, /"worker_id"/, 'events by the same worker in another scan must not count')
  assert.doesNotMatch(sql, /"captured_at"/, 'the time window is no longer what ties an event to a scan')
  for (const p of [42, 'route', 'grazed', 'close']) assert.ok(q.params.includes(p), `param ${p}`)
  assert.ok(!q.params.includes('seen'), 'SEEN never counts as worked ground')
})

const ROUTE_GEOMETRY = { type: 'LineString', coordinates: [[-87.7202, 41.8601], [-87.7182, 41.8601]] }

test('loadScanRouteCoverage: one definition — route geometry vs this scan\'s attributed cells', async () => {
  const routeCells = [...routeRequiredCells(ROUTE_GEOMETRY)]
  const { db, queries } = recordingDb(
    when(/from "routes"/, [[JSON.stringify(ROUTE_GEOMETRY)]]),
    when(/from "scan_coverage_events"/, routeCells.map((c) => [c])),
  )
  const { coverage } = await loadScanRouteCoverage(db, { id: 42, routeId: 3 })
  assert.equal(coverage.ratio, 1)
  assert.ok(queries.find((q) => /from "routes"/.test(q.sql))?.params.includes(3))
  assert.ok(queries.find((q) => /from "scan_coverage_events"/.test(q.sql))?.params.includes(42))

  const { db: empty } = recordingDb(when(/from "routes"/, [[JSON.stringify(ROUTE_GEOMETRY)]]))
  assert.equal((await loadScanRouteCoverage(empty, { id: 42, routeId: 3 })).coverage.ratio, 0)
})

test('findCoverageAttributionScan: the caller\'s own scan or the one they perform as admin; ambiguous → null', async () => {
  const { db, queries } = recordingDb(when(/from "route_scans"/, [[42, STARTED]]))
  assert.deepEqual(await findCoverageAttributionScan(db, 3, NOW), { id: 42, scoutStartedAt: STARTED })
  const sql = flat(queries[0].sql)
  assert.match(sql, /"status" = \$\d+/)
  assert.match(sql, /"created_at" >= \$\d+::timestamptz - make_interval/)
  assert.match(sql, /"performed_by_admin_id" = \$\d+ or "route_scans"\."worker_id" = \$\d+ and "route_scans"\."performed_by_admin_id" is null/)
  for (const p of ['in_progress', NOW, 3]) assert.ok(queries[0].params.includes(p), `param ${p}`)

  const { db: two } = recordingDb(when(/from "route_scans"/, [[42, STARTED], [43, STARTED]]))
  assert.equal(await findCoverageAttributionScan(two, 3, NOW), null, 'two active scans → attribute nothing')
  const { db: none } = recordingDb()
  assert.equal(await findCoverageAttributionScan(none, 3, NOW), null)
})

test('findActiveScanConflict: the target worker\'s active scan, or one the caller is already walking', async () => {
  const { db, queries } = recordingDb(when(/from "route_scans"/, [[9, 7, null]]))
  assert.deepEqual(await findActiveScanConflict(db, { workerId: 7, callerId: 3 }, NOW), {
    id: 9,
    workerId: 7,
    performedByAdminId: null,
  })
  const sql = flat(queries[0].sql)
  assert.match(sql, /"worker_id" in \$\d+, \$\d+ or "route_scans"\."performed_by_admin_id" = \$\d+/)
  assert.match(sql, /"status" = \$\d+/)
  for (const p of [7, 3, 'in_progress']) assert.ok(queries[0].params.includes(p), `param ${p}`)

  const { db: none } = recordingDb()
  assert.equal(await findActiveScanConflict(none, { workerId: 7, callerId: 7 }, NOW), null)
})

test('markScanComplete: the UPDATE re-checks in_progress + cleaning-lap phase + recorded lap-one proof', async () => {
  const { db, queries } = recordingDb()
  const row = await markScanComplete(
    db,
    42,
    { cleanPathGeoJson: null, totalTrashVolumeLiters: null, totalTrashWeightLbs: null, pointsAwarded: 50, verifiedCoverageRatio: 0.93 },
    NOW,
  )
  assert.equal(row, null, '0 rows (state changed meanwhile) → null → the route answers 409')
  const q = queries[0]
  assert.match(q.sql, /^update "route_scans" set/)
  assert.match(q.sql, /"verified_coverage_ratio" = \$\d+/)
  assert.ok(q.params.includes(0.93))
  const where = flat(q.sql.slice(q.sql.indexOf(' where ')))
  assert.match(where, /"status" = \$\d+/)
  assert.match(where, /"phase" in \$\d+, \$\d+/)
  assert.match(where, /"lap_one_verified_at" is not null/)
  for (const p of ['in_progress', 'cleaning', 'submitting', 42]) assert.ok(q.params.includes(p), `param ${p}`)
})

// select columns: id, status, phase, verifiedCoverageRatio, createdAt, usedByJobId
const scanRow = (id: number, createdAt: string, usedByJobId: number | null, ratio: number | null = 0.95) => [
  id, 'completed', 'complete', ratio, createdAt, usedByJobId,
]
const JOB = { id: 50, workerId: 7, claimedAt: '2026-10-07T08:00:00.000Z', startedAt: '2026-10-07T08:30:00.000Z' }

test('findQualifyingScanForJob: filters to the job\'s route + worker and reports which other job used each scan', async () => {
  const { db, queries } = recordingDb(when(/from "route_scans"/, [scanRow(9, STARTED, null)]))
  const scan = await findQualifyingScanForJob(db, JOB, 33)
  assert.equal(scan?.id, 9)
  const q = queries[0]
  assert.match(flat(q.sql), /"route_id" = \$\d+ and "route_scans"\."worker_id" = \$\d+ and "route_scans"\."status" = \$\d+/)
  assert.match(q.sql, /select j\.id from jobs j where j\.completion_scan_id = "route_scans"\."id" and j\.id <> \$\d+/)
  for (const p of [33, 7, 'completed', 50]) assert.ok(q.params.includes(p), `param ${p}`)
})

test('findQualifyingScanForJob: a scan from before the claim is refused', async () => {
  const { db } = recordingDb(when(/from "route_scans"/, [scanRow(9, '2026-10-06T09:00:00.000Z', null)]))
  assert.equal(await findQualifyingScanForJob(db, JOB, 33), null)
})

test('findQualifyingScanForJob: a scan already used by another job is refused', async () => {
  const { db } = recordingDb(when(/from "route_scans"/, [scanRow(9, STARTED, 49)]))
  assert.equal(await findQualifyingScanForJob(db, JOB, 33), null)
})

test('findQualifyingScanForJob: a legacy completed scan with no server-verified coverage is refused', async () => {
  const { db } = recordingDb(when(/from "route_scans"/, [scanRow(9, STARTED, null, null)]))
  assert.equal(await findQualifyingScanForJob(db, JOB, 33), null)
})

test('completeJob records the completion scan; unique violations are recognised', async () => {
  const { db, queries } = recordingDb()
  await completeJob(db, { id: 50, payoutAmount: '2500', payoutStatus: null }, { workerId: 7, isAdmin: false }, NOW, {
    completionScanId: 9,
  })
  const setClause = (sql: string) => sql.slice(0, sql.indexOf(' where '))
  assert.match(setClause(queries[0].sql), /"completion_scan_id" = \$\d+/)
  assert.ok(queries[0].params.includes(9))

  const { db: db2, queries: q2 } = recordingDb()
  await completeJob(db2, { id: 50, payoutAmount: '0', payoutStatus: null }, { workerId: 1, isAdmin: true }, NOW)
  assert.doesNotMatch(setClause(q2[0].sql), /completion_scan_id/, 'admin recovery path records no scan')

  assert.equal(isUniqueViolation({ code: '23505' }), true)
  assert.equal(isUniqueViolation({ cause: { code: '23505' } }), true)
  assert.equal(isUniqueViolation(new Error('boom')), false)
})
