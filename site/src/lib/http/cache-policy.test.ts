import assert from 'node:assert/strict'
import test from 'node:test'

import { PRIVATE_NO_STORE, publicCacheControl } from './cache-policy'

test('publicCacheControl formats browser, shared, and stale durations exactly', () => {
  assert.equal(
    publicCacheControl({
      browserMaxAgeSeconds: 60,
      sharedMaxAgeSeconds: 300,
      staleWhileRevalidateSeconds: 120,
    }),
    'public, max-age=60, s-maxage=300, stale-while-revalidate=120',
  )
})

test('PRIVATE_NO_STORE prevents private response storage', () => {
  assert.equal(PRIVATE_NO_STORE, 'private, no-store')
})

for (const [field, value] of [
  ['browserMaxAgeSeconds', -1],
  ['sharedMaxAgeSeconds', -1],
  ['staleWhileRevalidateSeconds', -1],
  ['browserMaxAgeSeconds', 1.5],
  ['sharedMaxAgeSeconds', 1.5],
  ['staleWhileRevalidateSeconds', 1.5],
] as const) {
  test(`publicCacheControl rejects a ${field} duration of ${value}`, () => {
    assert.throws(() => publicCacheControl({
      browserMaxAgeSeconds: 60,
      sharedMaxAgeSeconds: 300,
      staleWhileRevalidateSeconds: 120,
      [field]: value,
    }))
  })
}
