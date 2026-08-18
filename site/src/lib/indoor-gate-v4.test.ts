import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  INDOOR_GATE_V4_COPY,
  parseGateEvent,
  parseOutdoorState,
  summarizeIndoorGateV4,
} from './indoor-gate-v4'

test('parseOutdoorState accepts the three gate states only', () => {
  assert.equal(parseOutdoorState('uncertain'), 'UNCERTAIN')
  assert.equal(parseOutdoorState('OUTDOOR'), 'OUTDOOR')
  assert.equal(parseOutdoorState('INDOOR'), 'INDOOR')
  assert.equal(parseOutdoorState('inside'), null)
})

test('parseGateEvent reads client gate@ / purge@ strings', () => {
  const gate = parseGateEvent('gate@1000000 state=INDOOR')
  assert.equal(gate?.kind, 'state')
  assert.equal(gate?.state, 'INDOOR')
  assert.equal(gate?.atMs, 1_000_000)

  const purge = parseGateEvent('purge@1005000 dropped=4')
  assert.equal(purge?.kind, 'purge')
  assert.equal(purge?.atMs, 1_005_000)
})

test('parseGateEvent classifies ceiling, override, and yield diagnostics', () => {
  assert.equal(parseGateEvent('INDOOR (ceiling plane detected)')?.kind, 'ceiling')
  assert.equal(
    parseGateEvent('manual override — user confirmed outdoors')?.kind,
    'override',
  )
  assert.equal(
    parseGateEvent('manual override cleared by ceiling plane')?.kind,
    'override_yield',
  )
})

test('summarizeIndoorGateV4 is deny-by-default until OUTDOOR is proven', () => {
  const empty = summarizeIndoorGateV4([])
  assert.equal(empty.lastState, 'UNCERTAIN')
  assert.equal(empty.captureAllowed, false)

  const outdoor = summarizeIndoorGateV4(['gate@1 state=OUTDOOR'])
  assert.equal(outdoor.captureAllowed, true)
  assert.equal(outdoor.lastState, 'OUTDOOR')
})

test('summarizeIndoorGateV4 counts v4 audit signals from mixed logs', () => {
  const summary = summarizeIndoorGateV4(
    [
      'gate@1000 state=UNCERTAIN',
      'gate@2000 state=INDOOR',
      'purge@2000 dropped=3',
    ],
    [
      'INDOOR (ceiling plane detected)',
      'manual override — user confirmed outdoors',
      'manual override cleared by ceiling plane',
    ],
  )
  assert.equal(summary.lastState, 'INDOOR')
  assert.equal(summary.captureAllowed, false)
  assert.equal(summary.lastIndoorFlipAtMs, 2000)
  assert.equal(summary.ceilingCueCount, 1)
  assert.equal(summary.overridePressCount, 1)
  assert.equal(summary.overrideYieldCount, 1)
  assert.equal(summary.purgeEventCount, 1)
})

test('v4 UX copy matches the shipped Compose strings', () => {
  assert.equal(INDOOR_GATE_V4_COPY.uncertainChip, "Confirming you're outside…")
  assert.equal(INDOOR_GATE_V4_COPY.imOutside, "I'm outside — continue")
})
