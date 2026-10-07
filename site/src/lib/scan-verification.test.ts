import assert from 'node:assert/strict'
import test from 'node:test'

import { gridCell } from './grid-cell-client'
import { validateLapOneProof } from './mobile-route-scan-response'
import {
  LAP_ONE_MIN_ROUTE_COVERAGE,
  SCAN_COMPLETION_MIN_ROUTE_COVERAGE,
  attributeCoverageEvent,
  computeRouteCoverage,
  coverageRefusal,
  isScanAlreadyComplete,
  lapOneGate,
  pickQualifyingScan,
  routeLines,
  routeRequiredCells,
  scanCompletionRefusal,
  scanQualifiesForJob,
  supersedableCells,
  type ScanForJob,
} from './scan-verification'

// A ~165m east-west street segment in North Lawndale.
const LAT = 41.8601
const LNG_START = -87.7202
const LNG_END = -87.7182
const ROUTE = { type: 'LineString', coordinates: [[LNG_START, LAT], [LNG_END, LAT]] }

/** Cells a worker walking parallel to the route, `offsetMeters` north of it, would log (one fix per ~1.5m). */
function walk(offsetMeters: number, fromFraction = 0, toFraction = 1): Set<string> {
  const cells = new Set<string>()
  const lat = LAT + offsetMeters / 111_000
  for (let f = fromFraction; f <= toFraction; f += 0.005) {
    cells.add(gridCell(lat, LNG_START + (LNG_END - LNG_START) * f))
  }
  return cells
}

// ── Route cells ─────────────────────────────────────────────────────────────

test('routeLines: LineString, Feature, Polygon ring and bare arrays all parse; junk yields nothing', () => {
  assert.equal(routeLines(ROUTE).length, 1)
  assert.equal(routeLines({ type: 'Feature', geometry: ROUTE }).length, 1)
  assert.equal(routeLines({ type: 'Polygon', coordinates: [[[0, 0], [0, 1], [1, 1], [0, 0]]] }).length, 1)
  assert.equal(routeLines([[LNG_START, LAT], [LNG_END, LAT]]).length, 1)
  assert.deepEqual(routeLines(null), [])
  assert.deepEqual(routeLines({ type: 'Point', coordinates: [0, 0] }), [])
  assert.equal(routeRequiredCells('not geometry').size, 0)
})

test('routeRequiredCells: every 10m gridCell the line passes through, nothing else', () => {
  const cells = routeRequiredCells(ROUTE)
  // ~165m east-west at 10m cells → ~17 cells in one row.
  assert.ok(cells.size >= 16 && cells.size <= 19, `got ${cells.size}`)
  const rows = new Set([...cells].map((c) => c.split(':')[0]))
  assert.equal(rows.size, 1, 'a straight east-west line stays in one cell row')
  assert.ok(cells.has(gridCell(LAT, LNG_START)))
  assert.ok(cells.has(gridCell(LAT, LNG_END)))
})

// ── Coverage ────────────────────────────────────────────────────────────────

test('coverage: walking the sidewalk (8m off the route line) covers the route', () => {
  const coverage = computeRouteCoverage(routeRequiredCells(ROUTE), walk(8))
  assert.equal(coverage.ratio, 1)
  assert.equal(coverageRefusal(coverage), null)
})

test('coverage: walking a parallel street 40m away covers none of it → 409', () => {
  const coverage = computeRouteCoverage(routeRequiredCells(ROUTE), walk(40))
  assert.equal(coverage.covered, 0)
  assert.equal(coverageRefusal(coverage)?.status, 409)
})

test(`coverage below the ${SCAN_COMPLETION_MIN_ROUTE_COVERAGE} threshold → 409; at/above → allowed`, () => {
  const required = routeRequiredCells(ROUTE)
  const half = computeRouteCoverage(required, walk(0, 0, 0.5))
  assert.ok(half.ratio < SCAN_COMPLETION_MIN_ROUTE_COVERAGE)
  const refusal = coverageRefusal(half)
  assert.equal(refusal?.status, 409)
  assert.match(refusal!.message, /90% is required/)

  const most = computeRouteCoverage(required, walk(0, 0, 0.95))
  assert.ok(most.ratio >= SCAN_COMPLETION_MIN_ROUTE_COVERAGE, `ratio ${most.ratio}`)
  assert.equal(coverageRefusal(most), null)
})

test('coverage: no worked cells at all (empty-body submit with no coverage events) → 409', () => {
  const coverage = computeRouteCoverage(routeRequiredCells(ROUTE), new Set())
  assert.equal(coverage.ratio, 0)
  assert.equal(coverageRefusal(coverage)?.status, 409)
})

