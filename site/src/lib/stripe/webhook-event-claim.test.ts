import assert from 'node:assert/strict'
import test, { mock } from 'node:test'

import { dbEventClaimStore, processEventOnce, type EventClaimStore } from './webhook-event-claim'
import { recordingDb } from '../testing/recording-db'

function memoryStore(): EventClaimStore & { ids: Set<string> } {
  const ids = new Set<string>()
  return {
    ids,
    async claim(id) {
      if (ids.has(id)) return false
      ids.add(id)
      return true
    },
    async release(id) {
      ids.delete(id)
    },
  }
}

test('processEventOnce: a handler error releases the claim so Stripe’s retry is processed', async () => {
  const store = memoryStore()
  let calls = 0
  const handler = async () => {
    calls++
    if (calls === 1) throw new Error('db blip')
  }
  await assert.rejects(processEventOnce('evt_1', store, handler), /db blip/)
  assert.equal(store.ids.has('evt_1'), false, 'claim released after failure')

  // Stripe retries the same event: it must run, not be skipped as a duplicate.
  assert.equal(await processEventOnce('evt_1', store, handler), 'processed')
  assert.equal(calls, 2)

  // A redelivery after success is a duplicate and does not run again.
  assert.equal(await processEventOnce('evt_1', store, handler), 'duplicate')
  assert.equal(calls, 2)
})

test('processEventOnce: a failing release does not mask the handler error', async () => {
  const store = memoryStore()
  store.release = async () => {
    throw new Error('release failed')
  }
  const errorLog = mock.method(console, 'error', () => undefined)
  try {
    await assert.rejects(processEventOnce('evt_2', store, async () => { throw new Error('handler failed') }), /handler failed/)
    assert.equal(errorLog.mock.callCount(), 1)
  } finally {
    errorLog.mock.restore()
  }
})

test('dbEventClaimStore: claim = INSERT … ON CONFLICT DO NOTHING RETURNING; release = DELETE by id', async () => {
  const { db, queries } = recordingDb((q) => (/^insert/.test(q.sql) ? [['evt_3']] : undefined))
  const store = dbEventClaimStore(db)
  assert.equal(await store.claim('evt_3'), true)
  await store.release('evt_3')
  assert.match(queries[0].sql, /^insert into "stripe_events" .* on conflict do nothing returning "id"$/)
  assert.match(queries[1].sql, /^delete from "stripe_events" where "stripe_events"\."id" = \$1$/)
  assert.deepEqual(queries[1].params, ['evt_3'])

  const dup = dbEventClaimStore(recordingDb().db)
  assert.equal(await dup.claim('evt_3'), false)
})
