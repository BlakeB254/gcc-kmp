import assert from 'node:assert/strict'
import test from 'node:test'

import { PAYOUT_CHANGE_COOLDOWN_MS, scopedWithdrawIdempotencyKey, withdrawRefusal } from './withdraw-guards'

const NOW = Date.parse('2026-10-07T12:00:00.000Z')
const hoursAgo = (h: number) => new Date(NOW - h * 3_600_000).toISOString()
const good = { verificationStatus: 'approved', emailVerified: true, payoutMethodChangedAt: null, lastAddressChangeAt: null }

test('withdrawRefusal: an approved, email-verified worker with no recent change may withdraw', () => {
  assert.equal(withdrawRefusal(good, 'stripe', NOW), null)
  assert.equal(withdrawRefusal(good, 'check', NOW), null)
})

test('withdrawRefusal: unapproved or unverified-email workers are refused (both methods)', () => {
  for (const method of ['stripe', 'check'] as const) {
    assert.equal(withdrawRefusal({ ...good, verificationStatus: 'pending' }, method, NOW)?.status, 403)
    assert.equal(withdrawRefusal({ ...good, verificationStatus: null }, method, NOW)?.status, 403)
    assert.equal(withdrawRefusal({ ...good, emailVerified: false }, method, NOW)?.status, 403)
    assert.equal(withdrawRefusal({ ...good, emailVerified: null }, method, NOW)?.status, 403)
  }
})

test('withdrawRefusal: a payout-method change holds cash outs for 24h', () => {
  assert.equal(PAYOUT_CHANGE_COOLDOWN_MS, 24 * 3_600_000)
  assert.equal(withdrawRefusal({ ...good, payoutMethodChangedAt: hoursAgo(1) }, 'stripe', NOW)?.status, 409)
  assert.equal(withdrawRefusal({ ...good, payoutMethodChangedAt: hoursAgo(23.9) }, 'check', NOW)?.status, 409)
  assert.equal(withdrawRefusal({ ...good, payoutMethodChangedAt: hoursAgo(24.1) }, 'stripe', NOW), null)
})

test('withdrawRefusal: a home-address change holds CHECK cash outs only', () => {
  const w = { ...good, lastAddressChangeAt: hoursAgo(2) }
  assert.equal(withdrawRefusal(w, 'check', NOW)?.status, 409)
  assert.equal(withdrawRefusal(w, 'stripe', NOW), null)
})

test('scopedWithdrawIdempotencyKey: the same client key from two workers never collides', () => {
  assert.notEqual(scopedWithdrawIdempotencyKey(1, 'abcdefgh'), scopedWithdrawIdempotencyKey(2, 'abcdefgh'))
  assert.equal(scopedWithdrawIdempotencyKey(7, 'abcdefgh'), 'w7:abcdefgh')
  // A crafted key can't impersonate another worker's namespace.
  assert.notEqual(scopedWithdrawIdempotencyKey(1, '2:abcdefgh'), scopedWithdrawIdempotencyKey(12, 'abcdefgh'))
})
