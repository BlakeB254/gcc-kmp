import { test } from 'node:test'
import assert from 'node:assert/strict'
import { getTableName } from 'drizzle-orm'
import {
  gridCell,
  parseCoveredCellIds,
  selectReportsInStaleCells,
  resolveByCoveredCells,
  mergeObservationAttributes,
  mergeObservationMediaUrl,
  computeDiagnosticStatus,
} from './world-observations'

// These tests exercise the Task 1 "supersede by covered cells" logic added
// to world-observations.ts. There's no established unit-test framework for
// site/ (only Playwright e2e), so this uses Node's built-in test runner via
// tsx — no new dependency. Run with:
//   DATABASE_URL=postgres://test:test@localhost/test npx tsx --test src/lib/world-observations.test.ts
// (DATABASE_URL just needs to be well-formed enough for `@/db`'s module-level
// `neon()` call to construct without connecting — these tests never touch a
// real database; `resolveByCoveredCells` takes an injectable fake dbClient.)

test('parseCoveredCellIds: valid array of cell ids passes through', () => {
  const ids = ['12:34', '-5:100', '0:0']
  assert.deepEqual(parseCoveredCellIds(ids), ids)
})

test('parseCoveredCellIds: absent field (old client) yields empty array', () => {
  assert.deepEqual(parseCoveredCellIds(undefined), [])
  assert.deepEqual(parseCoveredCellIds(null), [])
})

test('parseCoveredCellIds: non-array value is ignored, not an error', () => {
  assert.deepEqual(parseCoveredCellIds('12:34'), [])
  assert.deepEqual(parseCoveredCellIds({ foo: 'bar' }), [])
  assert.deepEqual(parseCoveredCellIds(42), [])
})

test('parseCoveredCellIds: malformed entries are dropped, well-formed ones kept', () => {
  const raw = ['12:34', 'not-a-cell', 42, null, '5:5:5', '-1:-1']
  assert.deepEqual(parseCoveredCellIds(raw), ['12:34', '-1:-1'])
})

test('parseCoveredCellIds: oversized array is truncated to 2000, not rejected', () => {
  const raw = Array.from({ length: 2500 }, (_, i) => `${i}:0`)
  const result = parseCoveredCellIds(raw)
  assert.equal(result.length, 2000)
  assert.deepEqual(result, raw.slice(0, 2000))
})

test('selectReportsInStaleCells: matches object-shape {lat,lng} coordinates in a stale cell', () => {
  const cell = gridCell(41.8, -87.7)
  const candidates = [{ id: 1, coordinates: { lat: 41.8, lng: -87.7 } }]
  assert.deepEqual(selectReportsInStaleCells(candidates, [cell]), [1])
})

test('selectReportsInStaleCells: matches array-shape [lng,lat] coordinates in a stale cell', () => {
  const cell = gridCell(41.8, -87.7)
  const candidates = [{ id: 2, coordinates: [-87.7, 41.8] }]
  assert.deepEqual(selectReportsInStaleCells(candidates, [cell]), [2])
})

test('selectReportsInStaleCells: a report outside every stale cell is not matched', () => {
  const staleCell = gridCell(41.8, -87.7)
  const farAwayCell = gridCell(42.5, -88.5)
  assert.notEqual(staleCell, farAwayCell)
  const candidates = [{ id: 3, coordinates: { lat: 42.5, lng: -88.5 } }]
  assert.deepEqual(selectReportsInStaleCells(candidates, [staleCell]), [])
})

test('selectReportsInStaleCells: malformed coordinates are skipped without throwing', () => {
  const staleCell = gridCell(41.8, -87.7)
  const candidates = [
    { id: 4, coordinates: null },
    { id: 5, coordinates: 'garbage' },
    { id: 6, coordinates: { lat: 'nope', lng: -87.7 } },
  ]
  assert.deepEqual(selectReportsInStaleCells(candidates, [staleCell]), [])
})

// ── resolveByCoveredCells: fake dbClient exercising both halves ──────────

