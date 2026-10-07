/**
 * GET /api/admin/jobs/pending-review reads job evidence in the shape
 * PATCH /api/jobs/:id/evidence actually stores — `{url, capturedAt}` objects —
 * instead of treating it as integer media ids (which put objects into
 * `inArray(media.id, …)` and failed the whole queue for any job with evidence).
 *
 * The database is a fake (src/test-support/fake-db.ts); nothing connects.
 */
import { test, afterEach } from 'node:test'
import assert from 'node:assert/strict'

process.env.JWT_SECRET = 'test-secret-for-pending-review-tests-0123456789'
process.env.DATABASE_URL ??= 'postgres://test:test@localhost/test'
process.env.ADMIN_EMAILS = 'boss@example.org'

import { __setDbForTests } from '@/db'
import { createFakeDb, type FakeQuery } from '@/test-support/fake-db'
import { createMobileToken } from '@/lib/mobile-auth'
import { evidenceUrls, evidenceMediaIds } from '@/lib/job-evidence'
import { GET } from './route'

// A realistic row: evidence exactly as the evidence route appends it.
const evidenceJob = {
  jobId: 311,
  workerId: 7,
  workerName: 'Wendy Worker',
  payoutAmount: '2500',
  beforePhotos: [
    { capturedAt: '2026-10-07T14:02:11.000Z', url: 'https://abc.public.blob.vercel-storage.com/spots/7/before-1.jpg' },
    { capturedAt: '2026-10-07T14:30:40.000Z', url: 'https://abc.public.blob.vercel-storage.com/spots/7/before-2.jpg' },
  ],
  afterPhotos: [
    { capturedAt: '2026-10-07T14:02:11.000Z', url: 'https://abc.public.blob.vercel-storage.com/spots/7/after-1.jpg' },
  ],
  completedAt: '2026-10-07T15:00:00+00:00',
  routeId: null,
}

async function run(jobRows: Record<string, unknown>[], mediaRows: Record<string, unknown>[] = []) {
  const fake = createFakeDb((q: FakeQuery) => {
    if (q.op === 'select' && q.table === 'workers') {
      return [{ email: 'boss@example.org', emailVerified: true, tokenVersion: 0 }]
    }
    if (q.op === 'select' && q.table === 'jobs') return jobRows
    if (q.op === 'select' && q.table === 'media') return mediaRows
    return []
  })
  __setDbForTests(fake.db)
  const { token } = await createMobileToken({ id: 1, email: 'boss@example.org', emailVerified: true, tokenVersion: 0 })
  const res = await GET(new Request('http://localhost/api/admin/jobs/pending-review', {
    headers: { Authorization: `Bearer ${token}` },
  }))
  return { res, json: await res.json(), log: fake.log }
}

afterEach(() => __setDbForTests(null))

test('{url, capturedAt} evidence comes back as URL lists without touching the media table', async () => {
  const { res, json, log } = await run([evidenceJob])
  assert.equal(res.status, 200)
  assert.equal(json.data.length, 1)
  const row = json.data[0]
  assert.equal(row.jobId, 311)
  assert.equal(row.payoutAmount, 2500)
  assert.deepEqual(row.beforePhotos, [
    'https://abc.public.blob.vercel-storage.com/spots/7/before-1.jpg',
    'https://abc.public.blob.vercel-storage.com/spots/7/before-2.jpg',
  ])
  assert.deepEqual(row.afterPhotos, ['https://abc.public.blob.vercel-storage.com/spots/7/after-1.jpg'])
  assert.equal(log.some((q) => q.table === 'media'), false, 'no media lookup for URL evidence')
})

test('legacy integer media ids still resolve through the media table', async () => {
  const { json, log } = await run(
    [{ ...evidenceJob, beforePhotos: [5], afterPhotos: [6, 999] }],
    [{ id: 5, url: 'https://media.example/5.jpg' }, { id: 6, url: 'https://media.example/6.jpg' }],
  )
  assert.ok(log.some((q) => q.table === 'media'))
  assert.deepEqual(json.data[0].beforePhotos, ['https://media.example/5.jpg'])
  assert.deepEqual(json.data[0].afterPhotos, ['https://media.example/6.jpg'])
})

test('a job with no evidence returns empty photo lists', async () => {
  const { json } = await run([{ ...evidenceJob, beforePhotos: null, afterPhotos: null }])
  assert.deepEqual(json.data[0].beforePhotos, [])
  assert.deepEqual(json.data[0].afterPhotos, [])
})

test('evidence helpers accept every stored shape and skip junk', () => {
  const mixed = [{ url: 'https://a/1.jpg', capturedAt: 'x' }, 'https://a/2.jpg', 3, { capturedAt: 'no url' }, null, 4.5]
  assert.deepEqual(evidenceMediaIds(mixed), [3])
  assert.deepEqual(evidenceUrls(mixed, new Map([[3, 'https://a/3.jpg']])), ['https://a/1.jpg', 'https://a/2.jpg', 'https://a/3.jpg'])
  assert.deepEqual(evidenceUrls({ not: 'an array' }), [])
})
