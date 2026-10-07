import { test } from 'node:test'
import assert from 'node:assert/strict'
import { toMobileJob, type JobRow } from './mobile-job-response'

// A denied paid job exactly as the jobs table holds it after
// PATCH /api/jobs/:id/evidence and POST /api/admin/jobs/:id/deny.
const deniedJob: JobRow = {
  id: 311,
  blockId: 10,
  workerId: 7,
  status: 'pending_review',
  claimedAt: '2026-10-07 13:00:00+00',
  startedAt: '2026-10-07 13:10:00+00',
  completedAt: '2026-10-07 15:00:00+00',
  beforePhotos: [{ capturedAt: '2026-10-07T14:02:11.000Z', url: 'https://blob.example/spots/7/before.jpg' }],
  afterPhotos: [{ capturedAt: '2026-10-07T14:02:11.000Z', url: 'https://blob.example/spots/7/after.jpg' }],
  gpsPath: null,
  payoutAmount: '2500',
  payoutStatus: 'processing',
  payoutStripeTransferId: null,
  payoutCompletedAt: null,
  rating: null,
  ratingNotes: null,
  performedByAdminId: null,
  reviewStatus: 'denied',
  approvedByAdminId: null,
  deniedByAdminId: 1,
  denyReason: 'Photos do not show the block',
  reviewedAt: '2026-10-07 16:00:00+00',
  createdAt: '2026-10-07 12:00:00+00',
  updatedAt: '2026-10-07 16:00:00+00',
} as JobRow

test('toMobileJob sends before AND after evidence separately', () => {
  const out = toMobileJob(deniedJob, 4)
  assert.deepEqual(out.beforePhotos, deniedJob.beforePhotos)
  assert.deepEqual(out.afterPhotos, deniedJob.afterPhotos)
  // legacy combined field keeps both, after-photos no longer dropped
  assert.equal(out.photos?.length, 2)
})

test('toMobileJob carries the admin review fields', () => {
  const out = toMobileJob(deniedJob, 4)
  assert.equal(out.reviewStatus, 'denied')
  assert.equal(out.deniedByAdminId, 1)
  assert.equal(out.approvedByAdminId, null)
  assert.equal(out.denyReason, 'Photos do not show the block')
  assert.equal(out.reviewedAt, '2026-10-07T16:00:00.000Z')
  assert.equal(out.payoutStatus, 'processing')
})

test('toMobileJob with no evidence or review sends nulls', () => {
  const out = toMobileJob({ ...deniedJob, beforePhotos: null, afterPhotos: null, reviewStatus: null, deniedByAdminId: null, denyReason: null, reviewedAt: null }, null)
  assert.equal(out.beforePhotos, null)
  assert.equal(out.afterPhotos, null)
  assert.equal(out.photos, null)
  assert.equal(out.reviewStatus, null)
  assert.equal(out.reviewedAt, null)
})
