/**
 * Rectification bonus anti-farming (lib/rectify-checks.ts): a dirty
 * observation someone else reported, and evidence the rectifier is at the
 * cell — the request's own fresh, plausible fix, or a recorded fix whose
 * reach grows with its age (fixes reach the server in 30 s batches).
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  RECTIFY_MAX_REACH_METERS,
  decideRectification,
  fixWithinCell,
  parseRequestFix,
  reachForAge,
  requestFixPlausible,
  type RecordedFix,
} from './rectify-checks'
import { cellCenter, gridCell } from './grid-cell-client'
import { METERS_PER_DEG_LAT, METERS_PER_DEG_LNG } from './geo-utils'

const CELL = gridCell(41.8601, -87.72)
const center = cellCenter(CELL)
const NOW = Date.parse('2026-10-18T15:00:00.000Z')
const ME = 7

/** A fix `northM` / `eastM` from the cell centre, `secondsAgo` before NOW. */
const at = (northM: number, secondsAgo = 0, eastM = 0): RecordedFix => ({
  lat: center.lat + northM / METERS_PER_DEG_LAT,
  lng: center.lng + eastM / METERS_PER_DEG_LNG,
  capturedAtMs: NOW - secondsAgo * 1000,
})

const decide = (over: Partial<Parameters<typeof decideRectification>[0]> = {}) =>
  decideRectification({
    cellId: CELL,
    rectifierId: ME,
    dirty: [{ id: 1, reporterWorkerId: 8 }],
    recentFixes: [at(3)],
    latestRecorded: at(3),
    requestFix: null,
    nowMs: NOW,
    ...over,
  })

test('awards when someone else reported it and the worker is at the cell', () => {
  assert.deepEqual(decide(), { award: true, observationId: 1 })
})

test('no dirty observation in the cell → no bonus', () => {
  assert.deepEqual(decide({ dirty: [] }), { award: false, reason: 'no_dirty_observation' })
})

test('rectifying your own report → no bonus; a report by someone else in the same cell still counts', () => {
  assert.deepEqual(decide({ dirty: [{ id: 1, reporterWorkerId: ME }] }), { award: false, reason: 'own_report' })
  assert.deepEqual(decide({ dirty: [{ id: 1, reporterWorkerId: ME }, { id: 2, reporterWorkerId: 9 }] }), {
    award: true,
    observationId: 2,
  })
})

test('an observation with no known reporter (legacy, 311 import) counts as someone else', () => {
  assert.deepEqual(decide({ dirty: [{ id: 4, reporterWorkerId: null }] }), { award: true, observationId: 4 })
})

test('no recorded fix near the cell (remote farming) → no bonus', () => {
  assert.deepEqual(decide({ recentFixes: [] }), { award: false, reason: 'not_at_location' })
  assert.deepEqual(decide({ recentFixes: [at(500, 5)] }), { award: false, reason: 'not_at_location' })
})

test('standing on the marker with the newest recorded fix 30 s / 42 m behind: still at the cell', () => {
  // Walking 1.4 m/s, last flush 30 s ago: the recorded fix trails by ~42 m.
  assert.deepEqual(decide({ recentFixes: [at(-42, 30)], latestRecorded: at(-42, 30) }), { award: true, observationId: 1 })
})

test('an old fix does not vouch for a far position: reach stops growing after the flush window', () => {
  assert.equal(reachForAge(10 * 60_000), RECTIFY_MAX_REACH_METERS)
  assert.deepEqual(decide({ recentFixes: [at(200, 600)] }), { award: false, reason: 'not_at_location' })
})

test('the request’s own fix counts when fresh and plausible against the recorded trajectory', () => {
  const requestFix = at(2, 1)
  // Recorded trajectory ends 60 m away 40 s ago — reachable on foot.
  assert.deepEqual(decide({ recentFixes: [], latestRecorded: at(60, 40), requestFix }), { award: true, observationId: 1 })
})

test('a request fix is refused when it teleports from the trajectory, is stale, or has no trajectory', () => {
  const requestFix = at(2, 1)
  assert.equal(requestFixPlausible(requestFix, at(3000, 30), NOW), false, '3 km in 29 s')
  assert.equal(requestFixPlausible(at(2, 600), at(3, 610), NOW), false, '10 minutes old')
  assert.equal(requestFixPlausible(requestFix, null, NOW), false, 'no recorded trajectory to check it against')
  assert.equal(requestFixPlausible({ ...requestFix, capturedAtMs: NOW + 10 * 60_000 }, at(3, 5), NOW), false, 'future')
  assert.deepEqual(decide({ recentFixes: [], latestRecorded: at(3000, 30), requestFix }), { award: false, reason: 'not_at_location' })
})

test('a request fix that copies a recorded fix’s timestamp must still be next to it (Grok round 2, H1)', () => {
  const recorded = at(3000, 30) // 3 km from the cell, 30 s ago
  const copied = { ...at(2), capturedAtMs: recorded.capturedAtMs } // claims the cell at the same instant
  assert.equal(requestFixPlausible(copied, recorded, NOW), false)
  assert.deepEqual(decide({ recentFixes: [], latestRecorded: recorded, requestFix: copied }), { award: false, reason: 'not_at_location' })
  // Same instant, genuinely next to it: fine.
  assert.equal(requestFixPlausible({ ...at(2), capturedAtMs: NOW - 30_000 }, at(20, 30), NOW), true)
})

test('a request fix is only checked against a recent recorded fix (≤ 2 min), not the 15-minute window', () => {
  // 10 minutes is enough to walk ~1 km at 8 m/s × 600 s; the old check accepted ~5 km.
  assert.equal(requestFixPlausible(at(2, 1), at(1000, 600), NOW), false)
  assert.equal(requestFixPlausible(at(2, 1), at(150, 60), NOW), true, 'real walk: 150 m in 59 s')
})

test('parseRequestFix: only a complete {lat, lng, capturedAt}', () => {
  assert.deepEqual(parseRequestFix({ lat: 41.86, lng: -87.72, capturedAt: '2026-10-18T15:00:00.000Z' }), {
    lat: 41.86,
    lng: -87.72,
    capturedAtMs: NOW,
  })
  assert.equal(parseRequestFix({ lat: 41.86, lng: -87.72 }), null)
  assert.equal(parseRequestFix(null), null)
  assert.equal(parseRequestFix({ lat: 'x', lng: -87.72, capturedAt: '2026-10-18T15:00:00Z' }), null)
})

test('fixWithinCell: the 10 m cell grown by the age-dependent reach, on both axes', () => {
  const edge = reachForAge(0)
  assert.equal(fixWithinCell(at(edge - 0.5), CELL), true)
  assert.equal(fixWithinCell(at(0, 0, -(edge - 0.5)), CELL), true)
  assert.equal(fixWithinCell(at(edge + 0.5), CELL), false)
  assert.equal(fixWithinCell(at(edge + 0.5), CELL, 30_000), true, 'older fix, longer reach')
  assert.equal(fixWithinCell(at(0), 'garbage'), false)
})
