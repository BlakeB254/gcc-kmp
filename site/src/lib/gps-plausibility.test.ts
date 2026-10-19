/**
 * GPS plausibility filter for coverage fixes (lib/gps-plausibility.ts):
 * impossible speed, future timestamps, duplicate-timestamp bursts and mock
 * locations are dropped one fix at a time; the trajectory continues across
 * batches from the device's last recorded fix.
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  DUPLICATE_TIMESTAMP_MS,
  GPS_JITTER_SLACK_METERS,
  GPS_SUSPECT_MIN_DROPPED,
  MAX_FUTURE_SKEW_MS,
  MAX_PLAUSIBLE_SPEED_MPS,
  filterDeviceFixes,
  fixDistanceMeters,
  isGpsSuspect,
  plausibleStep,
  readMockFlag,
  type FixInput,
} from './gps-plausibility'
import { METERS_PER_DEG_LAT } from './geo-utils'

const T0 = Date.parse('2026-10-07T09:10:00.000Z')
const NOW = T0 + 60 * 60 * 1000
const LAT = 41.8601
const LNG = -87.72

/** A fix `metersNorth` north of the origin, `seconds` after T0. */
function fix(metersNorth: number, seconds: number, extra: Partial<FixInput> = {}): FixInput {
  return {
    lat: LAT + metersNorth / METERS_PER_DEG_LAT,
    lng: LNG,
    capturedAt: new Date(T0 + seconds * 1000).toISOString(),
    ...extra,
  }
}

test('a walking track at 1.4 m/s, one fix per second, keeps every fix', () => {
  const walk = Array.from({ length: 30 }, (_, i) => fix(i * 1.4, i))
  const r = filterDeviceFixes(walk, null, NOW)
  assert.equal(r.kept.length, 30)
  assert.deepEqual(r.dropped, [])
})

test('cycling at 7 m/s is kept; a car at 15 m/s is dropped after its first fix', () => {
  const bike = Array.from({ length: 10 }, (_, i) => fix(i * 7, i))
  assert.equal(filterDeviceFixes(bike, null, NOW).dropped.length, 0)

  const car = Array.from({ length: 10 }, (_, i) => fix(i * 15, i))
  const r = filterDeviceFixes(car, null, NOW)
  assert.deepEqual(r.kept, [0], 'every later fix is too far from the last kept one')
  assert.ok(r.dropped.every((d) => d.reason === 'speed'))
})

test('ordinary GPS jitter (≤ slack) between fixes 1 s apart is not speed', () => {
  assert.equal(plausibleStep({ lat: LAT, lng: LNG, capturedAtMs: T0 }, { ...fix(GPS_JITTER_SLACK_METERS + 8, 1), capturedAtMs: T0 + 1000 }), 'ok')
  assert.equal(plausibleStep({ lat: LAT, lng: LNG, capturedAtMs: T0 }, { ...fix(GPS_JITTER_SLACK_METERS + 8.5, 1), capturedAtMs: T0 + 1000 }), 'speed')
  assert.equal(MAX_PLAUSIBLE_SPEED_MPS, 8)
})

test('a scripted 10 Hz track (2.8 m per 100 ms) is dropped: slack shrinks with the interval', () => {
  const spoof = Array.from({ length: 30 }, (_, i) => fix(i * 2.8, i * 0.1))
  const r = filterDeviceFixes(spoof, null, NOW)
  assert.deepEqual(r.kept, [0])
  assert.ok(r.dropped.every((d) => d.reason === 'speed'))
  // An honest 5 Hz walk (0.28 m per 200 ms, plus 1 m of noise) is still kept.
  const fast = Array.from({ length: 30 }, (_, i) => fix(i * 0.28 + (i % 2), i * 0.2))
  assert.equal(filterDeviceFixes(fast, null, NOW).dropped.length, 0)
})

test('one jitter spike is dropped and the walk continues from the last kept fix', () => {
  const track = [fix(0, 0), fix(1.4, 1), fix(80, 2), fix(4.2, 3), fix(5.6, 4)]
  const r = filterDeviceFixes(track, null, NOW)
  assert.deepEqual(r.kept, [0, 1, 3, 4])
  assert.deepEqual(r.dropped, [{ index: 2, reason: 'speed' }])
})

test('a teleport across the route is dropped until enough time has passed to walk it', () => {
  // 200 m away 3 s later: impossible. 30 s later: 6.5 m/s after slack — plausible.
  const r = filterDeviceFixes([fix(0, 0), fix(200, 3), fix(200, 30)], null, NOW)
  assert.deepEqual(r.kept, [0, 2])
  assert.deepEqual(r.dropped, [{ index: 1, reason: 'speed' }])
})

