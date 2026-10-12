import assert from 'node:assert/strict'
import test from 'node:test'

import { DEFAULT_ADMIN_CREDIT_LIMITS } from './admin-credit'
import { releaseDailyCapacity, reserveDailyCapacity, utcDay } from './admin-credit-caps'
import { recordingDb, when, type RecordedQuery } from './testing/recording-db'

const DAY = '2026-10-11'
const limits = DEFAULT_ADMIN_CREDIT_LIMITS // $500/admin/day, $250/target/day, $100 threshold

/**
 * A fake that applies the counter table's semantics from the statement's own
 * params: one row per (scope, subject, day), ON CONFLICT adds the amount only
 * while the new total stays within that scope's cap. Mirrors what Postgres
 * does with the upsert so the N+1 test is about the SQL we send, not a mock.
 */
function counterTable() {
  const totals = new Map<string, number>()
  const responder = (q: RecordedQuery): unknown[][] | undefined => {
    if (/^update "admin_credit_daily_totals"/.test(q.sql)) {
      const [amount, scope, subject, day] = q.params as [number, string, number, string]
      const key = `${scope}:${subject}:${day}`
      totals.set(key, Math.max((totals.get(key) ?? 0) - amount, 0))
      return []
    }
    if (!/^insert into "admin_credit_daily_totals"/.test(q.sql)) return undefined
    // values ($1,$2,$3,$4), ($5,$6,$7,$8) … case excluded.scope when 'admin' then $9 else $10 end
    const p = q.params as [string, number, string, number, string, number, string, number, number, number]
    const caps = { admin: p[8], target: p[9] }
    const rows: unknown[][] = []
    for (const [scope, subject, day, amount] of [[p[0], p[1], p[2], p[3]], [p[4], p[5], p[6], p[7]]] as const) {
      const key = `${scope}:${subject}:${day}`
      const next = (totals.get(key) ?? 0) + amount
      if (next <= caps[scope as 'admin' | 'target']) {
        totals.set(key, next)
        rows.push([scope, next])
      }
    }
    return rows
  }
  return { totals, responder }
}

test('utcDay: the cap day is the UTC calendar date', () => {
  assert.equal(utcDay(new Date('2026-10-11T23:59:59.000Z')), '2026-10-11')
  assert.equal(utcDay(new Date('2026-10-12T00:00:00.000Z')), '2026-10-12')
})

test('reserveDailyCapacity: one upsert covering both counters, each capped in the DO UPDATE WHERE', async () => {
  const { db, queries } = recordingDb(when(/^insert into "admin_credit_daily_totals"/, [['admin', 10_000], ['target', 10_000]]))
  const r = await reserveDailyCapacity(db, { adminWorkerId: 1, targetWorkerId: 2, amountCents: 10_000, day: DAY, limits })
  assert.deepEqual(r, { ok: true, adminTotalCents: 10_000, targetTotalCents: 10_000 })
  assert.equal(queries.length, 1)
  const q = queries[0]
  assert.match(q.sql, /^insert into "admin_credit_daily_totals" \("scope", "subject_id", "day", "total_cents"\) values \(\$1, \$2, \$3, \$4\), \(\$5, \$6, \$7, \$8\)/)
  assert.deepEqual(q.params.slice(0, 8), ['admin', 1, DAY, 10_000, 'target', 2, DAY, 10_000])
  assert.match(q.sql, /on conflict \("scope","subject_id","day"\) do update set "total_cents" = "admin_credit_daily_totals"\."total_cents" \+ excluded\.total_cents/)
  assert.match(q.sql, /where "admin_credit_daily_totals"\."total_cents" \+ excluded\.total_cents <= case excluded\.scope when 'admin' then \$9 else \$10 end/)
  assert.deepEqual(q.params.slice(8), [limits.perAdminDailyCents, limits.perTargetDailyCents])
  assert.match(q.sql, /returning "scope", "total_cents"$/)
})

test('reserveDailyCapacity: the N+1th credit of the day trips the per-admin cap (one admin, many workers)', async () => {
  const table = counterTable()
  const { db } = recordingDb(table.responder)
  // 5 × $100 to five different workers = $500 = the per-admin cap.
  for (let worker = 2; worker <= 6; worker++) {
    const r = await reserveDailyCapacity(db, { adminWorkerId: 1, targetWorkerId: worker, amountCents: 10_000, day: DAY, limits })
    assert.equal(r.ok, true, `credit ${worker - 1} fits`)
  }
  const sixth = await reserveDailyCapacity(db, { adminWorkerId: 1, targetWorkerId: 7, amountCents: 100, day: DAY, limits })
  assert.deepEqual(sixth, { ok: false, scope: 'admin', capCents: limits.perAdminDailyCents })
  assert.equal(table.totals.get(`admin:1:${DAY}`), limits.perAdminDailyCents, 'the refused credit did not count')
  assert.equal(table.totals.get(`target:7:${DAY}`) ?? 0, 0, 'the target row applied by the partial upsert was released')
})

