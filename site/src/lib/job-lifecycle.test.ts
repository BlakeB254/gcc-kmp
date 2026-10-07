import assert from 'node:assert/strict'
import test from 'node:test'

import {
  approvalGuard,
  claimAvailableBlock,
  claimAvailableJob,
  claimJobForApproval,
  completeJob,
  completionStatusFor,
  evidenceAllowedForStatus,
  isOwnEvidenceUrl,
  ownBlobHost,
  routePayoutCentsForBlock,
  startClaimedJob,
} from './job-lifecycle'
import { recordingDb, when } from './testing/recording-db'

const NOW = '2026-10-07T12:00:00.000Z'
/** Drizzle wraps every condition in parentheses; strip them to assert on shape. */
const flat = (sql: string) => sql.replace(/[()]/g, '')

// A full jobs row in pg-proxy array form (column order of the jobs table).
function jobRow(overrides: { id?: number; status?: string; workerId?: number | null; payoutAmount?: string | null; payoutStatus?: string | null } = {}) {
  return [
    overrides.id ?? 1, 10, overrides.workerId === undefined ? 7 : overrides.workerId, overrides.status ?? 'claimed',
    NOW, null, null, // claimedAt, startedAt, completedAt
    null, null, null, // beforePhotos, afterPhotos, gpsPath
    overrides.payoutAmount === undefined ? '2500' : overrides.payoutAmount, overrides.payoutStatus ?? null, null, null,
    null, null, // rating, ratingNotes
    null, // performedByAdminId
    null, null, null, null, null, // review fields
    NOW, NOW,
  ]
}

// ── Completion → review ─────────────────────────────────────────────────────

test('completionStatusFor: paid job → pending_review, $0 / null → completed', () => {
  assert.equal(completionStatusFor('2500'), 'pending_review')
  assert.equal(completionStatusFor(1), 'pending_review')
  assert.equal(completionStatusFor('0'), 'completed')
  assert.equal(completionStatusFor(null), 'completed')
})

test('completeJob: a worker completing a PAID job moves it to pending_review, guarded on in_progress + ownership', async () => {
  const { db, queries } = recordingDb(when(/^update "jobs"/, [jobRow({ status: 'pending_review' })]))
  const row = await completeJob(db, { id: 1, payoutAmount: '2500', payoutStatus: null }, { workerId: 7, isAdmin: false }, NOW)
  assert.equal(row?.status, 'pending_review')
  const q = queries[0]
  assert.match(q.sql, /^update "jobs" set "status" = \$1/)
  assert.equal(q.params[0], 'pending_review')
  assert.equal(q.params[2], null, 'payoutStatus left as-is (NULL) — approve settles it')
  assert.match(flat(q.sql), /"jobs"\."status" = \$\d+ and "jobs"\."worker_id" = \$\d+/)
  assert.ok(q.params.includes('in_progress'))
  assert.ok(q.params.includes(7))
})

test('completeJob: a $0 test job completes outright and is marked settled', async () => {
  const { db, queries } = recordingDb(when(/^update "jobs"/, [jobRow({ status: 'completed', payoutAmount: '0' })]))
  await completeJob(db, { id: 1, payoutAmount: '0', payoutStatus: null }, { workerId: 7, isAdmin: false }, NOW)
  assert.equal(queries[0].params[0], 'completed')
  assert.equal(queries[0].params[2], 'completed')
})

test('completeJob: 0 rows (already completed / raced) → null so the route answers 409', async () => {
  const { db } = recordingDb()
  assert.equal(await completeJob(db, { id: 1, payoutAmount: '2500', payoutStatus: null }, { workerId: 7, isAdmin: false }, NOW), null)
})

test('completeJob: an admin may complete from claimed/in_progress only — never from a terminal state', async () => {
  const { db, queries } = recordingDb()
  await completeJob(db, { id: 1, payoutAmount: '2500', payoutStatus: null }, { workerId: 99, isAdmin: true }, NOW)
  assert.match(queries[0].sql, /"jobs"\."status" in \(\$\d+, \$\d+\)/)
  assert.ok(queries[0].params.includes('claimed'))
  assert.ok(queries[0].params.includes('in_progress'))
  assert.ok(!queries[0].params.includes(99), 'admin path has no ownership filter')
})

