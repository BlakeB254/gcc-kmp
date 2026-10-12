import assert from 'node:assert/strict'
import test from 'node:test'

import { DEFAULT_ADMIN_CREDIT_LIMITS, adminCreditLimits, requiresSecondApproval, validateAdminCredit } from './admin-credit'

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

// ── Limits (env-configured, fail closed) ─────────────────────────────────────

test('adminCreditLimits: defaults are low — $500/admin/day, $250/target/day, second approval above $100', () => {
  assert.deepEqual(adminCreditLimits({}), DEFAULT_ADMIN_CREDIT_LIMITS)
  assert.deepEqual(DEFAULT_ADMIN_CREDIT_LIMITS, {
    perAdminDailyCents: 50_000,
    perTargetDailyCents: 25_000,
    secondApprovalThresholdCents: 10_000,
  })
})

test('adminCreditLimits: env overrides are whole non-negative cents; anything else falls back to the default', () => {
  assert.deepEqual(
    adminCreditLimits({
      ADMIN_CREDIT_DAILY_CAP_PER_ADMIN_CENTS: '100000',
      ADMIN_CREDIT_DAILY_CAP_PER_TARGET_CENTS: '0',
      ADMIN_CREDIT_SECOND_APPROVAL_THRESHOLD_CENTS: ' 5000 ',
    }),
    { perAdminDailyCents: 100_000, perTargetDailyCents: 0, secondApprovalThresholdCents: 5_000 },
  )
  for (const bad of ['', 'abc', '-1', '1.5', '1e5', '$500', 'Infinity']) {
    assert.deepEqual(
      adminCreditLimits({
        ADMIN_CREDIT_DAILY_CAP_PER_ADMIN_CENTS: bad,
        ADMIN_CREDIT_DAILY_CAP_PER_TARGET_CENTS: bad,
        ADMIN_CREDIT_SECOND_APPROVAL_THRESHOLD_CENTS: bad,
      }),
      DEFAULT_ADMIN_CREDIT_LIMITS,
      JSON.stringify(bad),
    )
  }
})

test('requiresSecondApproval: strictly above the threshold', () => {
  assert.equal(requiresSecondApproval(10_000, DEFAULT_ADMIN_CREDIT_LIMITS), false)
  assert.equal(requiresSecondApproval(10_001, DEFAULT_ADMIN_CREDIT_LIMITS), true)
  assert.equal(requiresSecondApproval(1, { ...DEFAULT_ADMIN_CREDIT_LIMITS, secondApprovalThresholdCents: 0 }), true)
})
