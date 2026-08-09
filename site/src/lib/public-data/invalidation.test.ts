import assert from 'node:assert/strict'
import test, { mock } from 'node:test'

import { GCC_CACHE_TAGS } from './cache-tags'
import {
  commitPublicDataMutation,
  createPublicDataInvalidator,
  tagsForPublicDataChange,
} from './invalidation'

test('each public data change expires only its owned cache families', () => {
  // Catches mapping a narrow mutation to an unrelated public cache family,
  // which would turn authoritative writes into broad cache flushes.
  assert.deepEqual(tagsForPublicDataChange('cleanup-impact'), [
    GCC_CACHE_TAGS.publicStats,
    GCC_CACHE_TAGS.impactStats,
  ])
  assert.deepEqual(tagsForPublicDataChange('worker-verification'), [
    GCC_CACHE_TAGS.publicStats,
    GCC_CACHE_TAGS.impactStats,
  ])
  assert.deepEqual(tagsForPublicDataChange('sponsorship'), [
    GCC_CACHE_TAGS.publicStats,
    GCC_CACHE_TAGS.impactStats,
    GCC_CACHE_TAGS.sponsorWall,
    GCC_CACHE_TAGS.supportWall,
  ])
  assert.deepEqual(tagsForPublicDataChange('supporter'), [
    GCC_CACHE_TAGS.publicStats,
    GCC_CACHE_TAGS.impactStats,
  ])
  assert.deepEqual(tagsForPublicDataChange('affected-area'), [
    GCC_CACHE_TAGS.affectedAreaSnapshots,
  ])
})

test('the public invalidator rejects arbitrary tag strings instead of forwarding them', () => {
  // Catches weakening the typed change boundary into a stringly-typed tag
  // passthrough that lets routes invalidate unrelated Next cache entries.
  const expired: string[] = []
  const invalidate = createPublicDataInvalidator((tag) => {
    expired.push(tag)
  })

  assert.throws(
    () => invalidate('gcc:anything' as never),
    /Unknown public data change/,
  )
  assert.deepEqual(expired, [])

  if (false) {
    // @ts-expect-error Routes must use a declared domain change, never a raw tag.
    tagsForPublicDataChange('gcc:anything')
  }
})

test('the public invalidator forwards every mapped family with bounded-stale semantics', () => {
  // Catches dropping a family from a multi-family change or using immediate
  // expiry instead of the cache contract's stale-while-revalidate profile.
  const expired: Array<{ tag: string; profile: string }> = []
  const invalidate = createPublicDataInvalidator((tag, profile) => {
    expired.push({ tag, profile: String(profile) })
  })

  invalidate('sponsorship')

  assert.deepEqual(expired, [
    { tag: GCC_CACHE_TAGS.publicStats, profile: 'max' },
    { tag: GCC_CACHE_TAGS.impactStats, profile: 'max' },
    { tag: GCC_CACHE_TAGS.sponsorWall, profile: 'max' },
    { tag: GCC_CACHE_TAGS.supportWall, profile: 'max' },
  ])
})

test('a cache-boundary failure cannot change an already-committed mutation result', async () => {
  // Catches an invalidation exception changing a successful authoritative
  // route response into a 500 after the database write has already committed.
  const committed = { id: 17, status: 'completed' }

  const errorLog = mock.method(console, 'error', () => undefined)
  try {
    const result = await commitPublicDataMutation(
      'cleanup-impact',
      async () => committed,
      () => {
        throw new Error('Next cache unavailable')
      },
    )

    assert.deepEqual(result, committed)
  } finally {
    errorLog.mock.restore()
  }
})