test('coverage: a route with no geometry can never be verified → 409', () => {
  const coverage = computeRouteCoverage(new Set(), walk(0))
  assert.equal(coverage.required, 0)
  assert.equal(coverageRefusal(coverage)?.status, 409)
})

// ── State machine ───────────────────────────────────────────────────────────

const VERIFIED_LAP_ONE = '2026-10-07T10:00:00.000Z'

test('complete from scouting (a fresh POST /api/scans + empty-body complete) → 409', () => {
  const refusal = scanCompletionRefusal({ phase: 'scouting', status: 'in_progress', lapOneVerifiedAt: null })
  assert.equal(refusal?.status, 409)
  assert.match(refusal!.message, /cleaning lap/)
})

test('complete from scout_review (wrong phase) → 409 even with lap-one proof', () => {
  const refusal = scanCompletionRefusal({ phase: 'scout_review', status: 'in_progress', lapOneVerifiedAt: VERIFIED_LAP_ONE })
  assert.equal(refusal?.status, 409)
})

test('complete from cleaning without recorded lap-one proof → 409', () => {
  const refusal = scanCompletionRefusal({ phase: 'cleaning', status: 'in_progress', lapOneVerifiedAt: null })
  assert.equal(refusal?.status, 409)
  assert.match(refusal!.message, /Lap 1 proof/)
})

test('abandoned scans cannot be completed', () => {
  assert.equal(
    scanCompletionRefusal({ phase: 'cleaning', status: 'abandoned', lapOneVerifiedAt: VERIFIED_LAP_ONE })?.status,
    409,
  )
})

test('cleaning (or the submitting step after it) with lap-one proof passes the state gate', () => {
  assert.equal(scanCompletionRefusal({ phase: 'cleaning', status: 'in_progress', lapOneVerifiedAt: VERIFIED_LAP_ONE }), null)
  assert.equal(scanCompletionRefusal({ phase: 'submitting', status: 'in_progress', lapOneVerifiedAt: VERIFIED_LAP_ONE }), null)
})

test('an already-complete scan is recognised (the route answers a retry idempotently)', () => {
  assert.equal(isScanAlreadyComplete({ phase: 'complete', status: 'completed' }), true)
  assert.equal(isScanAlreadyComplete({ phase: 'cleaning', status: 'in_progress' }), false)
})

test('lap-one gate: missing proof at scouting → scout_review is refused (it used to pass)', () => {
  const full = computeRouteCoverage(routeRequiredCells(ROUTE), walk(0))
  const missing = validateLapOneProof({ phase: 'scout_review' }, false)
  assert.equal(missing, null, 'precondition: no proof fields → null')
  assert.equal(lapOneGate('scouting', 'scout_review', missing, full), 'refused-proof')
  assert.equal(lapOneGate('scouting', 'scout_review', validateLapOneProof(null, false), full), 'refused-proof')
  assert.equal(
    lapOneGate('scouting', 'scout_review', validateLapOneProof({ coveragePercent: 60, closeCoveragePercent: 0, loopClosed: true }, false), full),
    'refused-proof',
    'the client proof is still required as a consistency check',
  )
  assert.equal(lapOneGate('scout_review', 'cleaning', null, null), 'not-boundary')
})

test('lap-one gate: a client claiming 96% with no attributed events is refused — the stamp needs the SERVER ratio', () => {
  const claims96 = validateLapOneProof({ coveragePercent: 96, closeCoveragePercent: 0, loopClosed: true }, false)
  assert.equal(claims96, true, 'precondition: the client numbers alone pass')
  const required = routeRequiredCells(ROUTE)
  assert.equal(lapOneGate('scouting', 'scout_review', claims96, computeRouteCoverage(required, new Set())), 'refused-coverage')
  assert.equal(lapOneGate('scouting', 'scout_review', claims96, null), 'refused-coverage', 'not computed → refused')
  assert.equal(
    lapOneGate('scouting', 'scout_review', claims96, computeRouteCoverage(required, walk(0, 0, 0.5))),
    'refused-coverage',
    'half the route',
  )
  assert.equal(
    lapOneGate('scouting', 'scout_review', claims96, computeRouteCoverage(new Set(), walk(0))),
    'refused-coverage',
    'a route with no geometry can never verify',
  )
  assert.equal(lapOneGate('scouting', 'scout_review', claims96, computeRouteCoverage(required, walk(0))), 'verified')
})

test('lap-one threshold is the completion threshold (the scan-and-coverage doc defines no separate server lap-one figure)', () => {
  assert.equal(LAP_ONE_MIN_ROUTE_COVERAGE, SCAN_COMPLETION_MIN_ROUTE_COVERAGE)
})

