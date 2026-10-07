/**
 * GET /api/spots/:id — the app's verification and volunteer-cleanup screens
 * call it (GccApiClient.getSpot). The route used to export only PATCH/DELETE,
 * so every call got a 405 and the spot's coordinates never loaded.
 */
import { test, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { NextRequest } from 'next/server'

process.env.JWT_SECRET = 'test-secret-for-spot-get-tests-0123456789'
process.env.DATABASE_URL ??= 'postgres://test:test@localhost/test'

import { __setDbForTests } from '@/db'
import { createFakeDb, type FakeQuery } from '@/test-support/fake-db'
import { createMobileToken } from '@/lib/mobile-auth'
import { GET } from './route'

const row = {
  id: 42, reporterType: 'worker', reporterId: 7, reporterName: 'Wendy', type: 'sighting', coordinates: [-87.63, 41.88],
  description: 'couch on the curb', mediaIds: ['https://blob.example/spots/7/a.jpg'], status: 'open',
  tags: null, createdAt: '2026-10-01 10:00:00+00', updatedAt: '2026-10-02 10:00:00+00',
}

async function get(id: string, reportRows: unknown[], withToken = true, caller = { id: 7, email: 'w@example.org' }) {
  __setDbForTests(createFakeDb((q: FakeQuery) => {
    if (q.op === 'select' && q.table === 'workers') return [{ email: caller.email, emailVerified: true, tokenVersion: 0 }]
    if (q.op === 'select' && q.table === 'community_reports') return reportRows
    return []
  }).db)
  const headers: Record<string, string> = {}
  if (withToken) {
    const { token } = await createMobileToken({ id: caller.id, email: caller.email, emailVerified: true, tokenVersion: 0 })
    headers.authorization = `Bearer ${token}`
  }
  const res = await GET(new NextRequest(`http://localhost/api/spots/${id}`, { headers }), { params: Promise.resolve({ id }) })
  return { res, json: await res.json() }
}

afterEach(() => __setDbForTests(null))

test('returns the spot as a CommunityReport with {lat, lng} coordinates', async () => {
  const { res, json } = await get('42', [row])
  assert.equal(res.status, 200)
  assert.equal(json.data.id, 42)
  assert.deepEqual(json.data.coordinates, { lng: -87.63, lat: 41.88 })
  assert.equal(json.data.photoUrl, 'https://blob.example/spots/7/a.jpg')
  assert.equal(json.data.createdAt, '2026-10-01T10:00:00.000Z')
})

test('404 for a missing spot, 401 without a token', async () => {
  assert.equal((await get('43', [])).res.status, 404)
  assert.equal((await get('42', [row], false)).res.status, 401)
})

// ── visibility: same rule as the public map list ─────────────────────────
const OTHER = { id: 99, email: 'other@example.org' }
const publicSpot = { ...row, status: 'verified', showOnMap: true, mapExpiresAt: null }

test('another worker can read a spot that is on the public map', async () => {
  assert.equal((await get('42', [publicSpot], true, OTHER)).res.status, 200)
})

test('another worker gets 404 for hidden, rejected, resolved or expired spots', async () => {
  for (const hidden of [
    { ...publicSpot, showOnMap: false },
    { ...publicSpot, status: 'rejected' },
    { ...publicSpot, status: 'resolved' },
    { ...publicSpot, mapExpiresAt: '2020-01-01T00:00:00Z' },
  ]) {
    const { res, json } = await get('42', [hidden], true, OTHER)
    assert.equal(res.status, 404, JSON.stringify(hidden))
    assert.equal(json.message, 'Spot report not found', 'same answer as a missing id')
  }
})

test('the reporter can read their own hidden spot', async () => {
  assert.equal((await get('42', [{ ...publicSpot, status: 'rejected' }])).res.status, 200)
})

test('an admin can read a hidden spot', async () => {
  const before = process.env.ADMIN_EMAILS
  process.env.ADMIN_EMAILS = 'boss@example.org'
  try {
    assert.equal((await get('42', [{ ...publicSpot, showOnMap: false }], true, { id: 5, email: 'boss@example.org' })).res.status, 200)
  } finally {
    if (before === undefined) delete process.env.ADMIN_EMAILS
    else process.env.ADMIN_EMAILS = before
  }
})