function makeFakeDb(opts: {
  candidateReports: { id: number; coordinates: unknown }[]
  updatedReportIds?: number[]
  invalidatedObservationIds?: number[]
}) {
  const calls: Record<string, unknown[]> = {
    selectReports: [],
    updateReports: [],
    updateObservations: [],
  }
  const fakeDb = {
    select(_cols: unknown) {
      return {
        from(_table: unknown) {
          return {
            where(_cond: unknown) {
              calls.selectReports.push(_cond)
              return Promise.resolve(opts.candidateReports)
            },
          }
        },
      }
    },
    update(table: unknown) {
      // Distinguish which table is being updated via drizzle's own table
      // name introspection (the real communityReports/worldObservations
      // pgTable objects are passed in, not sentinels).
      const isReports = getTableName(table as any) === 'community_reports'
      return {
        set(_values: unknown) {
          return {
            where(_cond: unknown) {
              if (isReports) {
                calls.updateReports.push(_cond)
                return Promise.resolve(undefined)
              }
              calls.updateObservations.push(_cond)
              return {
                returning(_sel: unknown) {
                  return Promise.resolve(
                    (opts.invalidatedObservationIds ?? []).map((id) => ({ id }))
                  )
                },
              }
            },
          }
        },
      }
    },
  }
  return { fakeDb, calls }
}

test('resolveByCoveredCells: empty staleCells short-circuits with no-op result', async () => {
  const { fakeDb, calls } = makeFakeDb({ candidateReports: [] })
  const result = await resolveByCoveredCells([], 999, 'reason', fakeDb as any)
  assert.deepEqual(result, { resolvedReportIds: [], invalidatedObservationCount: 0 })
  assert.equal(calls.selectReports.length, 0)
})

test('resolveByCoveredCells: a stale (uncovered-by-this-submission) cell resolves its report and invalidates its observation', async () => {
  const staleCell = gridCell(41.8, -87.7)
  const { fakeDb } = makeFakeDb({
    candidateReports: [{ id: 10, coordinates: { lat: 41.8, lng: -87.7 } }],
    invalidatedObservationIds: [500],
  })
  const result = await resolveByCoveredCells([staleCell], 999, 'Superseded by scan report #999', fakeDb as any)
  assert.deepEqual(result.resolvedReportIds, [10])
  assert.equal(result.invalidatedObservationCount, 1)
})

test('resolveByCoveredCells: a report in a re-confirmed cell (excluded from staleCells) is not touched', async () => {
  const reconfirmedCell = gridCell(41.9, -87.6)
  const staleCell = gridCell(41.8, -87.7)
  // Simulate the caller having already excluded reconfirmedCell from
  // staleCells (this is the route's job) — the report living there should
  // never even be a candidate match once staleCells excludes its cell.
  const { fakeDb } = makeFakeDb({
    candidateReports: [{ id: 11, coordinates: { lat: 41.9, lng: -87.6 } }],
  })
  const result = await resolveByCoveredCells([staleCell], 999, 'reason', fakeDb as any)
  assert.deepEqual(result.resolvedReportIds, [])
  assert.notEqual(reconfirmedCell, staleCell)
})

// Amendment 2 / Task 1b: scan submissions no longer insert a communityReports
// row, so there is nothing for the sweep to exclude by id — callers on that
// path pass `undefined` instead of a real report id.
test('resolveByCoveredCells: excludeReportId is optional (undefined) — scan-path callers with no report row still work', async () => {
  const staleCell = gridCell(41.8, -87.7)
  const { fakeDb } = makeFakeDb({
    candidateReports: [{ id: 10, coordinates: { lat: 41.8, lng: -87.7 } }],
    invalidatedObservationIds: [501],
  })
  const result = await resolveByCoveredCells([staleCell], undefined, 'Superseded by scan sweep', fakeDb as any)
  assert.deepEqual(result.resolvedReportIds, [10])
  assert.equal(result.invalidatedObservationCount, 1)
})

// ── computeDiagnosticStatus / mergeObservationAttributes: Task 4 litter-
// truth lifecycle (observed -> diagnosed, completion upgrades). These are
// the pure functions recordWorldObservation composes — merge THEN compute
// diagnosticStatus from the merged result — kept side-effect-free (no DB
// access) so the CRITICAL ordering/persistence semantics are directly
// unit-testable, same rationale as selectReportsInStaleCells above.

test('computeDiagnosticStatus: weight + width attrs together -> diagnosed', () => {
  assert.equal(
    computeDiagnosticStatus({ estimatedWeightGrams: 500, estimatedWidthMeters: 0.3 }),
    'diagnosed'
  )
})

