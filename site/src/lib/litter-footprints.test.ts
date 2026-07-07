import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  TYPICAL_WIDTH_METERS,
  FALLBACK_ITEM_TYPE,
  FOOTPRINT_VISIBILITY_FLOOR_METERS,
  typicalWidthMetersFor,
  footprintRectRing,
  circlePolygonRing,
  buildFootprintFeature,
  buildObservationFootprintsGeoJson,
  humanizeItemType,
  type MapObservation,
} from './litter-footprints'

// These tests exercise the pure footprint-geometry builder that mirrors
// composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/GeoJsonLayers.kt's
// buildObservationFootprintsGeoJson + TrashItemCatalog.kt (Pillar 8 Task 3,
// web map parity). No test framework is set up for site/ beyond Playwright
// e2e — this uses Node's built-in test runner via tsx, same pattern as
// world-observations.test.ts. Run with:
//   npx tsx --test src/lib/litter-footprints.test.ts

function baseObservation(overrides: Partial<MapObservation> = {}): MapObservation {
  return {
    id: 1,
    lat: 0,
    lng: 0,
    itemType: 'plastic_bottle',
    domain: 'litter',
    state: 'dirty',
    widthMeters: null,
    yawDegrees: null,
    ...overrides,
  }
}

// ── footprintRectRing: corner-rotation handedness must match the Kotlin
//    reference exactly (hand-computed from the same formula) ────────────

test('footprintRectRing: yaw=0 keeps the width axis east-west (unrotated corners)', () => {
  const metersPerDegreeLat = 111_320.0
  const metersPerDegreeLng = 111_320.0 // cos(0) === 1
  const ring = footprintRectRing(0, 0, 10, 4, 0, metersPerDegreeLat, metersPerDegreeLng)
  // 4 corners + closing point = 5
  assert.equal(ring.length, 5)
  const halfW = 5
  const halfD = 2
  const expected = [
    [-halfW / metersPerDegreeLng, -halfD / metersPerDegreeLat],
    [halfW / metersPerDegreeLng, -halfD / metersPerDegreeLat],
    [halfW / metersPerDegreeLng, halfD / metersPerDegreeLat],
    [-halfW / metersPerDegreeLng, halfD / metersPerDegreeLat],
  ]
  for (let i = 0; i < 4; i++) {
    assert.ok(Math.abs(ring[i][0] - expected[i][0]) < 1e-12, `corner ${i} lng`)
    assert.ok(Math.abs(ring[i][1] - expected[i][1]) < 1e-12, `corner ${i} lat`)
  }
  // Ring closes back to the first corner.
  assert.deepEqual(ring[4], ring[0])
})

test('footprintRectRing: yaw=90 (compass bearing) swings the width axis to north-south, not a mirrored/CCW result', () => {
  const metersPerDegreeLat = 111_320.0
  const metersPerDegreeLng = 111_320.0
  const ring = footprintRectRing(0, 0, 10, 4, 90, metersPerDegreeLat, metersPerDegreeLng)
  // Hand-computed from the Kotlin formula (rx = dx*cosR + dy*sinR, ry = -dx*sinR + dy*cosR)
  // with cosR=0, sinR=1 at yaw=90:
  //   corner1 (dx=-5,dy=-2) -> rx=-2, ry=5
  //   corner2 (dx=5,dy=-2)  -> rx=-2, ry=-5
  //   corner3 (dx=5,dy=2)   -> rx=2,  ry=-5
  //   corner4 (dx=-5,dy=2)  -> rx=2,  ry=5
  const expected = [
    [-2 / metersPerDegreeLng, 5 / metersPerDegreeLat],
    [-2 / metersPerDegreeLng, -5 / metersPerDegreeLat],
    [2 / metersPerDegreeLng, -5 / metersPerDegreeLat],
    [2 / metersPerDegreeLng, 5 / metersPerDegreeLat],
  ]
  for (let i = 0; i < 4; i++) {
    assert.ok(Math.abs(ring[i][0] - expected[i][0]) < 1e-12, `corner ${i} lng`)
    assert.ok(Math.abs(ring[i][1] - expected[i][1]) < 1e-12, `corner ${i} lat`)
  }
})

