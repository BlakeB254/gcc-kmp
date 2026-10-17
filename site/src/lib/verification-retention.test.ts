import { test } from 'node:test'
import assert from 'node:assert/strict'
import { MIN_RETENTION_DAYS, clampRetentionDays, sweepVerificationDocs, type RetentionDeps } from './verification-retention'

const DAY = 24 * 60 * 60 * 1000
const NOW = Date.UTC(2026, 9, 17)

function fake(opts: { protectedIds?: number[]; failDelete?: string } = {}) {
  const r2 = new Map<string, number>([
    ['verification/1/old.jpg', NOW - 200 * DAY], // worker 1 approved, old → purge
    ['verification/2/old.jpg', NOW - 200 * DAY], // worker 2 in review → keep
    ['verification/1/new.jpg', NOW - 5 * DAY], // fresh → keep
    ['verification/9/orphan.jpg', NOW - 200 * DAY], // unreferenced → purge without tombstone
  ])
  const media = new Map<number, string>([[10, 'verification/1/old.jpg'], [11, 'verification/2/old.jpg']])
  const deps: RetentionDeps = {
    async listPrivate() {
      return [...r2.entries()].map(([key, lastModifiedMs]) => ({ key, lastModifiedMs }))
    },
    async reviewProtectedWorkers(ids) {
      return new Set(ids.filter((i) => (opts.protectedIds ?? [2]).includes(i)))
    },
    async referencedKeys(keys) {
      const urls = new Set(media.values())
      return new Set(keys.filter((k) => urls.has(k)))
    },
    async tombstone(key) {
      let n = 0
      for (const [id, url] of media) if (url === key) { media.set(id, `purged:${key}`); n++ }
      return n
    },
    async deletePrivate(key) {
      if (key === opts.failDelete) throw new Error('boom')
      r2.delete(key)
    },
  }
  return { deps, r2, media }
}

test('retention never drops below the floor', () => {
  assert.equal(clampRetentionDays(1), MIN_RETENTION_DAYS)
  assert.equal(clampRetentionDays(Number.NaN), MIN_RETENTION_DAYS)
  assert.equal(clampRetentionDays(90.7), 90)
})

test('dry run counts but deletes nothing', async () => {
  const w = fake()
  const r = await sweepVerificationDocs(w.deps, { retentionDays: 90, enforce: false, now: NOW })
  assert.deepEqual(
    { scanned: r.scanned, expired: r.expired, prot: r.protectedByReview, ref: r.expiredReferenced, del: r.deleted },
    { scanned: 4, expired: 3, prot: 1, ref: 1, del: 0 },
  )
  assert.equal(w.r2.size, 4)
  assert.equal(w.media.get(10), 'verification/1/old.jpg')
})

test('enforce tombstones referenced rows, then deletes; review and fresh docs survive', async () => {
  const w = fake()
  const r = await sweepVerificationDocs(w.deps, { retentionDays: 90, enforce: true, now: NOW })
  assert.equal(r.deleted, 2)
  assert.equal(r.tombstoned, 1)
  assert.equal(w.media.get(10), 'purged:verification/1/old.jpg')
  assert.equal(w.media.get(11), 'verification/2/old.jpg')
  assert.deepEqual([...w.r2.keys()].sort(), ['verification/1/new.jpg', 'verification/2/old.jpg'])
})

test('a failed delete is counted and the sweep continues', async () => {
  const w = fake({ failDelete: 'verification/1/old.jpg' })
  const r = await sweepVerificationDocs(w.deps, { retentionDays: 90, enforce: true, now: NOW })
  assert.equal(r.failed, 1)
  assert.equal(r.deleted, 1)
  assert.equal(w.r2.has('verification/9/orphan.jpg'), false)
})
