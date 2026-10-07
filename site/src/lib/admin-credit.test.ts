import assert from 'node:assert/strict'
import test from 'node:test'

import { validateAdminCredit } from './admin-credit'

const ok = { amountCents: 5000, reason: 'Volunteer day bonus', idempotencyKey: 'key-12345678' }

test('validateAdminCredit: a valid credit to someone else passes', () => {
  const d = validateAdminCredit({ adminWorkerId: 1, targetWorkerId: 2, body: ok })
  assert.deepEqual(d, { ok: true, amountCents: 5000, reason: 'Volunteer day bonus', idempotencyKey: 'key-12345678' })
})

test('validateAdminCredit: an admin cannot credit their own worker account', () => {
  const d = validateAdminCredit({ adminWorkerId: 2, targetWorkerId: 2, body: ok })
  assert.equal(d.ok, false)
  assert.equal(!d.ok && d.status, 403)
})

test('validateAdminCredit: a reason is required', () => {
  for (const body of [{ ...ok, reason: undefined }, { ...ok, reason: '   ' }]) {
    const d = validateAdminCredit({ adminWorkerId: 1, targetWorkerId: 2, body })
    assert.equal(!d.ok && d.status, 400)
  }
})

test('validateAdminCredit: older admin builds send the reason as `note`', () => {
  const d = validateAdminCredit({ adminWorkerId: 1, targetWorkerId: 2, body: { amountCents: 100, note: 'Correction', idempotencyKey: 'key-12345678' } })
  assert.equal(d.ok && d.reason, 'Correction')
})

test('validateAdminCredit: job money must go through approve', () => {
  const withJobId = validateAdminCredit({ adminWorkerId: 1, targetWorkerId: 2, body: { ...ok, jobId: 12 } })
  assert.equal(!withJobId.ok && withJobId.status, 400)
  for (const reason of ['Payment for job #12', 'job 12 payout', 'Jobs 3 and 4']) {
    const d = validateAdminCredit({ adminWorkerId: 1, targetWorkerId: 2, body: { ...ok, reason } })
    assert.equal(!d.ok && d.status, 400, reason)
  }
})

test('validateAdminCredit: amount bounds still apply', () => {
  for (const amountCents of [0, -5, 1.5, 100_001, '500']) {
    const d = validateAdminCredit({ adminWorkerId: 1, targetWorkerId: 2, body: { ...ok, amountCents } })
    assert.equal(!d.ok && d.status, 400, String(amountCents))
  }
})
