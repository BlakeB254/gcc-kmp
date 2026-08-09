import assert from 'node:assert/strict'
import test, { mock } from 'node:test'

import { GCC_CACHE_TAGS } from './cache-tags'
import {
  commitPublicDataChanges,
  commitPublicDataMutation,
  createPublicDataInvalidator,
  tagsForPublicDataChange,
} from './invalidation'

test('compatibility change kinds retain their multi-family mappings', () => {
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

test('granular change kinds expire only a single projection family', () => {
  // Catches collapsing job-completion and community-report writers onto the
  // same multi-family group when they touch disjoint aggregates.
  assert.deepEqual(tagsForPublicDataChange('public-stats'), [GCC_CACHE_TAGS.publicStats])
  assert.deepEqual(tagsForPublicDataChange('impact-stats'), [GCC_CACHE_TAGS.impactStats])
  assert.deepEqual(tagsForPublicDataChange('wall-presentation'), [
    GCC_CACHE_TAGS.sponsorWall,
    GCC_CACHE_TAGS.supportWall,
  ])
})

test('the public invalidator rejects arbitrary tag strings instead of forwarding them', () => {
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

test('zero-row mutations do not invalidate any cache family', async () => {
  // Catches invalidating after an UPDATE … RETURNING that matched no rows
  // (stale worker id, concurrent delete) and thrashing public caches for free.
  const tags: string[] = []
  const invalidate = createPublicDataInvalidator((tag) => {
    tags.push(tag)
  })

  const empty = await commitPublicDataMutation(
    'worker-verification',
    async () => [] as unknown[],
    invalidate,
  )
  assert.deepEqual(empty, [])
  assert.deepEqual(tags, [])

  const missing = await commitPublicDataMutation(
    'impact-stats',
    async () => null,
    invalidate,
  )
  assert.equal(missing, null)
  assert.deepEqual(tags, [])
})

test('non-empty committed results still invalidate every requested change', async () => {
  const tags: string[] = []
  const invalidate = createPublicDataInvalidator((tag) => {
    tags.push(tag)
  })

  const row = { id: 9, status: 'approved' }
  await commitPublicDataChanges(['public-stats', 'impact-stats'], async () => [row], invalidate)
  assert.deepEqual(tags, [GCC_CACHE_TAGS.publicStats, GCC_CACHE_TAGS.impactStats])
})