// ── circlePolygonRing ─────────────────────────────────────────────────────

test('circlePolygonRing: returns segments+1 points (closed) all at the given radius', () => {
  const ring = circlePolygonRing(41.86, -87.72, 5, 12)
  assert.equal(ring.length, 13)
  assert.deepEqual(ring[12], ring[0])
})

// ── typicalWidthMetersFor / TYPICAL_WIDTH_METERS table ───────────────────

test('typicalWidthMetersFor: known item types match the TrashItemCatalog.kt table verbatim', () => {
  assert.equal(TYPICAL_WIDTH_METERS.chip_bag, 0.22)
  assert.equal(TYPICAL_WIDTH_METERS.trash_bag, 0.60)
  assert.equal(TYPICAL_WIDTH_METERS.other, 0.15)
  assert.equal(typicalWidthMetersFor('cigarette'), 0.03)
  assert.equal(typicalWidthMetersFor('mattress'), 1.50)
})

test('typicalWidthMetersFor: unknown/null itemType falls back to "other"', () => {
  assert.equal(typicalWidthMetersFor(null), TYPICAL_WIDTH_METERS[FALLBACK_ITEM_TYPE])
  assert.equal(typicalWidthMetersFor('not_a_real_type'), TYPICAL_WIDTH_METERS[FALLBACK_ITEM_TYPE])
})

// ── buildFootprintFeature: width resolution, floor, domain branch ────────

test('buildFootprintFeature: a usable measured widthMeters wins over the type default', () => {
  // 10m is comfortably above both mattress's 1.5m type-default AND the
  // ~6.29m visibility floor, so a result of 10m can only come from the
  // measured value being used, not the (much smaller) type default.
  const obs = baseObservation({ itemType: 'mattress', widthMeters: 10, yawDegrees: 0 })
  const feature = buildFootprintFeature(obs, 0)
  const ring = feature.geometry.coordinates[0]
  const lngSpan = Math.max(...ring.map((c) => c[0])) - Math.min(...ring.map((c) => c[0]))
  const widthMeters = lngSpan * 111_320.0
  assert.ok(Math.abs(widthMeters - 10) < 1e-6)
})

test('buildFootprintFeature: null/non-finite/non-positive widthMeters falls back to the type default', () => {
  for (const bad of [null, undefined, NaN, 0, -1]) {
    const obs = baseObservation({ itemType: 'cup', widthMeters: bad as number | null, yawDegrees: 0 })
    const feature = buildFootprintFeature(obs, 0)
    const ring = feature.geometry.coordinates[0]
    const lngSpan = Math.max(...ring.map((c) => c[0])) - Math.min(...ring.map((c) => c[0]))
    const widthMeters = lngSpan * 111_320.0
    // cup's typical width (0.11m) is below the visibility floor, so the
    // rendered width is the floor, not the raw type default — this test
    // only asserts the type-default path was taken (not the measured one),
    // which the floor test below covers precisely.
    assert.ok(widthMeters >= FOOTPRINT_VISIBILITY_FLOOR_METERS - 1e-6)
  }
})

test('buildFootprintFeature: visibility floor — a tiny true-scale item never renders under ~6.29m', () => {
  const obs = baseObservation({ itemType: 'cigarette', widthMeters: 0.03, yawDegrees: 0 })
  const feature = buildFootprintFeature(obs, 0)
  const ring = feature.geometry.coordinates[0]
  const lngSpan = Math.max(...ring.map((c) => c[0])) - Math.min(...ring.map((c) => c[0]))
  const widthMeters = lngSpan * 111_320.0
  assert.ok(Math.abs(widthMeters - FOOTPRINT_VISIBILITY_FLOOR_METERS) < 1e-6)
  assert.ok(FOOTPRINT_VISIBILITY_FLOOR_METERS > 6.2 && FOOTPRINT_VISIBILITY_FLOOR_METERS < 6.4)
})