// ── Approve ─────────────────────────────────────────────────────────────────

const pending = { jobStatus: 'pending_review', reviewStatus: null, workerId: 7, payoutAmount: '2500' }

test('approvalGuard: only pending_review jobs, never denied, never your own', () => {
  assert.equal(approvalGuard(pending, 1), null)
  assert.equal(approvalGuard({ ...pending, jobStatus: 'completed' }, 1)?.status, 409)
  assert.equal(approvalGuard({ ...pending, jobStatus: 'in_progress' }, 1)?.status, 409)
  assert.equal(approvalGuard({ ...pending, reviewStatus: 'denied' }, 1)?.status, 409)
  assert.equal(approvalGuard({ ...pending, workerId: null }, 1)?.status, 409)
  assert.equal(approvalGuard({ ...pending, payoutAmount: '0' }, 1)?.status, 409)
  const self = approvalGuard(pending, 7)
  assert.equal(self?.status, 403, 'an admin cannot approve their own job')
})

test('claimJobForApproval: atomic UPDATE re-checks pending_review, treats NULL payoutStatus as unpaid, excludes self', async () => {
  const { db, queries } = recordingDb(when(/^update "jobs"/, [[1]]))
  const claimed = await claimJobForApproval(db, 1, 3, NOW)
  assert.deepEqual(claimed, { id: 1 })
  const { sql, params } = queries[0]
  assert.match(sql, /"jobs"\."status" = \$\d+/)
  assert.ok(params.includes('pending_review'))
  // NULL payoutStatus must be approvable: `NULL NOT IN (...)` alone is NULL → excluded.
  assert.match(flat(sql), /"jobs"\."payout_status" is null or "jobs"\."payout_status" not in \$\d+, \$\d+ and/)
  assert.match(sql, /"jobs"\."payout_stripe_transfer_id" is null/)
  assert.match(sql, /"jobs"\."worker_id" <> \$\d+/)
  assert.ok(params.includes(3))
})

test('claimJobForApproval: a NULL-payoutStatus job is approvable once; the second approve gets 0 rows → 409', async () => {
  // Emulate the row: the first UPDATE flips status to completed; the
  // second finds status != pending_review and matches nothing.
  const job = { status: 'pending_review', payoutStatus: null as string | null }
  const { db } = recordingDb((q) => {
    if (!/^update "jobs"/.test(q.sql)) return undefined
    const matches = job.status === 'pending_review' && (job.payoutStatus == null || !['completed', 'processing'].includes(job.payoutStatus))
    if (!matches) return []
    job.status = 'completed'
    job.payoutStatus = 'processing'
    return [[1]]
  })
  assert.deepEqual(await claimJobForApproval(db, 1, 3, NOW), { id: 1 })
  assert.equal(await claimJobForApproval(db, 1, 3, NOW), null)
})

// ── Claim / start races ─────────────────────────────────────────────────────

test('claimAvailableJob: guarded on status=available; 0 rows → null (409)', async () => {
  const { db, queries } = recordingDb()
  assert.equal(await claimAvailableJob(db, 5, 7, NOW), null)
  assert.match(flat(queries[0].sql), /where "jobs"\."id" = \$\d+ and "jobs"\."status" = \$\d+ returning/)
  assert.ok(queries[0].params.includes('available'))
})

test('claimAvailableJob: the winner gets the row', async () => {
  const { db } = recordingDb(when(/^update "jobs"/, [jobRow({ id: 5, status: 'claimed' })]))
  const row = await claimAvailableJob(db, 5, 7, NOW)
  assert.equal(row?.id, 5)
  assert.equal(row?.status, 'claimed')
})

test('startClaimedJob: worker start is guarded on status=claimed AND ownership; 0 rows → null (409)', async () => {
  const { db, queries } = recordingDb()
  assert.equal(await startClaimedJob(db, 5, { workerId: 7, isAdmin: false }, NOW), null)
  const { sql, params } = queries[0]
  assert.match(flat(sql), /"jobs"\."status" = \$\d+ and "jobs"\."worker_id" = \$\d+/)
  assert.ok(params.includes('claimed'))
})