test('computeDiagnosticStatus: weight OR width alone (not both) -> observed', () => {
  assert.equal(computeDiagnosticStatus({ estimatedWeightGrams: 500 }), 'observed')
  assert.equal(computeDiagnosticStatus({ estimatedWidthMeters: 0.3 }), 'observed')
})

test('computeDiagnosticStatus: any objectCapture* attribute -> diagnosed', () => {
  assert.equal(computeDiagnosticStatus({ objectCaptureWeightGrams: 900 }), 'diagnosed')
  assert.equal(computeDiagnosticStatus({ objectCaptureR2Key: 'r2/foo.ply' }), 'diagnosed')
})

test('computeDiagnosticStatus: bare detection (severity only, or no attrs) -> observed', () => {
  assert.equal(computeDiagnosticStatus({ severity: 3 }), 'observed')
  assert.equal(computeDiagnosticStatus(null), 'observed')
  assert.equal(computeDiagnosticStatus(undefined), 'observed')
  assert.equal(computeDiagnosticStatus({}), 'observed')
})

test('computeDiagnosticStatus: non-finite weight/width does not count as a close-range measurement', () => {
  assert.equal(
    computeDiagnosticStatus({ estimatedWeightGrams: NaN, estimatedWidthMeters: 0.3 }),
    'observed'
  )
})

test('mergeObservationAttributes: completion upgrade — prior observed-only row + new close-confirm merges weight+width in, then computes diagnosed', () => {
  const prior = { severity: 3 }
  const incoming = { estimatedWeightGrams: 450, estimatedWidthMeters: 0.22 }
  const merged = mergeObservationAttributes(prior, incoming)
  assert.deepEqual(merged, { severity: 3, estimatedWeightGrams: 450, estimatedWidthMeters: 0.22 })
  assert.equal(computeDiagnosticStatus(merged), 'diagnosed')
})

test('mergeObservationAttributes: persistence — prior diagnosed row + new lean re-scan retains old weight+width, stays diagnosed', () => {
  const prior = { estimatedWeightGrams: 450, estimatedWidthMeters: 0.22 }
  const incoming = { severity: 4 } // lean re-scan: no size data, just a fresher severity
  const merged = mergeObservationAttributes(prior, incoming)
  assert.deepEqual(merged, { estimatedWeightGrams: 450, estimatedWidthMeters: 0.22, severity: 4 })
  assert.equal(computeDiagnosticStatus(merged), 'diagnosed')
})

test('mergeObservationAttributes: new keys win over inherited ones', () => {
  const prior = { estimatedWeightGrams: 100 }
  const incoming = { estimatedWeightGrams: 200 }
  assert.deepEqual(mergeObservationAttributes(prior, incoming), { estimatedWeightGrams: 200 })
})

test('mergeObservationAttributes: no prior row (first observation at this cell+itemType) — incoming attributes pass through unchanged', () => {
  assert.deepEqual(mergeObservationAttributes(null, { severity: 2 }), { severity: 2 })
  assert.deepEqual(mergeObservationAttributes(undefined, undefined), undefined)
})

// ── mergeObservationMediaUrl: evidence-photo supersede policy ──────────────
// The 2026-07-29 field bug: a submit whose photo upload failed superseded
// photo-bearing marker rows with mediaUrl NULL — the map LOST photos it
// already had. Photoless re-files must inherit; real photos must win.

test('mergeObservationMediaUrl: photoless re-file inherits the prior photo', () => {
  assert.equal(mergeObservationMediaUrl('https://r2/prior.jpg', null), 'https://r2/prior.jpg')
  assert.equal(mergeObservationMediaUrl('https://r2/prior.jpg', undefined), 'https://r2/prior.jpg')
})

test('mergeObservationMediaUrl: incoming photo wins over the prior one', () => {
  assert.equal(mergeObservationMediaUrl('https://r2/prior.jpg', 'https://r2/new.jpg'), 'https://r2/new.jpg')
})

test('mergeObservationMediaUrl: no photo anywhere stays undefined (not null)', () => {
  assert.equal(mergeObservationMediaUrl(null, null), undefined)
  assert.equal(mergeObservationMediaUrl(undefined, undefined), undefined)
})

test('mergeObservationMediaUrl: first photo lands on a previously photoless row', () => {
  assert.equal(mergeObservationMediaUrl(null, 'https://r2/new.jpg'), 'https://r2/new.jpg')
})