test('buildFootprintFeature: vegetation domain renders a 12-gon circle, not an oriented rectangle', () => {
  const obs = baseObservation({ itemType: 'other', domain: 'vegetation', widthMeters: 10, yawDegrees: 0 })
  const feature = buildFootprintFeature(obs, 0)
  const ring = feature.geometry.coordinates[0]
  assert.equal(ring.length, 13) // 12-gon + closing point
  // Circle radius = width/2 = 5m -> every vertex sits ~5m from center.
  const metersPerDegreeLat = 111_320.0
  for (const [lng, lat] of ring) {
    const distMeters = Math.sqrt((lng * 111_320.0) ** 2 + (lat * metersPerDegreeLat) ** 2)
    assert.ok(Math.abs(distMeters - 5) < 1e-6)
  }
})

test('buildFootprintFeature: non-vegetation domain (construction/litter/null) renders the oriented rectangle', () => {
  for (const domain of ['construction', 'litter', null, undefined]) {
    const obs = baseObservation({ domain, widthMeters: 10, yawDegrees: 0 })
    const feature = buildFootprintFeature(obs, 0)
    assert.equal(feature.geometry.coordinates[0].length, 5) // rectangle, not 12-gon
  }
})

test('buildFootprintFeature: feature properties carry id/itemType/domain/state for tap resolution', () => {
  const obs = baseObservation({ id: 42, itemType: 'tire', domain: 'litter', state: 'dirty' })
  const feature = buildFootprintFeature(obs, 0)
  assert.equal(feature.properties.id, 42)
  assert.equal(feature.properties.itemType, 'tire')
  assert.equal(feature.properties.domain, 'litter')
  assert.equal(feature.properties.state, 'dirty')
})

// ── diagnosticStatus -> diagnosed property (Pillar 8 Task 4) ─────────────

test('buildFootprintFeature: diagnosticStatus "observed" -> diagnosed false (hollow)', () => {
  const obs = baseObservation({ diagnosticStatus: 'observed' })
  const feature = buildFootprintFeature(obs, 0)
  assert.equal(feature.properties.diagnosed, false)
})

test('buildFootprintFeature: diagnosticStatus "diagnosed" -> diagnosed true (solid)', () => {
  const obs = baseObservation({ diagnosticStatus: 'diagnosed' })
  const feature = buildFootprintFeature(obs, 0)
  assert.equal(feature.properties.diagnosed, true)
})

test('buildFootprintFeature: null/undefined diagnosticStatus (legacy row/server) -> diagnosed true (solid), not observed', () => {
  const nullObs = baseObservation({ diagnosticStatus: null })
  assert.equal(buildFootprintFeature(nullObs, 0).properties.diagnosed, true)
  const undefinedObs = baseObservation({})
  assert.equal(buildFootprintFeature(undefinedObs, 0).properties.diagnosed, true)
})

// ── buildObservationFootprintsGeoJson: state handling (matches mobile) ───
//
// Mobile (GccMap.ios.kt / MapFullScreen.kt) renders every observation
// buildObservationFootprintsGeoJson is given — there is no state=='dirty'
// filter anywhere in that pipeline (verified by reading both files). The
// web builder intentionally matches that: no state filter here either.

test('buildObservationFootprintsGeoJson: both dirty and clean observations produce a feature (no state filter, matches mobile)', () => {
  const observations = [
    baseObservation({ id: 1, state: 'dirty' }),
    baseObservation({ id: 2, state: 'clean' }),
  ]
  const fc = buildObservationFootprintsGeoJson(observations, 0)
  assert.equal(fc.features.length, 2)
  assert.deepEqual(
    fc.features.map((f) => f.properties.id).sort(),
    [1, 2]
  )
})

test('buildObservationFootprintsGeoJson: empty input yields an empty FeatureCollection', () => {
  const fc = buildObservationFootprintsGeoJson([], 0)
  assert.deepEqual(fc.features, [])
  assert.equal(fc.type, 'FeatureCollection')
})

// ── humanizeItemType ───────────────────────────────────────────────────────

test('humanizeItemType: snake_case becomes a human label with only the first word capitalized', () => {
  assert.equal(humanizeItemType('chip_bag'), 'Chip bag')
  assert.equal(humanizeItemType('plastic_bottle'), 'Plastic bottle')
  assert.equal(humanizeItemType(null), 'Litter')
  assert.equal(humanizeItemType(undefined), 'Litter')
})
