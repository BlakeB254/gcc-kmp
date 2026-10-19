/**
 * GET /api/admin/jobs/pending-review carries `trust` per job: the qualifying
 * scan's server-verified coverage and GPS plausibility, photo fingerprint
 * flags, client vs server-capped hotspot volume, and the AI review
 * (job_ai_reviews) — or "not reviewed yet" (null) when the worker has not
 * written one.
 *
 * The database is a fake (src/test-support/fake-db.ts); nothing connects.
 */
import { test, afterEach } from 'node:test'
import assert from 'node:assert/strict'

process.env.JWT_SECRET = 'test-secret-for-pending-review-trust-0123456789'
process.env.DATABASE_URL ??= 'postgres://test:test@localhost/test'
process.env.ADMIN_EMAILS = 'boss@example.org'

import { __setDbForTests } from '@/db'
import { createFakeDb, type FakeQuery } from '@/test-support/fake-db'
import { createMobileToken } from '@/lib/mobile-auth'
import { GET } from './route'

const B = 'https://abc.public.blob.vercel-storage.com/spots/7'

const job = (over: Record<string, unknown> = {}) => ({
  jobId: 311,
  workerId: 7,
  workerName: 'Wendy Worker',
  payoutAmount: '2500',
  beforePhotos: [{ capturedAt: '2026-10-07T14:02:11.000Z', url: `${B}/before.jpg` }],
  afterPhotos: [{ capturedAt: '2026-10-07T14:30:40.000Z', url: `${B}/after.jpg` }],
  completedAt: '2026-10-07T15:00:00+00:00',
  completionScanId: null,
  routeId: null,
  ...over,
})

async function run(tables: Record<string, Array<Record<string, unknown>>>) {
  const fake = createFakeDb((q: FakeQuery) => {
    if (q.op === 'select' && q.table === 'workers') return [{ email: 'boss@example.org', emailVerified: true, tokenVersion: 0 }]
    if (q.op === 'select' && q.table) return tables[q.table] ?? []
    return []
  })
  __setDbForTests(fake.db)
  const { token } = await createMobileToken({ id: 1, email: 'boss@example.org', emailVerified: true, tokenVersion: 0 })
  const res = await GET(new Request('http://localhost/api/admin/jobs/pending-review', { headers: { Authorization: `Bearer ${token}` } }))
  return { res, json: await res.json() }
}

afterEach(() => __setDbForTests(null))

test('a job with no scan, no fingerprints and no AI review: trust says so plainly', async () => {
  const { res, json } = await run({ jobs: [job()] })
  assert.equal(res.status, 200)
  assert.deepEqual(json.data[0].trust, {
    scan: null,
    photos: [
      { url: `${B}/before.jpg`, role: 'before', status: 'not_fingerprinted', flags: [] },
      { url: `${B}/after.jpg`, role: 'after', status: 'not_fingerprinted', flags: [] },
    ],
    volume: null,
    aiReview: null,
  })
})

test('a job completed by a scan: coverage, GPS stats, capped volume, photo flags and the AI review', async () => {
  const { json } = await run({
    jobs: [job({ completionScanId: 42 })],
    route_scans: [
      {
        id: 42,
        verifiedCoverageRatio: 0.93,
        gpsPointsKept: 400,
        gpsPointsDropped: 120,
        gpsDropReasons: { speed: 110, duplicate_timestamp: 10 },
        gpsSuspect: true,
        // The hasScan lookup reads route_scans too; routeId is irrelevant here.
        routeId: 3,
      },
    ],
    scan_hotspots: [
      // Legacy row (no client column): stored value was the client's own.
      { scanId: 42, estimatedVolumeLiters: 1e6, clientEstimatedVolumeLiters: null, beforePhotoUrl: `${B}/h1.jpg`, afterPhotoUrl: `${B}/h1a.jpg` },
      { scanId: 42, estimatedVolumeLiters: 200, clientEstimatedVolumeLiters: 900, beforePhotoUrl: `${B}/h2.jpg`, afterPhotoUrl: null },
    ],
    photo_fingerprints: [
      { url: `${B}/before.jpg`, status: 'ok', flags: [] },
      { url: `${B}/after.jpg`, status: 'ok', flags: ['near_duplicate_before_after'] },
      { url: `${B}/h1a.jpg`, status: 'ok', flags: ['reused_other_scan'] },
      { url: `${B}/h2.jpg`, status: 'unverified', flags: [] },
    ],
    job_ai_reviews: [
      { jobId: 311, status: 'flagged', model: 'qwen-vl', verdict: { summary: 'after photo shows litter' }, createdAt: '2026-10-07T15:01:00Z', updatedAt: null },
    ],
  })
  const trust = json.data[0].trust
  assert.deepEqual(trust.scan, {
    scanId: 42,
    verifiedCoverageRatio: 0.93,
    gpsPointsKept: 400,
    gpsPointsDropped: 120,
    gpsDropReasons: { speed: 110, duplicate_timestamp: 10 },
    gpsSuspect: true,
  })
  assert.deepEqual(trust.volume, { clientLiters: 1e6 + 900, cappedLiters: 400, hotspots: 2 })
  assert.deepEqual(
    trust.photos.map((p: { role: string; status: string; flags: string[] }) => [p.role, p.status, p.flags]),
    [
      ['before', 'ok', []],
      ['after', 'ok', ['near_duplicate_before_after']],
      ['hotspot_after', 'ok', ['reused_other_scan']],
    ],
    'hotspot photos appear only when flagged',
  )
  assert.deepEqual(trust.aiReview, {
    status: 'flagged',
    model: 'qwen-vl',
    verdict: { summary: 'after photo shows litter' },
    reviewedAt: '2026-10-07T15:01:00Z',
  })
})