test('duplicate-timestamp bursts: only the first fix of a burst is kept', () => {
  const burst = [fix(0, 0), fix(3, 0), fix(6, 0), fix(9, 0.05)]
  const r = filterDeviceFixes(burst, null, NOW)
  assert.deepEqual(r.kept, [0])
  assert.ok(r.dropped.every((d) => d.reason === 'duplicate_timestamp'))
  assert.equal(DUPLICATE_TIMESTAMP_MS, 100)
})

test('a resubmitted fix (same as the anchor) is a duplicate, not double credit', () => {
  const anchor = { lat: LAT, lng: LNG, capturedAtMs: T0 }
  const r = filterDeviceFixes([fix(0, 0)], anchor, NOW)
  assert.deepEqual(r.dropped, [{ index: 0, reason: 'duplicate_timestamp' }])
})

test('future timestamps beyond the skew allowance are dropped; within it they are kept', () => {
  const near = { lat: LAT, lng: LNG, capturedAt: new Date(NOW + MAX_FUTURE_SKEW_MS - 1000).toISOString() }
  const far = { lat: LAT, lng: LNG, capturedAt: new Date(NOW + MAX_FUTURE_SKEW_MS + 1000).toISOString() }
  assert.deepEqual(filterDeviceFixes([near], null, NOW).kept, [0])
  assert.deepEqual(filterDeviceFixes([far], null, NOW).dropped, [{ index: 0, reason: 'future' }])
})

test('unparseable timestamps and mock-location fixes are dropped', () => {
  const r = filterDeviceFixes([{ lat: LAT, lng: LNG, capturedAt: 'yesterday' }, fix(0, 1, { mocked: true }), fix(1, 2)], null, NOW)
  assert.deepEqual(r.dropped, [
    { index: 0, reason: 'bad_timestamp' },
    { index: 1, reason: 'mock' },
  ])
  assert.deepEqual(r.kept, [2])
})

test('readMockFlag accepts the names a client might send, and nothing else', () => {
  assert.equal(readMockFlag({ isMock: true }), true)
  assert.equal(readMockFlag({ mocked: true }), true)
  assert.equal(readMockFlag({ isMocked: true }), true)
  assert.equal(readMockFlag({ isMock: 'true' }), false)
  assert.equal(readMockFlag({}), false)
})

test('the trajectory continues from the previous batch: a jump from the anchor is dropped', () => {
  const anchor = { lat: LAT, lng: LNG, capturedAtMs: T0 }
  const r = filterDeviceFixes([fix(500, 5), fix(501.4, 6)], anchor, NOW)
  assert.equal(r.kept.length, 0, 'both fixes are 500 m from where the device was 5 s earlier')
})

test('a batch is checked in time order, whatever order it arrives in', () => {
  const r = filterDeviceFixes([fix(2.8, 2), fix(0, 0), fix(1.4, 1)], null, NOW)
  assert.deepEqual(r.kept, [0, 1, 2])
})

test('offline backlog older than the anchor is checked against its own predecessors', () => {
  // Anchor is at t=100 s, 140 m north; the backlog walks t=0..3 from the origin.
  const anchor = { lat: LAT + 140 / METERS_PER_DEG_LAT, lng: LNG, capturedAtMs: T0 + 100_000 }
  const backlog = [fix(0, 0), fix(1.4, 1), fix(2.8, 2), fix(4.2, 3)]
  assert.deepEqual(filterDeviceFixes(backlog, anchor, NOW).kept, [0, 1, 2, 3])
})

// ── Stale first fix (Grok review HIGH 2) ────────────────────────────────────

const walk = (n: number, startSec = 1, offsetM = 0) => Array.from({ length: n }, (_, i) => fix(offsetM + i * 1.4, startSec + i))

test('a cached last-known fix 400 m away, then a clean 1 Hz walk: the walk is kept, the cached fix dropped', () => {
  const cached = fix(400, 0)
  const r = filterDeviceFixes([cached, ...walk(30)], null, NOW)
  assert.deepEqual(r.kept, Array.from({ length: 30 }, (_, i) => i + 1))
  assert.deepEqual(r.dropped, [{ index: 0, reason: 'stale_anchor' }])
})

