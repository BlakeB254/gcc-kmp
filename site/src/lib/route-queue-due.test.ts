import assert from 'node:assert/strict'
import test from 'node:test'

import {
  ROUTE_RELEASE_BATCH_LIMIT,
  isReleaseBatchDue,
  selectDueBatchIds,
} from './route-queue'

test('isReleaseBatchDue excludes future nextWaveAt and releaseAt', () => {
  const now = '2026-08-08T15:00:00.000Z'
  assert.equal(
    isReleaseBatchDue(
      { status: 'scheduled', releaseAt: '2026-08-08T16:00:00.000Z', nextWaveAt: '2026-08-08T16:00:00.000Z' },
      now,
    ),
    false,
  )
  assert.equal(
    isReleaseBatchDue(
      { status: 'active', releaseAt: '2026-08-08T10:00:00.000Z', nextWaveAt: '2026-08-08T16:00:00.000Z' },
      now,
    ),
    false,
  )
})

test('isReleaseBatchDue includes scheduled/active rows whose first or next wave is due', () => {
  const now = '2026-08-08T15:00:00.000Z'
  assert.equal(
    isReleaseBatchDue(
      { status: 'scheduled', releaseAt: '2026-08-08T14:00:00.000Z', nextWaveAt: '2026-08-08T14:00:00.000Z' },
      now,
    ),
    true,
  )
  assert.equal(
    isReleaseBatchDue(
      { status: 'active', releaseAt: '2026-08-08T10:00:00.000Z', nextWaveAt: '2026-08-08T14:30:00.000Z' },
      now,
    ),
    true,
  )
  assert.equal(
    isReleaseBatchDue(
      { status: 'active', releaseAt: '2026-08-08T10:00:00.000Z', nextWaveAt: null },
      now,
    ),
    true,
  )
})

test('isReleaseBatchDue rejects completed, cancelled, and draft batches', () => {
  const now = '2026-08-08T15:00:00.000Z'
  for (const status of ['draft', 'completed', 'cancelled'] as const) {
    assert.equal(
      isReleaseBatchDue(
        { status, releaseAt: '2026-08-08T10:00:00.000Z', nextWaveAt: '2026-08-08T10:00:00.000Z' },
        now,
      ),
      false,
    )
  }
})

test('selectDueBatchIds respects due predicate and documented batch cap', () => {
  const now = '2026-08-08T15:00:00.000Z'
  const rows = [
    { id: 1, status: 'scheduled', releaseAt: '2026-08-08T14:00:00.000Z', nextWaveAt: '2026-08-08T14:00:00.000Z' },
    { id: 2, status: 'active', releaseAt: '2026-08-08T10:00:00.000Z', nextWaveAt: '2026-08-08T16:00:00.000Z' },
    { id: 3, status: 'active', releaseAt: '2026-08-08T10:00:00.000Z', nextWaveAt: '2026-08-08T12:00:00.000Z' },
    { id: 4, status: 'completed', releaseAt: '2026-08-08T10:00:00.000Z', nextWaveAt: null },
  ]

  assert.deepEqual(selectDueBatchIds(rows, now, 10), [1, 3])
  assert.equal(ROUTE_RELEASE_BATCH_LIMIT, 25)

  const many = Array.from({ length: 40 }, (_, i) => ({
    id: i + 1,
    status: 'active' as const,
    releaseAt: '2026-08-08T10:00:00.000Z',
    nextWaveAt: '2026-08-08T10:00:00.000Z',
  }))
  assert.deepEqual(selectDueBatchIds(many, now, ROUTE_RELEASE_BATCH_LIMIT).length, 25)
})
