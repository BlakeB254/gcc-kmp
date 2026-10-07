import assert from 'node:assert/strict'
import test from 'node:test'

import { findWalletMismatches, walletIsReconciled } from './wallet-reconcile'
import { recordingDb, when } from './testing/recording-db'

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