// ── Coverage attribution ────────────────────────────────────────────────────

test('attributeCoverageEvent: route-mode events at/after the active scan start get its id; nothing else does', () => {
  const active = { id: 42, scoutStartedAt: '2026-10-07T09:00:00.000Z' }
  assert.equal(attributeCoverageEvent({ scanMode: 'route', capturedAt: '2026-10-07T09:00:00.000Z' }, active), 42)
  assert.equal(attributeCoverageEvent({ scanMode: 'route', capturedAt: '2026-10-07T09:30:00Z' }, active), 42)
  assert.equal(attributeCoverageEvent({ scanMode: 'spot', capturedAt: '2026-10-07T09:30:00Z' }, active), null, 'spot mode')
  assert.equal(attributeCoverageEvent({ scanMode: 'route', capturedAt: '2026-10-07T08:59:59Z' }, active), null, 'before the scan started')
  assert.equal(attributeCoverageEvent({ scanMode: 'route', capturedAt: 'not a date' }, active), null)
  assert.equal(attributeCoverageEvent({ scanMode: 'route', capturedAt: '2026-10-07T09:30:00Z' }, null), null, 'no active scan')
})

// ── Community-report supersede ──────────────────────────────────────────────

test('supersede: client coveredCellIds are narrowed to cells the server saw this scan work on this route', () => {
  const required = routeRequiredCells(ROUTE)
  const worked = walk(0)
  const onRouteWorked = [...worked][3]
  const farAwayCell = gridCell(41.9, -87.6) // someone else's block
  const onRouteNotWorked = [...required].find((c) => !worked.has(c)) ?? gridCell(LAT + 0.01, LNG_START)
  const workedOffRoute = gridCell(LAT + 200 / 111_000, LNG_START) // the server saw it, but it is not this route
  const serverWorked = new Set([...worked, workedOffRoute])

  const cells = supersedableCells([onRouteWorked, farAwayCell, onRouteNotWorked, workedOffRoute, onRouteWorked], serverWorked, required)
  assert.deepEqual(cells, [onRouteWorked])
})

// ── Job completion ──────────────────────────────────────────────────────────

const CLAIMED_AT = '2026-10-07T08:00:00.000Z'
const JOB = { id: 50, claimedAt: CLAIMED_AT, startedAt: '2026-10-07T08:30:00.000Z' }

function scan(overrides: Partial<ScanForJob> = {}): ScanForJob {
  return {
    id: 900,
    status: 'completed',
    phase: 'complete',
    verifiedCoverageRatio: 0.97,
    createdAt: '2026-10-07T09:00:00.000Z',
    usedByJobId: null,
    ...overrides,
  }
}

test('job: a verified scan created after the claim qualifies', () => {
  assert.equal(scanQualifiesForJob(scan(), JOB), true)
  assert.equal(scanQualifiesForJob(scan({ createdAt: CLAIMED_AT }), JOB), true, 'at the claim instant counts')
})

test('job: a scan from before the claim (an old scan) is refused', () => {
  assert.equal(scanQualifiesForJob(scan({ createdAt: '2026-10-06T09:00:00.000Z' }), JOB), false)
})

test('job: a scan already used to complete another job is refused; the same job may re-use its own', () => {
  assert.equal(scanQualifiesForJob(scan({ usedByJobId: 49 }), JOB), false)
  assert.equal(scanQualifiesForJob(scan({ usedByJobId: 50 }), JOB), true)
})

test('job: legacy completed scans without server verification, or below threshold, are refused', () => {
  assert.equal(scanQualifiesForJob(scan({ verifiedCoverageRatio: null }), JOB), false)
  assert.equal(scanQualifiesForJob(scan({ verifiedCoverageRatio: 0.5 }), JOB), false)
  assert.equal(scanQualifiesForJob(scan({ status: 'in_progress', phase: 'cleaning' }), JOB), false)
})

test('job: no claim or start time → nothing qualifies', () => {
  assert.equal(scanQualifiesForJob(scan(), { id: 50, claimedAt: null, startedAt: null }), false)
})

test('job: pickQualifyingScan skips old/used scans and returns the newest qualifying one', () => {
  const picked = pickQualifyingScan(
    [scan({ id: 3, usedByJobId: 49 }), scan({ id: 2 }), scan({ id: 1, createdAt: '2026-10-01T00:00:00.000Z' })],
    JOB,
  )
  assert.equal(picked?.id, 2)
  assert.equal(pickQualifyingScan([scan({ id: 3, usedByJobId: 49 })], JOB), null)
})
