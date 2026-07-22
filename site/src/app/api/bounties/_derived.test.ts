import { test } from 'node:test'
import assert from 'node:assert/strict'
import { resolveBountyTrigger, observationToBountySource } from './_derived'
import type { WorldObservationRow } from '@/db/schema'

// Amendment 2 / Task 1b, Change 3: bounty continuity — a bounty can now be
// seeded from EITHER a communityReports row OR a worldObservations row.
// These tests exercise the two pure helpers extracted from POST
// /api/bounties (no DB, no server) per the same pattern established for
// spots in spot-utils.test.ts / world-observations.test.ts. Run with:
//   npx tsx --test src/app/api/bounties/_derived.test.ts

// ── resolveBountyTrigger ──────────────────────────────────────────────────

test('resolveBountyTrigger: rejects when neither trigger id is present', () => {
  const result = resolveBountyTrigger({})
  assert.equal(result.ok, false)
  if (!result.ok) assert.match(result.message, /exactly one/i)
})

test('resolveBountyTrigger: rejects when BOTH trigger ids are present', () => {
  const result = resolveBountyTrigger({ triggerReportId: 5, triggerObservationId: 9 })
  assert.equal(result.ok, false)
  if (!result.ok) assert.match(result.message, /exactly one/i)
})

test('resolveBountyTrigger: accepts a valid triggerReportId alone', () => {
  const result = resolveBountyTrigger({ triggerReportId: 5 })
  assert.deepEqual(result, { ok: true, triggerReportId: 5, triggerObservationId: null })
})

test('resolveBountyTrigger: accepts a valid triggerObservationId alone', () => {
  const result = resolveBountyTrigger({ triggerObservationId: 9 })
  assert.deepEqual(result, { ok: true, triggerReportId: null, triggerObservationId: 9 })
})

test('resolveBountyTrigger: rejects a non-positive-integer triggerReportId', () => {
  assert.equal(resolveBountyTrigger({ triggerReportId: 0 }).ok, false)
  assert.equal(resolveBountyTrigger({ triggerReportId: -1 }).ok, false)
  assert.equal(resolveBountyTrigger({ triggerReportId: 1.5 }).ok, false)
  assert.equal(resolveBountyTrigger({ triggerReportId: 'nope' }).ok, false)
})

test('resolveBountyTrigger: rejects a non-positive-integer triggerObservationId', () => {
  assert.equal(resolveBountyTrigger({ triggerObservationId: 0 }).ok, false)
  assert.equal(resolveBountyTrigger({ triggerObservationId: -1 }).ok, false)
  assert.equal(resolveBountyTrigger({ triggerObservationId: 'nope' }).ok, false)
})

test('resolveBountyTrigger: null is treated the same as absent (still "neither present")', () => {
  const result = resolveBountyTrigger({ triggerReportId: null, triggerObservationId: null })
  assert.equal(result.ok, false)
})

// ── observationToBountySource ─────────────────────────────────────────────

function makeObservation(overrides: Partial<WorldObservationRow> = {}): WorldObservationRow {
  return {
    id: 42,
    scanHotspotId: null,
    communityReportId: null,
    sourceType: 'route_scan',
    domain: 'litter',
    lat: 41.88,
    lng: -87.63,
    locationCellId: '100:200',
    itemType: 'tire',
    state: 'dirty',
    attributes: null,
    confidence: 0.9,
    mediaUrl: 'https://example.com/observation.jpg',
    depthData: null,
    arAnchorData: null,
    capturedAt: '2026-07-06T12:00:00.000Z',
    isCurrent: true,
    excludedPrivateProperty: false,
    createdAt: '2026-07-06T12:00:00.000Z',
    ...overrides,
  } as WorldObservationRow
}

test('observationToBountySource: derives location from the observation lat/lng', () => {
  const source = observationToBountySource(makeObservation({ lat: 41.88, lng: -87.63 }))
  assert.equal(source.locationLat, 41.88)
  assert.equal(source.locationLng, -87.63)
})

test('observationToBountySource: derives mediaUrls from a single-element array around mediaUrl', () => {
  const source = observationToBountySource(makeObservation({ mediaUrl: 'https://example.com/x.jpg' }))
  assert.deepEqual(source.mediaUrls, ['https://example.com/x.jpg'])
})

test('observationToBountySource: mediaUrls is empty when the observation has no mediaUrl', () => {
  const source = observationToBountySource(makeObservation({ mediaUrl: null }))
  assert.deepEqual(source.mediaUrls, [])
})

test('observationToBountySource: a privacy-flagged photo never reaches the bounty card', () => {
  const source = observationToBountySource(
    makeObservation({ mediaUrl: 'https://example.com/x.jpg', attributes: { mediaFlagged: true } })
  )
  assert.deepEqual(source.mediaUrls, [])
})

test('observationToBountySource: title/description fall back to the itemType', () => {
  const source = observationToBountySource(makeObservation({ itemType: 'tire' }))
  assert.equal(source.title, 'Cleanup opportunity: Tire')
  assert.equal(source.description, 'Scan-detected tire')
})

test('observationToBountySource: title/description degrade gracefully with a null itemType', () => {
  const source = observationToBountySource(makeObservation({ itemType: null }))
  assert.equal(source.title, 'Cleanup opportunity: Litter')
  assert.equal(source.description, null)
})

test('observationToBountySource: uses a tighter radius than the report-path default (single grid cell)', () => {
  const source = observationToBountySource(makeObservation())
  assert.equal(source.radiusMeters, 15)
})