test('a stored anchor 3 km away from the previous batch does not blank a whole short block', () => {
  const stale = { lat: LAT + 3000 / METERS_PER_DEG_LAT, lng: LNG, capturedAtMs: T0 }
  const r = filterDeviceFixes(walk(60), stale, NOW)
  assert.equal(r.kept.length, 60, 'every fix of the real walk counts')
  assert.deepEqual(r.dropped, [])
})

test('an iOS-rate stream: cached fix, then bursts of near-simultaneous updates along a real walk', () => {
  const stream = [
    fix(300, 0), // cached CLLocation from elsewhere
    fix(0, 0.08), // < 100 ms after it: duplicate burst
    fix(0.2, 0.3),
    fix(0.25, 0.35), // burst
    fix(1.6, 1.3),
    fix(3.0, 2.3),
    fix(3.05, 2.32), // burst
    ...walk(10, 3.3, 4.4),
  ]
  const r = filterDeviceFixes(stream, null, NOW)
  const reasons = Object.fromEntries(r.dropped.map((d) => [d.index, d.reason]))
  assert.equal(reasons[0], 'stale_anchor')
  assert.equal(reasons[1], 'duplicate_timestamp')
  assert.equal(reasons[3], 'duplicate_timestamp')
  assert.equal(reasons[6], 'duplicate_timestamp')
  assert.deepEqual(r.kept, [2, 4, 5, ...Array.from({ length: 10 }, (_, i) => 7 + i)])
})

test('two agreeing outliers never re-anchor; a long real track before a jump is kept on both sides', () => {
  const twoBad = [...walk(5, 0), fix(500, 5), fix(501.4, 6), ...walk(5, 7, 9.8)]
  const r = filterDeviceFixes(twoBad, null, NOW)
  assert.deepEqual(r.dropped.map((d) => [d.index, d.reason]), [[5, 'speed'], [6, 'speed']])

  // 10 s walking, GPS lost, then 10 s walking 400 m away (regained after a real move).
  const relocated = [...walk(10, 0), ...walk(10, 20, 400)]
  const r2 = filterDeviceFixes(relocated, null, NOW)
  assert.equal(r2.kept.length, 20, 'a long anchor-side chain is a real track, not an outlier')
})

// ── Paid distance (Grok round 2, M2) ────────────────────────────────────────

test('server distance: the jump to a re-anchored trajectory is 0; walked steps are measured between kept fixes', () => {
  const r = filterDeviceFixes([fix(400, 0), ...walk(5)], null, NOW)
  assert.equal(r.distanceMeters[1], 0, 'first fix after the re-anchor pays nothing for the 400 m jump')
  for (const i of [2, 3, 4, 5]) assert.ok(Math.abs(r.distanceMeters[i] - 1.4) < 0.01, `step ${i}`)
  assert.equal(r.distanceMeters[0], undefined, 'the dropped stale fix pays nothing')
})

test('server distance: after a dropped spike the next kept fix is measured from the last kept one', () => {
  const r = filterDeviceFixes([fix(0, 0), fix(1.4, 1), fix(80, 2), fix(4.2, 3)], null, NOW)
  assert.equal(r.distanceMeters[0], 0, 'no predecessor')
  assert.ok(Math.abs(r.distanceMeters[3] - 2.8) < 0.01)
})

test('server distance: continues from the stored anchor across batches', () => {
  const anchor = { lat: LAT, lng: LNG, capturedAtMs: T0 }
  const r = filterDeviceFixes([fix(1.4, 1)], anchor, NOW)
  assert.ok(Math.abs(r.distanceMeters[0] - 1.4) < 0.01)
})

test('fixDistanceMeters uses the frozen grid constants (docs/CONVENTIONS.md §2)', () => {
  assert.equal(Math.round(fixDistanceMeters({ lat: 0, lng: 0 }, { lat: 1, lng: 0 })), 111000)
  assert.equal(Math.round(fixDistanceMeters({ lat: 0, lng: 0 }, { lat: 0, lng: 1 })), 82300)
})

test('suspect: needs both enough dropped fixes and a high dropped share', () => {
  assert.equal(isGpsSuspect(1000, GPS_SUSPECT_MIN_DROPPED - 1), false, 'too few drops')
  assert.equal(isGpsSuspect(5, 9), false, 'high share but under the minimum count')
  assert.equal(isGpsSuspect(100, 10), false, '9% dropped')
  assert.equal(isGpsSuspect(40, 10), true, '20% dropped')
  assert.equal(isGpsSuspect(0, 0), false)
})
