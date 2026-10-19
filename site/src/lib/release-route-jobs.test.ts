import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  ACTIVE_JOB_STATUSES,
  isReleasableJobStatus,
  releasedJobStatus,
  releaseJobPatch,
  routeCloseConflict,
  parseReleaseFlag,
} from './release-route-jobs'

// Why this module exists: job 24 (worker 3, NL-S01) sat `in_progress` for six
// weeks after its route was unpublished. The unpublish/archive verbs never
// looked at jobs, delete refused because of the job, decline only accepts
// `claimed`, and the admin UI had no unassign. One definition of "release a
// job" — used by assign-DELETE and by the close verbs — is the fix.

test('active statuses are the three a worker can be mid-way through', () => {
  assert.deepEqual([...ACTIVE_JOB_STATUSES], ['claimed', 'in_progress', 'pending_review'])
})

test('only claimed and in_progress can be released; pending_review holds submitted work', () => {
  assert.equal(isReleasableJobStatus('claimed'), true)
  assert.equal(isReleasableJobStatus('in_progress'), true)
  assert.equal(isReleasableJobStatus('pending_review'), false)
  assert.equal(isReleasableJobStatus('completed'), false)
  assert.equal(isReleasableJobStatus(null), false)
})

test('a paid job goes back to available; a $0 test job is tombstoned as disputed', () => {
  // Pre-existing rule from assign-DELETE and decline, kept in one place. The
  // enum has no `cancelled`, so $0 jobs must not be re-listed as claimable.
  assert.equal(releasedJobStatus(1500), 'available')
  assert.equal(releasedJobStatus(0), 'disputed')
  assert.equal(releasedJobStatus(null), 'disputed')
})

test('release patch detaches the worker and clears every timing/payout field', () => {
  const patch = releaseJobPatch({ payoutAmount: '2500' }, '2026-09-13T00:00:00.000Z')
  assert.deepEqual(patch, {
    workerId: null,
    status: 'available',
    claimedAt: null,
    startedAt: null,
    completedAt: null,
    payoutStatus: null,
    reviewStatus: null,
    deniedByAdminId: null,
    denyReason: null,
    reviewedAt: null,
    completionScanId: null,
    updatedAt: '2026-09-13T00:00:00.000Z',
  })
})

test('release patch on a $0 job uses the disputed tombstone', () => {
  assert.equal(releaseJobPatch({ payoutAmount: '0' }, 'now').status, 'disputed')
  assert.equal(releaseJobPatch({ payoutAmount: null }, 'now').status, 'disputed')
})

test('conflict body matches the shape route-DELETE already returns', () => {
  // The web "blocked" dialog and the KMP RouteDeleteSummary parse this shape.
  const body = routeCloseConflict('unpublish', [
    { id: 24, status: 'in_progress' },
    { id: 25, status: 'claimed' },
  ])
  assert.equal(body.success, false)
  assert.deepEqual(body.conflicts, { activeJobs: 2, pendingOffers: 0 })
  assert.match(body.error, /2 active jobs/)
  assert.match(body.error, /unpublish/)
  assert.deepEqual(body.releasable, { count: 2, heldForReview: 0 })
})

test('conflict body says when review-held jobs block the release', () => {
  const body = routeCloseConflict('archive', [
    { id: 1, status: 'pending_review' },
    { id: 2, status: 'in_progress' },
  ])
  assert.deepEqual(body.releasable, { count: 1, heldForReview: 1 })
})

test('release flag is only honoured as a literal true', () => {
  assert.equal(parseReleaseFlag({ releaseActiveJobs: true }), true)
  assert.equal(parseReleaseFlag({ releaseActiveJobs: 'true' }), false)
  assert.equal(parseReleaseFlag({}), false)
  assert.equal(parseReleaseFlag(null), false)
  assert.equal(parseReleaseFlag('junk'), false)
})
