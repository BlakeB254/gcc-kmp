import assert from 'node:assert/strict'
import test from 'node:test'

import { PgDialect } from 'drizzle-orm/pg-core'

import { releaseJobPatch } from './release-route-jobs'
import {
  TEST_JOB_CLAIM_TTL_MINUTES,
  TEST_JOB_IN_PROGRESS_TTL_HOURS,
  isExpiredTestJob,
  notExpiredTestJobCondition,
  planStaleJobSweep,
  staleJobCondition,
  sweepStaleTestJobs,
  type StaleJobCandidate,
  type StaleJobRelease,
} from './stale-test-jobs'

const NOW = Date.parse('2026-10-07T12:00:00.000Z')
const minutesAgo = (m: number) => new Date(NOW - m * 60_000).toISOString()
const hoursAgo = (h: number) => minutesAgo(h * 60)

function job(overrides: Partial<StaleJobCandidate>): StaleJobCandidate {
  return {
    id: 1,
    blockId: 10,
    workerId: 7,
    status: 'claimed',
    payoutAmount: '0',
    claimedAt: null,
    startedAt: null,
    ...overrides,
  }
}

const claimedOld = job({ id: 1, status: 'claimed', claimedAt: minutesAgo(61) })
const claimedRecent = job({ id: 2, status: 'claimed', claimedAt: minutesAgo(30) })
const inProgressOld = job({ id: 3, status: 'in_progress', claimedAt: hoursAgo(30), startedAt: hoursAgo(25) })
const inProgressRecent = job({ id: 4, status: 'in_progress', claimedAt: hoursAgo(3), startedAt: hoursAgo(2) })
const paidClaimedOld = job({ id: 5, status: 'claimed', claimedAt: minutesAgo(600), payoutAmount: '2500' })
const paidInProgressOld = job({ id: 6, status: 'in_progress', startedAt: hoursAgo(72), payoutAmount: '2500' })
const nullPayoutOld = job({ id: 7, status: 'in_progress', startedAt: hoursAgo(48), payoutAmount: null })
// Job 24's shape: $0, in_progress since 2026-07-30.
const job24 = job({ id: 24, status: 'in_progress', claimedAt: '2026-07-30T15:00:00.000Z', startedAt: '2026-07-30T15:05:00.000Z' })

test('TTL constants match the app start window and 24h abandonment', () => {
  assert.equal(TEST_JOB_CLAIM_TTL_MINUTES, 60)
  assert.equal(TEST_JOB_IN_PROGRESS_TTL_HOURS, 24)
})

test('isExpiredTestJob: $0 claimed >60m without start, $0 in_progress >24h', () => {
  assert.equal(isExpiredTestJob(claimedOld, NOW), true)
  assert.equal(isExpiredTestJob(claimedRecent, NOW), false)
  assert.equal(isExpiredTestJob(inProgressOld, NOW), true)
  assert.equal(isExpiredTestJob(inProgressRecent, NOW), false)
  assert.equal(isExpiredTestJob(job24, NOW), true)
  assert.equal(isExpiredTestJob(nullPayoutOld, NOW), true, 'null payout is coalesced to 0')
})

test('isExpiredTestJob: paid jobs never expire', () => {
  assert.equal(isExpiredTestJob(paidClaimedOld, NOW), false)
  assert.equal(isExpiredTestJob(paidInProgressOld, NOW), false)
})

test('isExpiredTestJob: other statuses and a claimed job that has a start stamp are untouched', () => {
  for (const status of ['available', 'pending_review', 'completed', 'disputed']) {
    assert.equal(isExpiredTestJob(job({ status, claimedAt: hoursAgo(100), startedAt: hoursAgo(99) }), NOW), false)
  }
  assert.equal(isExpiredTestJob(job({ status: 'claimed', claimedAt: hoursAgo(5), startedAt: hoursAgo(4) }), NOW), false)
})

