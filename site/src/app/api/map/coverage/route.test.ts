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
