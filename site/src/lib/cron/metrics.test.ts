import assert from 'node:assert/strict'
import test, { mock } from 'node:test'

import { logRouteReleaseMetrics, summarizeReleaseResults } from './metrics'

test('summarizeReleaseResults counts processed batches and offers sent', () => {
  assert.deepEqual(
    summarizeReleaseResults([
      { offersSent: 2 },
      { offersSent: 0 },
      { offersSent: 5 },
    ]),
    { processed: 3, released: 7 },
  )
})

test('logRouteReleaseMetrics emits structured JSON without free-form PII fields', () => {
  const lines: string[] = []
  const info = mock.method(console, 'info', (line: string) => {
    lines.push(line)
  })
  try {
    logRouteReleaseMetrics({
      examined: 4,
      processed: 2,
      released: 3,
      durationMs: 18,
      leaseAcquired: true,
    })
    assert.equal(lines.length, 1)
    const payload = JSON.parse(lines[0]!) as Record<string, unknown>
    assert.equal(payload.event, 'gcc.cron.route_releases')
    assert.equal(payload.examined, 4)
    assert.equal(payload.processed, 2)
    assert.equal(payload.released, 3)
    assert.equal(payload.durationMs, 18)
    assert.equal(payload.leaseAcquired, true)
    assert.equal('email' in payload, false)
    assert.equal('workerId' in payload, false)
  } finally {
    info.mock.restore()
  }
})
