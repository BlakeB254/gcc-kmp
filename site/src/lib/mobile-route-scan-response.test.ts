import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  isValidScanPhaseTransition,
  normalizeScanPhase,
  validateLapOneProof,
} from './mobile-route-scan-response'

// Pure helpers only — no DB. Run with:
//   DATABASE_URL=postgres://test:test@localhost/test npx tsx --test src/lib/mobile-route-scan-response.test.ts

test('isValidScanPhaseTransition: same phase is always allowed (idempotent retries)', () => {
  for (const phase of ['scouting', 'scout_review', 'cleaning', 'submitting', 'complete']) {
    assert.equal(isValidScanPhaseTransition(phase, phase), true)
  }
})

test('isValidScanPhaseTransition: one step forward is allowed', () => {
  assert.equal(isValidScanPhaseTransition('scouting', 'scout_review'), true)
  assert.equal(isValidScanPhaseTransition('scout_review', 'cleaning'), true)
  assert.equal(isValidScanPhaseTransition('cleaning', 'submitting'), true)
  assert.equal(isValidScanPhaseTransition('submitting', 'complete'), true)
})

test('isValidScanPhaseTransition: skipping the cleanup lap is rejected', () => {
  assert.equal(isValidScanPhaseTransition('scouting', 'cleaning'), false)
  assert.equal(isValidScanPhaseTransition('scouting', 'complete'), false)
  assert.equal(isValidScanPhaseTransition('scout_review', 'complete'), false)
})

test('isValidScanPhaseTransition: backward writes are rejected', () => {
  assert.equal(isValidScanPhaseTransition('complete', 'scouting'), false)
  assert.equal(isValidScanPhaseTransition('cleaning', 'scout_review'), false)
})

test('isValidScanPhaseTransition: garbage input normalizes to scouting', () => {
  // normalizeScanPhase maps unknown → 'scouting', so garbage "to" values are
  // a backward write from anything past scouting and get rejected.
  assert.equal(normalizeScanPhase('bogus'), 'scouting')
  assert.equal(isValidScanPhaseTransition('cleaning', 'bogus'), false)
  assert.equal(isValidScanPhaseTransition('scouting', 'bogus'), true)
})

test('validateLapOneProof: guided AR routes require coverage, detail, and loop closure', () => {
  assert.equal(validateLapOneProof({ coveragePercent: 95, closeCoveragePercent: 80, loopClosed: true }, true), true)
  assert.equal(validateLapOneProof({ coveragePercent: 94.9, closeCoveragePercent: 100, loopClosed: true }, true), false)
  assert.equal(validateLapOneProof({ coveragePercent: 100, closeCoveragePercent: 79.9, loopClosed: true }, true), false)
  assert.equal(validateLapOneProof({ coveragePercent: 100, closeCoveragePercent: 100, loopClosed: false }, true), false)
})

test('validateLapOneProof: GPS routes omit close detail and legacy clients remain compatible', () => {
  assert.equal(validateLapOneProof({ coveragePercent: 95, closeCoveragePercent: 0, loopClosed: true }, false), true)
  assert.equal(validateLapOneProof({ coveragePercent: 95 }, false), null)
  assert.equal(validateLapOneProof(null, true), null)
})