test('startClaimedJob: admin start still requires status=claimed', async () => {
  const { db, queries } = recordingDb()
  await startClaimedJob(db, 5, { workerId: 99, isAdmin: true }, NOW)
  assert.match(queries[0].sql, /"jobs"\."status" = \$\d+/)
  assert.doesNotMatch(queries[0].sql, /"jobs"\."worker_id" = /)
})

test('claimAvailableBlock: guarded on blocks.status=available; 0 rows → null (409)', async () => {
  const { db, queries } = recordingDb()
  assert.equal(await claimAvailableBlock(db, 3, NOW), null)
  assert.match(flat(queries[0].sql), /^update "blocks" set "status" = \$1.* where "blocks"\."id" = \$\d+ and "blocks"\."status" = \$\d+/)
  assert.ok(queries[0].params.includes('available'))
})

test('routePayoutCentsForBlock: payout comes from routes.payout_amount (cents), not blocks.funding_goal', async () => {
  const { db, queries } = recordingDb(when(/from "routes"/, [['2500']]))
  // funding_goal=100 on the block would have been a $1 job.
  assert.equal(await routePayoutCentsForBlock(db, { routeId: 12 }), 2500)
  assert.match(queries[0].sql, /select "payout_amount" from "routes"/)
})

test('routePayoutCentsForBlock: no route → null (claim refused, never a guessed amount)', async () => {
  const { db, queries } = recordingDb()
  assert.equal(await routePayoutCentsForBlock(db, { routeId: null }), null)
  assert.equal(queries.length, 0)
})

// ── Evidence ────────────────────────────────────────────────────────────────

test('evidenceAllowedForStatus: only while claimed / in_progress', () => {
  assert.equal(evidenceAllowedForStatus('claimed'), true)
  assert.equal(evidenceAllowedForStatus('in_progress'), true)
  for (const s of ['available', 'pending_review', 'completed', 'disputed', null]) {
    assert.equal(evidenceAllowedForStatus(s), false, String(s))
  }
})

test('ownBlobHost: derived from the Vercel Blob read-write token', () => {
  assert.equal(ownBlobHost('vercel_blob_rw_AbC123xyz_secretpart'), 'abc123xyz.public.blob.vercel-storage.com')
  assert.equal(ownBlobHost(undefined), null)
  assert.equal(ownBlobHost('garbage'), null)
})

test('isOwnEvidenceUrl: only our blob store, under the worker’s own spots/ prefix', () => {
  const host = 'abc123xyz.public.blob.vercel-storage.com'
  const ok = `https://${host}/spots/7/1700000000000-photo_0-Xyz.jpg`
  assert.equal(isOwnEvidenceUrl(ok, 7, host), true)
  assert.equal(isOwnEvidenceUrl(ok, 8, host), false, 'another worker’s upload')
  assert.equal(isOwnEvidenceUrl(`https://evil.example.com/spots/7/a.jpg`, 7, host), false, 'foreign host')
  assert.equal(isOwnEvidenceUrl(`https://other.public.blob.vercel-storage.com/spots/7/a.jpg`, 7, host), false, 'someone else’s blob store')
  assert.equal(isOwnEvidenceUrl(`http://${host}/spots/7/a.jpg`, 7, host), false, 'not https')
  assert.equal(isOwnEvidenceUrl(`https://${host}/verification/7/a.jpg`, 7, host), false, 'wrong prefix')
  assert.equal(isOwnEvidenceUrl('not a url', 7, host), false)
  // Without a derivable store host, any Vercel Blob public host — still bound to the worker prefix.
  assert.equal(isOwnEvidenceUrl(`https://zzz.public.blob.vercel-storage.com/spots/7/a.jpg`, 7, null), true)
  assert.equal(isOwnEvidenceUrl(`https://evil.example.com/spots/7/a.jpg`, 7, null), false)
})
