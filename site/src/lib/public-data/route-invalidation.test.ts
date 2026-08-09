import assert from 'node:assert/strict'
import test from 'node:test'

import { commitWorkerVerification } from '@/app/api/admin/workers/[id]/verify/route'
import { commitAffectedAreaMutation } from '@/app/api/admin/blocks/[blockId]/boundaries/route'
import { commitJobCompletion } from '@/app/api/jobs/[id]/complete/route'
import { commitBlockSponsorshipMutation } from '@/app/api/stripe/webhooks/route'
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

test('job completion invalidates public impact families only after the database update commits', async () => {
  // Catches invalidating before the job UPDATE resolves, which would expose
  // stale data again after a rejected mutation and waste a cache refresh.
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
    await commitJobCompletion(async () => committedJob, success.invalidate),
    committedJob,
  )
  assert.deepEqual(success.tags, [GCC_CACHE_TAGS.publicStats, GCC_CACHE_TAGS.impactStats])

  const failure = collectingInvalidator()
  await assert.rejects(
    commitJobCompletion(async () => {
      throw new Error('job update rejected')
    }, failure.invalidate),
    /job update rejected/,
  )
  assert.deepEqual(failure.tags, [])
})

test('worker verification invalidates verification families only after the update commits', async () => {
  // Catches validation/DB failures invalidating worker counts even though the
  // authoritative verification state did not change.
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
    await commitWorkerVerification(async () => committedWorker, success.invalidate),
    committedWorker,
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
})

test('completed sponsor writes invalidate every contribution consumer and rejected writes invalidate none', async () => {
  // Catches the webhook leaving a wall or affected-area snapshot stale after
  // a completed contribution, or invalidating for a rejected DB write.
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
    await commitBlockSponsorshipMutation(async () => committedTransaction, success.invalidate),
    committedTransaction,
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

test('affected-area writes invalidate only snapshots after the boundary update commits', async () => {
  // Catches invalidating real mobile delta families or public summaries for a
  // geometry-only write, and catches invalidation on rejected geometry writes.
  const success = collectingInvalidator()
  const committedBlock = {
    id: 12,
    locationBoundary: {
      type: 'Polygon',
      coordinates: [[[-87.72, 41.86], [-87.71, 41.86], [-87.72, 41.86]]],
    },
  }

  assert.deepEqual(
    await commitAffectedAreaMutation(async () => committedBlock, success.invalidate),
    committedBlock,
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
