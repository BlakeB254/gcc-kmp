import { test } from 'node:test'
import assert from 'node:assert/strict'
import { getTableName } from 'drizzle-orm'
import {
  gridCell,
  parseCoveredCellIds,
  selectReportsInStaleCells,
  resolveByCoveredCells,
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
