import { test } from 'node:test'
import assert from 'node:assert/strict'
import { computePriority, applyObservedOnlyBoost } from './route'

// Pillar 8 Task 4 — observed-only litter priority boost. computePriority
// and applyObservedOnlyBoost are pure/side-effect-free (no DB access), same
// pattern as site/src/lib/world-observations.test.ts. Run with:
//   DATABASE_URL=postgres://test:test@localhost/test npx tsx --test src/app/api/map/coverage/route.test.ts
// (DATABASE_URL just needs to be well-formed enough for `@/db`'s module-level
// `neon()` call to construct without connecting — these tests never touch a
// real database.)

test('computePriority: fresh close-quality cell has zero priority (no age, low quality boost)', () => {
  assert.equal(computePriority(0, 'close'), 0)
})

test('computePriority: fully stale (>=7d) grazed cell saturates at the grazed quality boost (1.0)', () => {
  const sevenDaysMs = 7 * 24 * 60 * 60 * 1000
  assert.equal(computePriority(sevenDaysMs, 'grazed'), 1.0)
})

test('computePriority: age beyond 7d does not exceed the saturated ageFactor of 1.0', () => {
  const fourteenDaysMs = 14 * 24 * 60 * 60 * 1000
  assert.equal(computePriority(fourteenDaysMs, 'seen'), computePriority(7 * 24 * 60 * 60 * 1000, 'seen'))
})

test('applyObservedOnlyBoost: a cell in the observed-only set gets +0.25', () => {
  const observedOnlyCells = new Set(['1:2'])
  assert.equal(applyObservedOnlyBoost(0.3, '1:2', observedOnlyCells), 0.55)
})

test('applyObservedOnlyBoost: a cell NOT in the observed-only set gets no boost', () => {
  const observedOnlyCells = new Set(['1:2'])
  assert.equal(applyObservedOnlyBoost(0.3, '9:9', observedOnlyCells), 0.3)
})

test('applyObservedOnlyBoost: an empty observed-only set never boosts anything', () => {
  assert.equal(applyObservedOnlyBoost(0.7, '1:2', new Set()), 0.7)
})

test('applyObservedOnlyBoost: the boost clamps at 1.0 rather than overshooting', () => {
  const observedOnlyCells = new Set(['1:2'])
  assert.equal(applyObservedOnlyBoost(0.9, '1:2', observedOnlyCells), 1.0)
  assert.equal(applyObservedOnlyBoost(1.0, '1:2', observedOnlyCells), 1.0)
})

test('applyObservedOnlyBoost composed with computePriority: a fully-stale close cell (priority 0.6) boosted by observed-only litter reaches 0.85, not 1.0', () => {
  const sevenDaysMs = 7 * 24 * 60 * 60 * 1000
  const base = computePriority(sevenDaysMs, 'close')
  assert.equal(base, 0.6)
  const observedOnlyCells = new Set(['5:5'])
  assert.equal(applyObservedOnlyBoost(base, '5:5', observedOnlyCells), 0.85)
})

// ── D7 regression: the litter join must filter on `domain`, not just `state` ──
// `state='dirty'` is also set on vegetation and construction observations, so a
// state-only filter counts trees and fences as trash. These build the drizzle
// condition and inspect the generated SQL via `.toSQL()` — no query is executed.
import { db } from '@/db'
import { worldObservations } from '@/db/schema'
import { currentDirtyLitterInBbox, observedOnlyLitterInBbox, litterStatsFor } from './route'

// Inspect ONLY the WHERE clause — `select *` echoes every column name
// (including "domain" and "diagnostic_status"), so asserting against the full
// statement would pass no matter what the filter actually constrains.
function whereFor(cond: ReturnType<typeof currentDirtyLitterInBbox>) {
  const { sql, params } = db.select().from(worldObservations).where(cond).toSQL()
  const idx = sql.indexOf(' where ')
  assert.ok(idx >= 0, `expected a where clause, got: ${sql}`)
  return { where: sql.slice(idx), params }
}

test('currentDirtyLitterInBbox filters on domain = litter', () => {
  const { where, params } = whereFor(currentDirtyLitterInBbox(-87.8, 41.8, -87.6, 41.9))
  assert.ok(where.includes('"domain"'), `expected a domain predicate, got: ${where}`)
  assert.ok(params.includes('litter'), `expected 'litter' bound param, got: ${JSON.stringify(params)}`)
})

test('currentDirtyLitterInBbox still constrains state and is_current', () => {
  const { where, params } = whereFor(currentDirtyLitterInBbox(-87.8, 41.8, -87.6, 41.9))
  assert.ok(where.includes('"state"'))
  assert.ok(where.includes('"is_current"'))
  assert.ok(params.includes('dirty'))
})

test('currentDirtyLitterInBbox does NOT constrain diagnostic_status (diagnosed litter still counts)', () => {
  const { where } = whereFor(currentDirtyLitterInBbox(-87.8, 41.8, -87.6, 41.9))
  assert.ok(!where.includes('"diagnostic_status"'), `rollup must include diagnosed litter, got: ${where}`)
})

test('observedOnlyLitterInBbox narrows to observed AND still filters domain = litter', () => {
  const { where, params } = whereFor(observedOnlyLitterInBbox(-87.8, 41.8, -87.6, 41.9))
  assert.ok(where.includes('"diagnostic_status"'))
  assert.ok(where.includes('"domain"'), 'the observed-only boost must not count trees as litter')
  assert.ok(params.includes('litter'))
  assert.ok(params.includes('observed'))
})

test('litterStatsFor: a cell with no observations yields zeroes, not undefined', () => {
  assert.deepEqual(litterStatsFor('1:2', new Map()), { litterCount: 0, litterWeightGrams: 0 })
})

test('litterStatsFor: returns the rolled-up stats for a known cell', () => {
  const stats = new Map([['1:2', { litterCount: 3, litterWeightGrams: 4200 }]])
  assert.deepEqual(litterStatsFor('1:2', stats), { litterCount: 3, litterWeightGrams: 4200 })
})
