import assert from 'node:assert/strict'
import test from 'node:test'

import {
  claimPendingCreditForApproval,
  createPendingAdminCredit,
  pendingCreditGuard,
  rejectPendingCredit,
  type PendingAdminCreditRow,
} from './admin-credit-approval'
import { recordingDb, when } from './testing/recording-db'

const NOW = '2026-10-11T15:00:00.000Z'
const flat = (sql: string) => sql.replace(/[()]/g, '')

// pending_admin_credits row in pg-proxy array form (column order of the table).
function pendingRow(o: Partial<PendingAdminCreditRow> = {}): unknown[] {
  return [
    o.id ?? 31, o.targetWorkerId ?? 2, o.amountCents ?? 25_000, o.reason ?? 'Equipment reimbursement',
    o.idempotencyKey ?? 'key-12345678', o.createdByAdminId ?? 1, o.status ?? 'pending',
    o.decidedByAdminId ?? null, o.decidedAt ?? null, o.decisionNote ?? null, o.walletEntryId ?? null,
    o.createdAt ?? NOW, o.updatedAt ?? NOW,
  ]
}
const pending: PendingAdminCreditRow = {
  id: 31, targetWorkerId: 2, amountCents: 25_000, reason: 'Equipment reimbursement', idempotencyKey: 'key-12345678',
  createdByAdminId: 1, status: 'pending', decidedByAdminId: null, decidedAt: null, decisionNote: null, walletEntryId: null,
  createdAt: NOW, updatedAt: NOW,
}

test('createPendingAdminCredit: idempotency key is the atomic claim; a retry reports the existing row', async () => {
  const fresh = recordingDb(when(/^insert into "pending_admin_credits"/, [[31]]))
  const created = await createPendingAdminCredit(fresh.db, {
    targetWorkerId: 2, amountCents: 25_000, reason: 'Equipment reimbursement', idempotencyKey: 'key-12345678', createdByAdminId: 1, now: NOW,
  })
  assert.deepEqual(created, { created: true, id: 31 })
  const q = fresh.queries[0]
  assert.match(q.sql, /^insert into "pending_admin_credits"/)
  assert.match(q.sql, /on conflict do nothing returning "id"/)
  assert.ok(q.params.includes('pending'))

  const retry = recordingDb(when(/^select .* from "pending_admin_credits"/, [[31, 'approved']]))
  const again = await createPendingAdminCredit(retry.db, {
    targetWorkerId: 2, amountCents: 25_000, reason: 'Equipment reimbursement', idempotencyKey: 'key-12345678', createdByAdminId: 1, now: NOW,
  })
  assert.deepEqual(again, { created: false, existing: { id: 31, status: 'approved' } })
  assert.match(retry.queries[1].sql, /where "pending_admin_credits"\."idempotency_key" = \$1/)
})

test('pendingCreditGuard: only a pending credit, and never by its creator or its target', () => {
  assert.equal(pendingCreditGuard(pending, 5), null)
  assert.deepEqual(pendingCreditGuard(pending, 1), {
    status: 403, error: 'You requested this credit — a different admin must approve it.',
  })
  assert.deepEqual(pendingCreditGuard(pending, 2), {
    status: 403, error: 'You cannot approve a credit to your own wallet.',
  })
  assert.equal(pendingCreditGuard({ ...pending, status: 'approved' }, 5)?.status, 409)
  assert.equal(pendingCreditGuard({ ...pending, status: 'rejected' }, 5)?.status, 409)
})

test('claimPendingCreditForApproval: single conditional UPDATE re-checks pending + not creator + not target', async () => {
  const { db, queries } = recordingDb(when(/^update "pending_admin_credits"/, [pendingRow({ status: 'approved', decidedByAdminId: 5, decidedAt: NOW })]))
  const row = await claimPendingCreditForApproval(db, 31, 5, NOW)
  assert.equal(row?.status, 'approved')
  assert.equal(row?.decidedByAdminId, 5)
  assert.equal(row?.amountCents, 25_000)
  const q = queries[0]
  assert.match(q.sql, /^update "pending_admin_credits" set "status" = \$1, "decided_by_admin_id" = \$2, "decided_at" = \$3, "updated_at" = \$4/)
  assert.deepEqual(q.params.slice(0, 4), ['approved', 5, NOW, NOW])
  const where = flat(q.sql.slice(q.sql.indexOf(' where ')))
  assert.match(where, /"pending_admin_credits"\."id" = \$\d+ and "pending_admin_credits"\."status" = \$\d+ and "pending_admin_credits"\."created_by_admin_id" <> \$\d+ and "pending_admin_credits"\."target_worker_id" <> \$\d+/)
  assert.deepEqual(q.params.slice(4), [31, 'pending', 5, 5])
  assert.match(q.sql, /returning/)
})

test('claimPendingCreditForApproval: the creator approving their own request matches no row', async () => {
  const { db, queries } = recordingDb()
  const row = await claimPendingCreditForApproval(db, 31, 1, NOW)
  assert.equal(row, null)
  assert.equal(queries.length, 1, 'no second write after a lost claim')
})

test('rejectPendingCredit: any admin (the creator included) may withdraw a pending credit; only from pending', async () => {
  const { db, queries } = recordingDb(when(/^update "pending_admin_credits"/, [pendingRow({ status: 'rejected', decidedByAdminId: 1 })]))
  const row = await rejectPendingCredit(db, 31, 1, NOW, 'wrong worker')
  assert.equal(row?.status, 'rejected')
  const q = queries[0]
  assert.deepEqual(q.params.slice(0, 5), ['rejected', 1, NOW, 'wrong worker', NOW])
  const where = flat(q.sql.slice(q.sql.indexOf(' where '), q.sql.indexOf(' returning ')))
  assert.match(where, /"pending_admin_credits"\."id" = \$\d+ and "pending_admin_credits"\."status" = \$\d+$/)
  assert.doesNotMatch(where, /created_by_admin_id/)
})
