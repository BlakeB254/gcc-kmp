/**
 * POST /api/spots/:id/volunteer?complete — the completion route the app calls
 * (GccApiClient.completeVolunteerCleanup). The app used to post to
 * /volunteer/complete, which does not exist, so every volunteer completion
 * failed with a 404.
 *
 * The database is a fake (src/test-support/fake-db.ts); nothing connects.
 */
import { test, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { NextRequest } from 'next/server'

process.env.JWT_SECRET = 'test-secret-for-volunteer-tests-0123456789'
process.env.DATABASE_URL ??= 'postgres://test:test@localhost/test'

import { __setDbForTests } from '@/db'
import { createFakeDb, callArg, type FakeQuery } from '@/test-support/fake-db'
import { createMobileToken } from '@/lib/mobile-auth'
import { POST } from './route'

const WORKER_ID = 7

function install(report: Record<string, unknown>) {
  return createFakeDb((q: FakeQuery) => {
    if (q.op === 'select' && q.table === 'workers') {
      return [{ email: 'v@example.org', emailVerified: true, tokenVersion: 0 }]
    }
    if (q.op === 'select' && q.table === 'community_reports') return [report]
    return []
  })
}

async function call(query: string, body: unknown, report: Record<string, unknown>) {
  const fake = install(report)
  __setDbForTests(fake.db)
  const { token } = await createMobileToken({ id: WORKER_ID, email: 'v@example.org', emailVerified: true, tokenVersion: 0 })
  const req = new NextRequest(`http://localhost/api/spots/42/volunteer${query}`, {
    method: 'POST',
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
    body: JSON.stringify(body),
  })
  const res = await POST(req, { params: Promise.resolve({ id: '42' }) })
  return { res, json: await res.json(), log: fake.log }
}

const startedReport = {
  id: 42,
  status: 'acknowledged',
  reviewedById: WORKER_ID,
  beforePhotos: ['https://blob.example/spots/7/before.jpg'],
  createdAt: '2026-10-01T10:00:00.000Z',
  updatedAt: '2026-10-07T09:00:00.000Z',
}

afterEach(() => __setDbForTests(null))

test('?complete resolves the spot, awards points and returns a full VolunteerCleanup', async () => {
  const { res, json, log } = await call(
    '?complete',
    { afterMediaUrls: ['https://blob.example/spots/7/after.jpg'], trashHeapMediaUrl: null },
    { ...startedReport, coordinates: [-87.63, 41.88] },
  )
  assert.equal(res.status, 200)
  assert.equal(json.success, true)
  assert.equal(json.data.status, 'completed')
  assert.equal(json.data.pointsAwarded, 50)
  assert.deepEqual(json.data.afterMediaUrls, ['https://blob.example/spots/7/after.jpg'])
  assert.equal(json.data.locationLat, 41.88)
  assert.equal(json.data.locationLng, -87.63)

  const update = log.find((q) => q.op === 'update' && q.table === 'community_reports')
  const set = callArg(update!, 'set') as Record<string, unknown>
  assert.equal(set.status, 'resolved')
  assert.ok(log.some((q) => q.op === 'insert'), 'points ledger row inserted')
})

test('?complete with {lat, lng} coordinates still sends numeric locationLat/locationLng', async () => {
  // Older/admin-created rows store an object. Indexing it as a tuple left both
  // fields undefined, which JSON drops — the app's required Double then failed
  // to decode even though the server had already resolved the spot.
  const { json } = await call('?complete', { afterMediaUrls: [] }, { ...startedReport, coordinates: { lat: 41.9, lng: -87.6 } })
  assert.equal(json.data.locationLat, 41.9)
  assert.equal(json.data.locationLng, -87.6)
})

test('?complete by a worker who did not start the cleanup is refused', async () => {
  const { res } = await call('?complete', {}, { ...startedReport, reviewedById: 99, coordinates: [-87.63, 41.88] })
  assert.equal(res.status, 403)
})

test('start path returns the ApiResponse envelope with numeric coordinates', async () => {
  const { res, json } = await call(
    '',
    { beforeMediaUrls: ['https://blob.example/spots/7/before.jpg'] },
    { ...startedReport, status: 'open', reviewedById: null, coordinates: { lat: 41.9, lng: -87.6 } },
  )
  assert.equal(res.status, 200)
  assert.equal(json.success, true)
  assert.equal(json.data.status, 'in_progress')
  assert.equal(json.data.locationLat, 41.9)
})
