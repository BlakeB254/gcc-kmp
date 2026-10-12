import assert from 'node:assert/strict'
import test from 'node:test'

import { findWalletMismatches, listAdminCredits, listPendingAdminCredits, walletIsReconciled } from './wallet-reconcile'
import { recordingDb, when } from './testing/recording-db'

// ── Admin credits in the reconcile output ────────────────────────────────────

const SINCE = '2026-09-11T00:00:00.000Z'

test('listAdminCredits: every admin_credit ledger line since the cutoff, newest first, with the admin who issued it', async () => {
  const { db, queries } = recordingDb(when(/from "worker_wallet_entries"/, [
    [41, 7, 25_000, 'Equipment reimbursement', 1, 'key-12345678', '2026-10-11T15:00:00.000Z'],
    [40, 8, 5_000, 'Volunteer day bonus', 3, 'key-abcdefgh', '2026-10-10T15:00:00.000Z'],
  ]))
  const rows = await listAdminCredits({ since: SINCE, limit: 500 }, db)
  assert.deepEqual(rows, [
    { entryId: 41, workerId: 7, amountCents: 25_000, note: 'Equipment reimbursement', createdByAdminId: 1, idempotencyKey: 'key-12345678', createdAt: '2026-10-11T15:00:00.000Z' },
    { entryId: 40, workerId: 8, amountCents: 5_000, note: 'Volunteer day bonus', createdByAdminId: 3, idempotencyKey: 'key-abcdefgh', createdAt: '2026-10-10T15:00:00.000Z' },
  ])
  const q = queries[0]
  const where = q.sql.slice(q.sql.indexOf(' where ')).replace(/[()]/g, '')
  assert.match(where, /"worker_wallet_entries"\."kind" = \$\d+ and "worker_wallet_entries"\."created_at" >= \$\d+/)
  assert.deepEqual(q.params, ['admin_credit', SINCE, 500])
  assert.match(q.sql, /order by "worker_wallet_entries"\."created_at" desc limit \$3/)
})

test('listPendingAdminCredits: every request since the cutoff in any state, newest first', async () => {
  const { db, queries } = recordingDb(when(/from "pending_admin_credits"/, [
    [31, 2, 25_000, 'Equipment reimbursement', 'key-12345678', 1, 'pending', null, null, null, null, '2026-10-11T15:00:00.000Z', '2026-10-11T15:00:00.000Z'],
  ]))
  const rows = await listPendingAdminCredits({ since: SINCE, limit: 500 }, db)
  assert.equal(rows.length, 1)
  assert.equal(rows[0].status, 'pending')
  assert.equal(rows[0].createdByAdminId, 1)
  assert.equal(rows[0].walletEntryId, null)
  assert.match(queries[0].sql, /where "pending_admin_credits"\."created_at" >= \$1 order by "pending_admin_credits"\."created_at" desc limit \$2/)
  assert.deepEqual(queries[0].params, [SINCE, 500])
})

test('findWalletMismatches: compares the balance column to SUM(ledger) per worker', async () => {
  const { db, queries } = recordingDb(when(/from "workers"/, [[7, 5000, '4000']]))
  const rows = await findWalletMismatches({}, db)
  assert.deepEqual(rows, [{ workerId: 7, balanceCents: 5000, ledgerCents: 4000, differenceCents: 1000 }])
  const { sql } = queries[0]
  assert.match(sql, /left join "worker_wallet_entries" on "worker_wallet_entries"\."worker_id" = "workers"\."id"/)
  assert.match(sql, /group by "workers"\."id", "workers"\."wallet_balance_cents"/)
  assert.match(sql, /having COALESCE\(SUM\("worker_wallet_entries"\."amount_cents"\), 0\) <> "workers"\."wallet_balance_cents"/)
  assert.doesNotMatch(sql, / where /, 'all workers when no id is given')
})

test('walletIsReconciled: scoped to one worker; false on mismatch, true when none', async () => {
  const mismatch = recordingDb(when(/from "workers"/, [[7, 5000, '0']]))
  assert.equal(await walletIsReconciled(7, mismatch.db), false)
  assert.match(mismatch.queries[0].sql, /where "workers"\."id" = \$1/)
  assert.deepEqual(mismatch.queries[0].params, [7])

  const clean = recordingDb()
  assert.equal(await walletIsReconciled(7, clean.db), true)
})