test('reserveDailyCapacity: the N+1th credit of the day trips the per-target cap (many admins, one worker)', async () => {
  const table = counterTable()
  const { db } = recordingDb(table.responder)
  // Admins 1..5 each credit worker 9 $50 = $250 = the per-target cap.
  for (let admin = 1; admin <= 5; admin++) {
    const r = await reserveDailyCapacity(db, { adminWorkerId: admin, targetWorkerId: 9, amountCents: 5_000, day: DAY, limits })
    assert.equal(r.ok, true, `credit ${admin} fits`)
  }
  const sixth = await reserveDailyCapacity(db, { adminWorkerId: 6, targetWorkerId: 9, amountCents: 100, day: DAY, limits })
  assert.deepEqual(sixth, { ok: false, scope: 'target', capCents: limits.perTargetDailyCents })
  assert.equal(table.totals.get(`target:9:${DAY}`), limits.perTargetDailyCents)
  assert.equal(table.totals.get(`admin:6:${DAY}`) ?? 0, 0, 'the admin row applied by the partial upsert was released')
})

test('reserveDailyCapacity: a new UTC day starts the counters over', async () => {
  const table = counterTable()
  const { db } = recordingDb(table.responder)
  const full = await reserveDailyCapacity(db, { adminWorkerId: 1, targetWorkerId: 2, amountCents: limits.perTargetDailyCents, day: DAY, limits })
  assert.equal(full.ok, true)
  const same = await reserveDailyCapacity(db, { adminWorkerId: 1, targetWorkerId: 2, amountCents: 1, day: DAY, limits })
  assert.equal(same.ok, false)
  const tomorrow = await reserveDailyCapacity(db, { adminWorkerId: 1, targetWorkerId: 2, amountCents: 1, day: '2026-10-12', limits })
  assert.equal(tomorrow.ok, true)
})

test('reserveDailyCapacity: an amount above a cap is refused before any statement runs', async () => {
  const { db, queries } = recordingDb()
  const r = await reserveDailyCapacity(db, { adminWorkerId: 1, targetWorkerId: 2, amountCents: limits.perTargetDailyCents + 1, day: DAY, limits })
  assert.deepEqual(r, { ok: false, scope: 'target', capCents: limits.perTargetDailyCents })
  assert.equal(queries.length, 0)
})

test('reserveDailyCapacity: a partial upsert (one counter at cap) releases the counter that was bumped', async () => {
  const { db, queries } = recordingDb(when(/^insert into "admin_credit_daily_totals"/, [['admin', 20_000]]))
  const r = await reserveDailyCapacity(db, { adminWorkerId: 1, targetWorkerId: 2, amountCents: 10_000, day: DAY, limits })
  assert.deepEqual(r, { ok: false, scope: 'target', capCents: limits.perTargetDailyCents })
  assert.equal(queries.length, 2)
  const release = queries[1]
  assert.match(release.sql, /^update "admin_credit_daily_totals" set "total_cents" = GREATEST\("admin_credit_daily_totals"\."total_cents" - \$1, 0\)/)
  assert.match(release.sql.replace(/[()]/g, ''), /"admin_credit_daily_totals"\."scope" = \$\d+ and "admin_credit_daily_totals"\."subject_id" = \$\d+ and "admin_credit_daily_totals"\."day" = \$\d+/)
  assert.deepEqual(release.params, [10_000, 'admin', 1, DAY])
})

test('releaseDailyCapacity: both counters by default, never below zero', async () => {
  const { db, queries } = recordingDb()
  await releaseDailyCapacity(db, { adminWorkerId: 1, targetWorkerId: 2, amountCents: 700, day: DAY })
  assert.equal(queries.length, 2)
  assert.deepEqual(queries[0].params, [700, 'admin', 1, DAY])
  assert.deepEqual(queries[1].params, [700, 'target', 2, DAY])
  for (const q of queries) assert.match(q.sql, /GREATEST\("admin_credit_daily_totals"\."total_cents" - \$1, 0\)/)
})
