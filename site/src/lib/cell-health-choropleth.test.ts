import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  aggregateCellHealth,
  buildChoroplethGeoJson,
  choroplethTileMetersForZoom,
  type CoverageCellInput,
  type ObservationInput,
  type SpotInput,
} from './cell-health-choropleth'

// Mirrors composeApp/.../CellHealthChoroplethTest.kt. Run with:
//   npx tsx --test src/lib/cell-health-choropleth.test.ts

function cell(overrides: Partial<CoverageCellInput> = {}): CoverageCellInput {
  return { lat: 41.86, lng: -87.72, state: 'fresh', quality: 'close', ...overrides }
}

function dirtyObs(overrides: Partial<ObservationInput> = {}): ObservationInput {
  return { lat: 41.86, lng: -87.72, state: 'dirty', ...overrides }
}

function spot(overrides: Partial<SpotInput> = {}): SpotInput {
  return { coordinates: [-87.72, 41.86], status: 'verified', severity: 5, ...overrides }
}

test('empty inputs produce no tiles', () => {
  assert.deepEqual(aggregateCellHealth([], [], [], 100), [])
})

test('non-positive tile size produces no tiles', () => {
  assert.deepEqual(aggregateCellHealth([cell()], [], [], 0), [])
})

test('single clean cell produces a fully green tile', () => {
  const tiles = aggregateCellHealth([cell({ quality: 'close', state: 'fresh' })], [], [], 100)
  assert.equal(tiles.length, 1)
  assert.ok(Math.abs(tiles[0].health - 1.0) < 1e-4)
  assert.equal(tiles[0].dominant, 'clean')
})

test('cell with heavy litter produces a fully red tile', () => {
  const tiles = aggregateCellHealth(
    [cell({ quality: 'close', state: 'fresh' })],
    [dirtyObs({ estimatedWeightGrams: 6000 })],
    [],
    100
  )
  assert.equal(tiles.length, 1)
  assert.ok(Math.abs(tiles[0].health) < 1e-4)
  assert.equal(tiles[0].dominant, 'dirty')
})

test('mixed coverage and moderate litter produce a weighted middle tile', () => {
  const tiles = aggregateCellHealth(
    [
      cell({ lat: 41.86, lng: -87.72, state: 'fresh', quality: 'close' }),
      cell({ lat: 41.8601, lng: -87.7201, state: 'stale', quality: 'close' }),
    ],
    [dirtyObs({ lat: 41.86, lng: -87.72, estimatedWeightGrams: 1000 })],
    [],
    200
  )
  assert.equal(tiles.length, 1)
  assert.ok(Math.abs(tiles[0].health - 0.5625) < 1e-4)
  assert.ok(tiles[0].health > 0 && tiles[0].health < 1)
  assert.equal(tiles[0].dominant, 'stale')
})

test('bins with no data at all are excluded not fabricated', () => {
  const tiles = aggregateCellHealth([cell({ lat: 41.86, lng: -87.72 })], [], [], 100)
  assert.equal(tiles.length, 1)
})

test('clean-state observations do not create or affect tiles', () => {
  const tiles = aggregateCellHealth([], [{ lat: 41.9, lng: -87.8, state: 'clean' }], [], 100)
  assert.deepEqual(tiles, [])
})

test('unscanned bin with reported mess shows warm, not grey', () => {
  // Product feedback 2026-07-08: a reported/observed mess with no scan must
  // stay visible (warm, amber→red half), never the neutral grey veil.
  const tiles = aggregateCellHealth([], [dirtyObs({ estimatedWeightGrams: 200 })], [], 100)
  assert.equal(tiles.length, 1)
  assert.notEqual(tiles[0].dominant, 'unscanned')
  assert.ok(tiles[0].health >= 0.4 && tiles[0].health <= 0.5)
})

test('active spot report keeps a reported mess representable at far zoom', () => {
  // A verified high-severity community report with no coverage/observation
  // still produces a hot tile — reported messes stay visible until scanned.
  const tiles = aggregateCellHealth([], [], [spot({ status: 'verified', severity: 5 })], 100)
  assert.equal(tiles.length, 1)
  assert.equal(tiles[0].dominant, 'dirty')
  assert.ok(tiles[0].health < 0.2)
})

test('resolved spot reports are excluded', () => {
  const tiles = aggregateCellHealth([], [], [spot({ status: 'resolved', severity: 5 })], 100)
  assert.deepEqual(tiles, [])
})

test('scanned-clean cell near an active spot stays dirty, not clean', () => {
  const tiles = aggregateCellHealth(
    [cell({ lat: 41.86, lng: -87.72, state: 'fresh', quality: 'close' })],
    [],
    [spot({ coordinates: [-87.72, 41.86], status: 'verified', severity: 5 })],
    100
  )
  assert.equal(tiles.length, 1)
  assert.equal(tiles[0].dominant, 'dirty')
})

test('all-stale coverage with no litter is amber', () => {
  const tiles = aggregateCellHealth([cell({ state: 'stale', quality: 'close' })], [], [], 100)
  assert.equal(tiles.length, 1)
  assert.equal(tiles[0].dominant, 'stale')
  assert.ok(Math.abs(tiles[0].health - 0.5) < 1e-4)
})

test('two cells in the same bin merge into one tile', () => {
  const tiles = aggregateCellHealth(
    [cell({ lat: 41.86, lng: -87.72 }), cell({ lat: 41.8601, lng: -87.7201 })],
    [],
    [],
    400
  )
  assert.equal(tiles.length, 1)
})

test('choroplethTileMetersForZoom is monotonically non-increasing', () => {
  const zooms = [2, 5, 7, 9, 10, 12, 13, 14, 14.2]
  const sizes = zooms.map(choroplethTileMetersForZoom)
  for (let i = 1; i < sizes.length; i++) {
    assert.ok(sizes[i] <= sizes[i - 1], `expected non-increasing: ${sizes}`)
  }
  assert.ok(sizes.every((s) => s > 0))
})

test('buildChoroplethGeoJson emits a closed square per tile with props', () => {
  const tiles = aggregateCellHealth([cell()], [], [], 100)
  const fc = buildChoroplethGeoJson(tiles, 100)
  assert.equal(fc.features.length, 1)
  const f = fc.features[0]
  assert.equal(f.properties.dominant, 'clean')
  const ring = f.geometry.coordinates[0]
  assert.equal(ring.length, 5)
  assert.deepEqual(ring[0], ring[4])
})
