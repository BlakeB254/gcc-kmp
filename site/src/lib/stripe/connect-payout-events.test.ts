import assert from 'node:assert/strict'
import test, { mock } from 'node:test'

import {
  creditWalletForReversedTransfer,
  recordConnectPayoutFailed,
  recordConnectPayoutPaid,
  recordJobPayoutPaid,
} from './connect-payout-events'
import { recordingDb, when } from '../testing/recording-db'

const touchesWallet = (sql: string) =>
  (/worker_wallet_entries/.test(sql) && /insert into/.test(sql)) || /update "workers"/.test(sql)

test('payout.failed: marks the payout failed, records where the money is, and does NOT credit the wallet', async () => {
  const { db, queries } = recordingDb(when(/^select .* from "payouts"/, [[7, 5000, 'tr_123']]))
  const record = await recordConnectPayoutFailed(db, {
    payoutRowId: 42,
    stripePayoutId: 'po_9',
    connectedAccountId: 'acct_77',
    failureCode: 'account_closed',
  })
  assert.equal(record?.workerId, 7)
  assert.equal(record?.amountCents, 5000)

  assert.equal(queries.some((q) => touchesWallet(q.sql)), false, 'no wallet credit / balance bump')
  const update = queries.find((q) => /^update "payouts"/.test(q.sql))
  assert.ok(update)
  assert.ok(update.params.includes('failed'))
  assert.ok(update.params.includes('po_9'))
  const note = update.params.find((p) => typeof p === 'string' && p.includes('acct_77')) as string
  assert.ok(note, 'note names the connected account holding the funds')
  assert.match(note, /NOT in the wallet/)
  assert.match(note, /tr_123/)
})

test('payout.failed: unknown payout row → no writes', async () => {
  const { db, queries } = recordingDb()
  assert.equal(await recordConnectPayoutFailed(db, { payoutRowId: 1, stripePayoutId: 'po_1', connectedAccountId: null, failureCode: null }), null)
  assert.equal(queries.filter((q) => !/^select/.test(q.sql)).length, 0)
})

test('transfer.reversed (full): ledger entry, balance bump and payout status are ONE statement; a retry credits nothing', async () => {
  let ledgerHasReversal = false
  const { db, queries } = recordingDb(
    when(/^select .* from "payouts"/, [[7, 5000]]),
    when(/^select .* from "worker_wallet_entries"/, [[555]]),
    (q) => {
      if (!/^with "ins" as \(insert into "worker_wallet_entries"/.test(q.sql)) return undefined
      // worker_wallet_entries_withdrawal_reversal_unique: one per payout.
      if (ledgerHasReversal) return []
      ledgerHasReversal = true
      return [[900]]
    },
  )
  const t = { payoutRowId: 42, stripeTransferId: 'tr_1', amountCents: 5000, amountReversedCents: 5000 }
  assert.deepEqual(await creditWalletForReversedTransfer(db, t), { credited: true, workerId: 7, amountCents: 5000 })

  const stmt = queries.find((q) => /^with "ins"/.test(q.sql))
  assert.ok(stmt)
  assert.match(stmt.sql, /on conflict do nothing/)
  assert.match(stmt.sql, /"bump" as \(update "workers" set "wallet_balance_cents" = "workers"\."wallet_balance_cents" \+ .*\(exists \(select 1 from "ins"\)/)
  assert.match(stmt.sql, /"mark" as \(update "payouts" set "status"/)
  assert.ok(stmt.params.includes('withdrawal_reversal'))
  assert.ok(stmt.params.includes(42))
  const separateWrites = queries.filter((q) => /^(insert into|update) /.test(q.sql))
  assert.equal(separateWrites.length, 0, 'no ledger/balance/payout write outside the one statement')

  // Stripe retries (e.g. the handler failed later): nothing is credited again.
  assert.deepEqual(await creditWalletForReversedTransfer(db, t), { credited: false, reason: 'already_credited' })
})

test('payout.paid (payouts row): flips once; the legacy earnings bump rides on the flip', async () => {
  let status = 'processing'
  const { db, queries } = recordingDb((q) => {
    if (!/^with "paid" as \(update "payouts"/.test(q.sql)) return undefined
    if (status === 'completed') return []
    status = 'completed'
    return [[7, 5000]]
  })
  assert.deepEqual(await recordConnectPayoutPaid(db, { payoutRowId: 43, stripePayoutId: 'po_1' }), { workerId: 7, amountCents: 5000 })
  assert.equal(await recordConnectPayoutPaid(db, { payoutRowId: 43, stripePayoutId: 'po_1' }), null, 'a retry neither re-counts nor re-notifies')
  const stmt = queries[0]
  assert.match(stmt.sql, /"status" <> 'completed'/)
  assert.match(stmt.sql, /"legacy_earnings" as \(update "workers" .* from "paid" .*not exists \(select 1 from "worker_wallet_entries"/)
  assert.equal(queries.filter((q) => /^update /.test(q.sql)).length, 0)
})

test('payout.paid (legacy job payout): flips once; job/earnings stats ride on the flip', async () => {
  let flipped = false
  const { db, queries } = recordingDb((q) => {
    if (!/^with "done" as \(update "jobs"/.test(q.sql)) return undefined
    if (flipped) return []
    flipped = true
    return [['2500']]
  })
  assert.deepEqual(await recordJobPayoutPaid(db, { jobId: 9, workerId: 7 }), { payoutCents: 2500 })
  assert.equal(await recordJobPayoutPaid(db, { jobId: 9, workerId: 7 }), null)
  assert.match(queries[0].sql, /IS DISTINCT FROM 'completed'/)
  assert.match(queries[0].sql, /"stats" as \(update "workers" set "stats_jobs_completed" = .* from "done"/)
  assert.equal(queries.filter((q) => /^update /.test(q.sql)).length, 0)
})

test('transfer.reversed (partial): no credit, left for manual reconciliation', async () => {
  const { db, queries } = recordingDb(when(/^select .* from "payouts"/, [[7, 5000]]))
  const errorLog = mock.method(console, 'error', () => undefined)
  try {
    const outcome = await creditWalletForReversedTransfer(db, { payoutRowId: 42, stripeTransferId: 'tr_1', amountCents: 5000, amountReversedCents: 2000 })
    assert.deepEqual(outcome, { credited: false, reason: 'partial' })
  } finally {
    errorLog.mock.restore()
  }
  assert.equal(queries.some((q) => touchesWallet(q.sql)), false)
})
