import assert from 'node:assert/strict'
import test from 'node:test'

import { commitWorkerVerification } from '@/app/api/admin/workers/[id]/verify/route'
import { commitAffectedAreaMutation } from '@/app/api/admin/blocks/[blockId]/boundaries/route'
import { commitRouteAssignmentMutation } from '@/app/api/admin/routes/[routeId]/assign/route'
import { commitJobCompletion } from '@/app/api/jobs/[id]/complete/route'
import {
  commitBlockSponsorshipMutation,
  commitSponsorshipMutation,
} from '@/app/api/stripe/webhooks/route'
import { GCC_CACHE_TAGS } from './cache-tags'
import { createPublicDataInvalidator } from './invalidation'

function collectingInvalidator() {
  const tags: string[] = []
  return {
    tags,
    invalidate: createPublicDataInvalidator((tag) => {
      tags.push(tag)
    }),
  }
}

test('job completion invalidates only impact stats after a real row commits', async () => {
  // Job completion changes jobs.completed/active in impact stats; public
  // stats has no jobs aggregate and must stay warm.
  const success = collectingInvalidator()
  const committedJob = {
    id: 41,
    workerId: 7,
    blockId: 12,
    status: 'completed',
    completedAt: '2026-08-08T15:00:00.000Z',
    payoutStatus: 'pending',
  }

  assert.deepEqual(
    await commitJobCompletion(async () => [committedJob], success.invalidate),
    [committedJob],
  )
  assert.deepEqual(success.tags, [GCC_CACHE_TAGS.impactStats])

  const failure = collectingInvalidator()
  await assert.rejects(
    commitJobCompletion(async () => {
      throw new Error('job update rejected')
    }, failure.invalidate),
    /job update rejected/,
  )
  assert.deepEqual(failure.tags, [])

  const zeroRow = collectingInvalidator()
  assert.deepEqual(await commitJobCompletion(async () => [], zeroRow.invalidate), [])
  assert.deepEqual(zeroRow.tags, [])
})

test('worker verification invalidates public+impact only after a non-empty update', async () => {
  const success = collectingInvalidator()
  const committedWorker = {
    id: 7,
    email: 'worker@example.test',
    name: 'Complete Worker',
    verificationStatus: 'approved',
    verificationGovernmentIdStatus: 'approved',
    verificationProofOfAddressStatus: 'approved',
  }

  assert.deepEqual(
    await commitWorkerVerification(async () => [committedWorker], success.invalidate),
    [committedWorker],
  )
  assert.deepEqual(success.tags, [GCC_CACHE_TAGS.publicStats, GCC_CACHE_TAGS.impactStats])

  const failure = collectingInvalidator()
  await assert.rejects(
    commitWorkerVerification(async () => {
      throw new Error('verification update rejected')
    }, failure.invalidate),
    /verification update rejected/,
  )
  assert.deepEqual(failure.tags, [])

  const zeroRow = collectingInvalidator()
  assert.deepEqual(await commitWorkerVerification(async () => [], zeroRow.invalidate), [])
  assert.deepEqual(zeroRow.tags, [])
})

test('completed block sponsorship invalidates contribution consumers and snapshots', async () => {
  const success = collectingInvalidator()
  const committedTransaction = {
    id: 93,
    sponsorId: 5,
    blockId: 12,
    amount: '10000',
    currency: 'usd',
    status: 'completed',
    stripeCheckoutSessionId: 'cs_complete_93',
  }

  assert.deepEqual(
    await commitBlockSponsorshipMutation(async () => [committedTransaction], success.invalidate),
    [committedTransaction],
  )
  assert.deepEqual(success.tags, [
    GCC_CACHE_TAGS.publicStats,
    GCC_CACHE_TAGS.impactStats,
    GCC_CACHE_TAGS.sponsorWall,
    GCC_CACHE_TAGS.supportWall,
    GCC_CACHE_TAGS.affectedAreaSnapshots,
  ])

  const failure = collectingInvalidator()
  await assert.rejects(
    commitBlockSponsorshipMutation(async () => {
      throw new Error('transaction insert rejected')
    }, failure.invalidate),
    /transaction insert rejected/,
  )
  assert.deepEqual(failure.tags, [])
})

test('general donation sponsorship invalidates summary/wall families without snapshots', async () => {
  const success = collectingInvalidator()
  const committed = { id: 11, status: 'completed', blockId: null }

  assert.deepEqual(
    await commitSponsorshipMutation(async () => [committed], success.invalidate),
    [committed],
  )
  assert.deepEqual(success.tags, [
    GCC_CACHE_TAGS.publicStats,
    GCC_CACHE_TAGS.impactStats,
    GCC_CACHE_TAGS.sponsorWall,
    GCC_CACHE_TAGS.supportWall,
  ])
})

test('affected-area writes invalidate only snapshots after the boundary update commits', async () => {
  const success = collectingInvalidator()
  const committedBlock = {
    id: 12,
    locationBoundary: {
      type: 'Polygon',
      coordinates: [[[-87.72, 41.86], [-87.71, 41.86], [-87.72, 41.86]]],
    },
  }

  assert.deepEqual(
    await commitAffectedAreaMutation(async () => [committedBlock], success.invalidate),
    [committedBlock],
  )
  assert.deepEqual(success.tags, [GCC_CACHE_TAGS.affectedAreaSnapshots])

  const failure = collectingInvalidator()
  await assert.rejects(
    commitAffectedAreaMutation(async () => {
      throw new Error('boundary update rejected')
    }, failure.invalidate),
    /boundary update rejected/,
  )
  assert.deepEqual(failure.tags, [])
})

test('route block-assignment invalidates impact stats after a new job is created', async () => {
  // Creating/cancelling a claimed job changes impact jobs.total/active.
  const success = collectingInvalidator()
  const job = { id: 55, status: 'claimed', workerId: 3, blockId: 12 }

  assert.deepEqual(
    await commitRouteAssignmentMutation(async () => job, success.invalidate),
    job,
  )
  assert.deepEqual(success.tags, [GCC_CACHE_TAGS.impactStats])

  const failure = collectingInvalidator()
  await assert.rejects(
    commitRouteAssignmentMutation(async () => {
      throw new Error('assignment insert rejected')
    }, failure.invalidate),
    /assignment insert rejected/,
  )
  assert.deepEqual(failure.tags, [])
})
