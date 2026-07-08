import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  isScanSubmission,
  resolvePointsAction,
  buildSyntheticScanReport,
  SCAN_OBSERVATION_PIN_ID_BASE,
  toCommunityReport,
} from './spot-utils'
import type { NewCommunityReport } from '@/db/schema'

// Amendment 2 / Task 1b (litter-truth): scan submissions to POST /api/spots
// no longer insert a communityReports row. These tests exercise the pure
// decision/construction helpers extracted from the route so the "no report
// row for scans, still a report row for manual submissions, and the
// wire-compat synthetic report" behavior is independently testable without
// a database or a running Next.js server. Run with:
//   npx tsx --test src/lib/spot-utils.test.ts
// (No DATABASE_URL needed — spot-utils.ts only imports `NewCommunityReport`
// as a type, which is erased; no runtime `@/db` import.)

// ── isScanSubmission ──────────────────────────────────────────────────────

test('isScanSubmission: an observedItems array (even empty) marks a scan submission', () => {
  assert.equal(isScanSubmission([], undefined), true)
  assert.equal(isScanSubmission([{ itemType: 'trash', lat: 1, lng: 2 }], undefined), true)
})

test('isScanSubmission: a live-capture attestation marks a scan submission even with no observedItems array', () => {
  assert.equal(isScanSubmission(null, { deviceId: 'abc' }), true)
  assert.equal(isScanSubmission(undefined, { deviceId: 'abc' }), true)
})

test('isScanSubmission: manual "Report a Spot" (no observedItems array, no attestation) is NOT a scan submission', () => {
  assert.equal(isScanSubmission(undefined, undefined), false)
  assert.equal(isScanSubmission(null, null), false)
  assert.equal(isScanSubmission(null, false), false)
})

// ── resolvePointsAction ───────────────────────────────────────────────────

test('resolvePointsAction: scan submissions log observation_logged against a world_observation', () => {
  assert.deepEqual(resolvePointsAction(true), { action: 'observation_logged', referenceType: 'world_observation' })
})

test('resolvePointsAction: manual submissions keep spot_created against a spot (unchanged)', () => {
  assert.deepEqual(resolvePointsAction(false), { action: 'spot_created', referenceType: 'spot' })
})

// ── buildSyntheticScanReport (wire-compat, CRITICAL) ─────────────────────

function makeReportValues(overrides: Partial<NewCommunityReport> = {}): NewCommunityReport {
  return {
    reporterType: 'worker',
    reporterId: 42,
    reporterName: 'Test Worker',
    type: 'sighting',
    description: 'Trash pile',
    coordinates: [-87.65, 41.85],
    mediaIds: ['https://example.com/photo.jpg'],
    mediaCaptions: null,
    affectedRadiusMeters: '15',
    affectedArea: null,
    tags: ['trash'],
    status: 'pending',
    showOnMap: true,
    capturedAt: '2026-07-06T12:00:00.000Z',
    lastVerifiedAt: '2026-07-06T12:00:00.000Z',
    verificationCount: 0,
    nextVerificationDue: '2026-07-07T12:00:00.000Z',
    missedVerifications: 0,
    depthAnalysis: null,
    captureAttestation: null,
    onDeviceAiResult: null,
    duplicateOverrideReason: null,
    source: 'user',
    ...overrides,
  }
}

test('buildSyntheticScanReport: id is SCAN_OBSERVATION_PIN_ID_BASE + anchorObservationId', () => {
  const report = buildSyntheticScanReport(makeReportValues(), 777, '2026-07-06T12:00:00.000Z')
  assert.equal(report.id, SCAN_OBSERVATION_PIN_ID_BASE + 777)
  assert.ok(report.id >= 1_000_000)
})

test('buildSyntheticScanReport: round-trips submission fields (type/coords/description/mediaUrls/tags) through toCommunityReport shape', () => {
  const values = makeReportValues({ description: 'Couch dumped on curb', tags: ['furniture'] })
  const report = buildSyntheticScanReport(values, 5, '2026-07-06T12:00:00.000Z')

  assert.equal(report.description, 'Couch dumped on curb')
  assert.deepEqual(report.coordinates, { lng: -87.65, lat: 41.85 })
  assert.deepEqual(report.mediaUrls, ['https://example.com/photo.jpg'])
  assert.deepEqual(report.tags, ['furniture'])
  assert.equal(report.type, 'sighting')
  assert.equal(report.status, 'pending') // fails-soft status, see doc comment
  assert.equal(report.createdAt, '2026-07-06T12:00:00.000Z')
  assert.equal(report.updatedAt, '2026-07-06T12:00:00.000Z')
})

test('buildSyntheticScanReport: matches the shape toCommunityReport(realRow) would produce (same keys)', () => {
  const values = makeReportValues()
  const synthetic = buildSyntheticScanReport(values, 1, '2026-07-06T12:00:00.000Z')
  const real = toCommunityReport({
    id: 123,
    ...values,
    thankCount: 0,
    reviewedById: null,
    resolvedByJobId: null,
    createdAt: '2026-07-06T12:00:00.000Z',
    updatedAt: '2026-07-06T12:00:00.000Z',
  })
  assert.deepEqual(Object.keys(synthetic).sort(), Object.keys(real).sort())
})

test('buildSyntheticScanReport: anchorObservationId of 0 still produces a valid (base) synthetic id', () => {
  // Defensive fallback path in the route (should not occur in practice —
  // the detectedItems fallback loop always records at least one
  // observation for a scan submission — but must not crash or collide
  // with a real report id if it ever does).
  const report = buildSyntheticScanReport(makeReportValues(), 0, '2026-07-06T12:00:00.000Z')
  assert.equal(report.id, SCAN_OBSERVATION_PIN_ID_BASE)
})
