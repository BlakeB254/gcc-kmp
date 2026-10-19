import { test } from 'node:test'
import assert from 'node:assert/strict'
import { gzipSync } from 'node:zlib'
import { EventLogUnreadable, MAX_EVENT_LOG_BYTES, decodeEvent, extractEventLogMetrics, gunzipLines } from './event-log-metrics'

// Cases ported from backend/src/test/kotlin/com/gcc/backend/EventLogMetricsTest.kt.

test('extracts headline counts from typed events', () => {
  const m = extractEventLogMetrics([
    '{"t":0,"type":"caption","msg":"Sweep the camera slowly"}',
    '{"t":1000,"type":"gate","state":"UNCERTAIN"}',
    '{"t":2000,"type":"gate","state":"OUTDOOR"}',
    '{"t":2500,"type":"gate","state":"OUTDOOR"}',
    '{"t":3000,"type":"gate","state":"INDOOR","msg":"ceiling-plane"}',
    '{"t":4000,"type":"tap"}',
    '{"t":4100,"type":"pill","state":"LOCATING"}',
    '{"t":4200,"type":"pill","state":"NO_RAYCAST"}',
    '{"t":5000,"type":"tap"}',
    '{"t":5200,"type":"pill","state":"AWAITING_CONFIRMATION","label":"can","confidence":0.8}',
    '{"t":5500,"type":"confirm","label":"can"}',
    '{"t":6000,"type":"reject","label":"leaf"}',
    '{"t":7000,"type":"detection","label":"bottle","confidence":0.7}',
    '{"t":8000,"type":"upload","tag":"chunk-video","ok":true,"key":"raw/x/y/z.mov"}',
    '{"t":9000,"type":"upload","tag":"chunk-depth","ok":false,"msg":"PUT 401"}',
    '{"t":9500,"type":"diag","state":"ERROR","tag":"capture-upload","msg":"chunk PUT 4xx"}',
    '{"t":9600,"type":"diag","state":"INFO","tag":"outdoor-gate","msg":"scene votes indoor"}',
  ])
  assert.deepEqual(m, {
    eventCount: 17,
    gateFlipCount: 2, // UNCERTAIN → OUTDOOR → (OUTDOOR) → INDOOR
    tapCount: 2,
    confirmCount: 1,
    rejectCount: 1,
    raycastMissCount: 1,
    detectionCount: 1,
    uploadAttemptCount: 2,
    uploadFailureCount: 1,
    errorCount: 1,
  })
})

test('tolerates corrupt lines and unknown types', () => {
  const m = extractEventLogMetrics([
    '{"t":0,"type":"tap"}',
    '{"t":100,"type":"hologram","weird":true}',
    'not json at all',
    '{"t":200,"type":"tap"',
  ])
  assert.equal(m.eventCount, 2)
  assert.equal(m.tapCount, 1)
})

test('empty log yields zeroes', () => {
  const m = extractEventLogMetrics([])
  assert.equal(m.eventCount, 0)
  assert.equal(m.gateFlipCount, 0)
})

test('gunzips a real event-log artifact', () => {
  const gz = gzipSync('{"t":0,"type":"tap"}\n{"t":1,"type":"confirm","label":"can"}\n')
  const lines = gunzipLines(gz)
  assert.equal(lines.length, 2)
  const m = extractEventLogMetrics(lines)
  assert.equal(m.tapCount, 1)
  assert.equal(m.confirmCount, 1)
})

test('a line decodes only when ScanSessionEvent would', () => {
  assert.equal(decodeEvent('{"type":"tap"}'), null, 't is required')
  assert.equal(decodeEvent('{"t":1.5,"type":"tap"}'), null, 't is a Long')
  assert.equal(decodeEvent('{"t":1}'), null, 'type is required')
  assert.equal(decodeEvent('{"t":1,"type":"tap","confidence":"high"}'), null, 'confidence is a Float')
  assert.equal(decodeEvent('{"t":1,"type":"tap","msg":{"a":1}}'), null, 'msg is a String')
  assert.equal(decodeEvent('[1,2]'), null)
  assert.deepEqual(decodeEvent('{"t":"7","type":"upload","ok":"false"}'), { type: 'upload', state: null, ok: false })
})

test('gunzip refuses decompression bombs, oversized input and non-gzip', () => {
  assert.throws(() => gunzipLines(gzipSync(Buffer.alloc(65 * 1024 * 1024))), EventLogUnreadable)
  assert.throws(() => gunzipLines(new Uint8Array(MAX_EVENT_LOG_BYTES + 1)), EventLogUnreadable)
  assert.throws(() => gunzipLines(new TextEncoder().encode('plain text')), EventLogUnreadable)
})
