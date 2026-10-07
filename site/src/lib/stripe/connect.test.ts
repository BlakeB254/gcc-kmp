import assert from 'node:assert/strict'
import test, { mock } from 'node:test'

import { stripe } from './client'
import { processAdminPayout } from './connect'
import { payoutIdempotencyKey } from '../stripe-idempotency'

type Opts = { idempotencyKey?: string; stripeAccount?: string }

test('payoutIdempotencyKey: deterministic per payouts row and step', () => {
  assert.equal(payoutIdempotencyKey({ kind: 'admin_payout', payoutId: '42' }, 'transfer'), 'payout-42-transfer')
  assert.equal(payoutIdempotencyKey({ kind: 'admin_payout', payoutId: '42' }, 'payout'), 'payout-42-payout')
  assert.equal(payoutIdempotencyKey({ kind: 'job', jobId: '9' }, 'transfer'), 'job-9-transfer')
})

test('processAdminPayout: transfer and payout carry deterministic idempotency keys', async () => {
  const transfer = mock.method(stripe.transfers, 'create', async () => ({ id: 'tr_1' }))
  const payout = mock.method(stripe.payouts, 'create', async () => ({ id: 'po_1' }))
  try {
    await processAdminPayout('acct_1', 5000, '42', '7', false)
    const tOpts = transfer.mock.calls[0].arguments[1] as Opts
    assert.equal(tOpts.idempotencyKey, 'payout-42-transfer')
    const pOpts = payout.mock.calls[0].arguments[1] as Opts
    assert.equal(pOpts.idempotencyKey, 'payout-42-payout')
    assert.equal(pOpts.stripeAccount, 'acct_1')
  } finally {
    transfer.mock.restore()
    payout.mock.restore()
  }
})

test('processAdminPayout: instant attempt has its own key; the standard fallback uses payout-{id}-payout', async () => {
  const transfer = mock.method(stripe.transfers, 'create', async () => ({ id: 'tr_1' }))
  let n = 0
  const payout = mock.method(stripe.payouts, 'create', async () => {
    if (n++ === 0) throw new Error('instant not available')
    return { id: 'po_2' }
  })
  const warn = mock.method(console, 'warn', () => undefined)
  try {
    await processAdminPayout('acct_1', 5000, '42', '7', true)
    const keys = payout.mock.calls.map((c) => (c.arguments[1] as Opts).idempotencyKey)
    assert.deepEqual(keys, ['payout-42-payout-instant', 'payout-42-payout'])
  } finally {
    transfer.mock.restore()
    payout.mock.restore()
    warn.mock.restore()
  }
})