test('isExpiredTestJob: in_progress with no started_at ages from claimed_at', () => {
  assert.equal(isExpiredTestJob(job({ status: 'in_progress', claimedAt: hoursAgo(25) }), NOW), true)
  assert.equal(isExpiredTestJob(job({ status: 'in_progress', claimedAt: hoursAgo(2) }), NOW), false)
})

test('planStaleJobSweep releases stale $0 jobs with releaseJobPatch and only reports paid ones', () => {
  const plan = planStaleJobSweep(
    [claimedOld, claimedRecent, inProgressOld, inProgressRecent, paidClaimedOld, paidInProgressOld, job24],
    NOW,
  )
  const nowIso = new Date(NOW).toISOString()
  assert.deepEqual(
    plan.release.map((r) => [r.job.id, r.reason]),
    [
      [1, 'claim-not-started'],
      [3, 'in-progress-abandoned'],
      [24, 'in-progress-abandoned'],
    ],
  )
  for (const r of plan.release) {
    assert.deepEqual(r.patch, releaseJobPatch(r.job, nowIso))
    assert.equal(r.patch.status, 'disputed')
    assert.equal(r.patch.workerId, null)
  }
  assert.deepEqual(
    plan.paidStale.map((p) => [p.id, p.reason]),
    [
      [5, 'claim-not-started'],
      [6, 'in-progress-abandoned'],
    ],
  )
})

test('sweepStaleTestJobs applies only $0 releases and reports what actually updated', async () => {
  let applied: StaleJobRelease[] = []
  const result = await sweepStaleTestJobs(
    {
      findCandidates: async () => [claimedOld, inProgressOld, inProgressRecent, paidInProgressOld],
      // Simulate job 3 having been advanced by its worker between read and write.
      applyReleases: async (releases) => {
        applied = releases
        return releases.map((r) => r.job.id).filter((id) => id !== 3)
      },
    },
    NOW,
  )
  assert.deepEqual(applied.map((r) => r.job.id), [1, 3])
  assert.deepEqual(result.released, [{ id: 1, workerId: 7, reason: 'claim-not-started', status: 'disputed' }])
  assert.deepEqual(result.paidStale.map((p) => p.id), [6])
  assert.equal(result.examined, 4)
})

test('sweepStaleTestJobs does not write when nothing is releasable', async () => {
  let called = false
  const result = await sweepStaleTestJobs(
    {
      findCandidates: async () => [claimedRecent, paidClaimedOld],
      applyReleases: async () => {
        called = true
        return []
      },
    },
    NOW,
  )
  assert.equal(called, false)
  assert.deepEqual(result.released, [])
  assert.deepEqual(result.paidStale.map((p) => p.id), [5])
})

test('me/jobs filter SQL excludes only $0 jobs past both TTLs', () => {
  const dialect = new PgDialect()
  const { sql: text, params } = dialect.sqlToQuery(notExpiredTestJobCondition())
  const flat = text.replace(/\s+/g, ' ')
  assert.match(flat, /^not coalesce\(\(coalesce\("jobs"\."payout_amount", 0\) = 0 and /)
  assert.match(flat, /, false\)$/)
  assert.match(flat, /"jobs"\."status" = 'claimed' and "jobs"\."started_at" is null and "jobs"\."claimed_at" < now\(\) - make_interval\(mins => \$\d+::int\)/)
  assert.match(flat, /"jobs"\."status" = 'in_progress' and coalesce\("jobs"\."started_at", "jobs"\."claimed_at"\) < now\(\) - make_interval\(hours => \$\d+::int\)/)
  assert.deepEqual(params, [TEST_JOB_CLAIM_TTL_MINUTES, TEST_JOB_IN_PROGRESS_TTL_HOURS])
})

test('sweep candidate SQL does not filter on payout (paid jobs are found so they can be counted)', () => {
  const { sql: text } = new PgDialect().sqlToQuery(staleJobCondition())
  assert.doesNotMatch(text, /payout_amount/)
})
