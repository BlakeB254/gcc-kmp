import assert from 'node:assert/strict'
import test, { mock } from 'node:test'

import { handleCheckoutSessionCompleted, handleInvoicePaid, sponsoredBlocksLine } from './sponsor-payment-events'
import { processEventOnce, type EventClaimStore } from './webhook-event-claim'
import { recordingDb, type RecordedQuery } from '../testing/recording-db'

/**
 * The webhook's event claim, in memory (same semantics as dbEventClaimStore).
 */
function memoryStore(): EventClaimStore {
  const ids = new Set<string>()
  return {
    async claim(id) {
      if (ids.has(id)) return false
      ids.add(id)
      return true
    },
    async release(id) {
      ids.delete(id)
    },
  }
}

/**
 * A stand-in for Postgres behind the recording drizzle: the payment statement
 * (`with "ins" as (insert into "transactions" … on conflict do nothing …)`)
 * inserts each (checkout session, block) or invoice key once, like the 0049
 * unique indexes, and its block-funding / stats CTEs apply only when the
 * insert created rows. Everything is recorded so tests can assert what was
 * written and how often.
 */
function fakeLedger() {
  const keys = new Set<string>()
  const rows: { key: string; id: number; blockId: number | null }[] = []
  let nextId = 1
  let statsIncrements = 0
  let blockFundings = 0
  const { db, queries } = recordingDb(
    (q) => {
      if (!/^with "ins" as \(insert into "transactions"/.test(q.sql)) return undefined
      // Each row to insert carries its session (cs_…) or invoice (in_…) id;
      // its block_id is the param right after the row's sponsor_id (5 in
      // these tests), the first two columns of every row.
      const created: unknown[][] = []
      q.params.forEach((ref, i) => {
        if (typeof ref !== 'string' || !/^(cs|in)_/.test(ref)) return
        const rowStart = q.params.lastIndexOf(5, i)
        const blockId = /^cs_/.test(ref) ? ((q.params[rowStart + 1] as number | null) ?? null) : null
        const key = `${ref}:${blockId ?? 0}`
        if (keys.has(key)) return
        keys.add(key)
        const row = { key, id: nextId++, blockId }
        rows.push(row)
        created.push([row.id, row.blockId])
      })
      if (created.length > 0) {
        if (/"stats" as \(update "sponsors"/.test(q.sql)) statsIncrements++
        if (/"funded" as \(update "blocks"/.test(q.sql)) blockFundings++
      }
      return created
    },
    (q) => (/^select .* from "sponsors"/.test(q.sql) ? [['Sam', 's@example.com', null]] : undefined),
  )
  return {
    db,
    queries,
    rows,
    get statsIncrements() {
      return statsIncrements
    },
    get blockFundings() {
      return blockFundings
    },
  }
}

const noInvalidate = () => {}
const quiet = () => {
  const log = mock.method(console, 'log', () => undefined)
  const err = mock.method(console, 'error', () => undefined)
  return () => {
    log.mock.restore()
    err.mock.restore()
  }
}

/** Writes outside the single payment statement (there must be none). */
const strayWrites = (queries: RecordedQuery[]) =>
  queries.filter((q) => /^(insert into|update) "(transactions|sponsors|blocks)"/.test(q.sql))

const blockSession = {
  id: 'cs_blocks',
  amount_total: 20000,
  currency: 'usd',
  payment_intent: 'pi_1',
  metadata: { sponsorId: '5', allocationType: 'block', blockIds: '11,12' },
}

test('checkout.session.completed: block rows, block funding and stats are ONE statement keyed on the unique index', async () => {
  const restore = quiet()
  try {
    const fake = fakeLedger()
    await handleCheckoutSessionCompleted(blockSession, { exec: fake.db, invalidate: noInvalidate, sendEmail: async () => ({ success: true }) })
    const stmt = fake.queries.find((q) => /^with "ins"/.test(q.sql))
    assert.ok(stmt)
    assert.match(stmt.sql, /on conflict do nothing returning "id", "block_id"/)
    assert.match(stmt.sql, /"funded" as \(update "blocks" .* where \(\("blocks"\."id" in \(select "block_id" from "ins"/)
    assert.match(stmt.sql, /"stats" as \(update "sponsors" .*\(exists \(select 1 from "ins"\)/)
    assert.equal(strayWrites(fake.queries).length, 0, 'no insert/update outside the payment statement')
    assert.equal(fake.rows.length, 2)
  } finally {
    restore()
  }
})

test('checkout.session.completed: funding never overwrites a claimed/in-progress/completed block — that row becomes a reservation', async () => {
  const restore = quiet()
  try {
    const fake = fakeLedger()
    await handleCheckoutSessionCompleted(blockSession, { exec: fake.db, invalidate: noInvalidate, sendEmail: async () => ({ success: true }) })
    const stmt = fake.queries.find((q) => /^with "ins"/.test(q.sql))
    assert.ok(stmt)
    // The block UPDATE only touches blocks still `available` (what checkout offered).
    assert.match(stmt.sql, /"funded" as \(update "blocks" .* and \("blocks"\."status" = \$\d+\)\) returning "id"\)/)
    const funded = stmt.sql.slice(stmt.sql.indexOf('"funded" as'))
    const guardParam = Number(funded.match(/"blocks"\."status" = \$(\d+)\)\) returning/)?.[1])
    assert.equal(stmt.params[guardParam - 1], 'available')
    // Each block row decides, under a row lock, whether it funds or is reserved
    // for that block's next cycle (reserved_block_count 1 + the block's code).
    assert.equal(stmt.sql.match(/case when exists \(select 1 from "blocks" where "blocks"\."id" = \$\d+\s+and "blocks"\."status" = \$\d+ for update\) then null else 1 end/g)?.length, 2)
    assert.match(stmt.sql, /else \(select "blocks"\."code" from "blocks" where "blocks"\."id" = \$\d+\) end/)
    // The sponsor's blocks-funded stat counts blocks really funded, not rows.
    assert.match(stmt.sql, /"stats_blocks_funded" = COALESCE\("sponsors"\."stats_blocks_funded", 0\) \+ \(select count\(\*\) from "funded"\)::int/)
  } finally {
    restore()
  }
})

test('checkout.session.completed: a throw after the insert, then Stripe’s retry → one row per block, one stats increment', async () => {
  const restore = quiet()
  try {
    const fake = fakeLedger()
    const store = memoryStore()
    let emailAttempts = 0
    const sendEmail = async () => {
      emailAttempts++
      if (emailAttempts === 1) throw new Error('email provider down')
      return { success: true }
    }
    const run = () =>
      processEventOnce('evt_cs', store, async () => {
        await handleCheckoutSessionCompleted(blockSession, { exec: fake.db, invalidate: noInvalidate, sendEmail })
      })

    await assert.rejects(run(), /email provider down/)
    assert.equal(await run(), 'processed', 'the claim was released, so the retry runs')

    assert.deepEqual(fake.rows.map((r) => r.key), ['cs_blocks:11', 'cs_blocks:12'])
    assert.equal(fake.statsIncrements, 1)
    assert.equal(fake.blockFundings, 1)
    assert.equal(emailAttempts, 1, 'the retry created no rows, so it sends no second email')
    assert.equal(strayWrites(fake.queries).length, 0)

    // The same event again is a duplicate; a resend under a new event id is a no-op.
    assert.equal(await run(), 'duplicate')
    const out = await handleCheckoutSessionCompleted(blockSession, { exec: fake.db, invalidate: noInvalidate, sendEmail })
    assert.equal(out.inserted, 0)
    assert.equal(fake.rows.length, 2)
    assert.equal(fake.statsIncrements, 1)
    assert.equal(emailAttempts, 1)
  } finally {
    restore()
  }
})

test('checkout.session.completed (general donation): a replay records nothing and counts nothing', async () => {
  const restore = quiet()
  try {
    const fake = fakeLedger()
    let emails = 0
    const deps = { exec: fake.db, invalidate: noInvalidate, sendEmail: async () => (emails++, { success: true }) }
    const session = { id: 'cs_gen', amount_total: 5000, currency: 'usd', payment_intent: 'pi_2', metadata: { sponsorId: '5' } }
    assert.equal((await handleCheckoutSessionCompleted(session, deps)).inserted, 1)
    assert.equal((await handleCheckoutSessionCompleted(session, deps)).inserted, 0)
    assert.deepEqual(fake.rows.map((r) => r.key), ['cs_gen:0'])
    assert.equal(fake.statsIncrements, 1)
    assert.equal(emails, 1)
    assert.equal(strayWrites(fake.queries).length, 0)
  } finally {
    restore()
  }
})

const renewalInvoice = {
  id: 'in_renew_1',
  billing_reason: 'subscription_cycle',
  amount_paid: 2500,
  currency: 'usd',
  parent: { subscription_details: { subscription: 'sub_1', metadata: { sponsorId: '5', frequency: 'monthly' } } },
}

test('invoice.paid: the insert carries stripe_invoice_id and the stats increment rides on it', async () => {
  const restore = quiet()
  try {
    const fake = fakeLedger()
    assert.deepEqual(await handleInvoicePaid(renewalInvoice, { exec: fake.db, invalidate: noInvalidate }), { recorded: true })
    const stmt = fake.queries.find((q) => /^with "ins" as \(insert into "transactions"/.test(q.sql))
    assert.ok(stmt)
    assert.match(stmt.sql, /"stripe_invoice_id"/)
    assert.ok(stmt.params.includes('in_renew_1'))
    assert.match(stmt.sql, /"stats" as \(update "sponsors" .*\(exists \(select 1 from "ins"\)/)
    assert.equal(strayWrites(fake.queries).length, 0)
  } finally {
    restore()
  }
})

test('invoice.paid: a throw after the insert, then the retry → one row, one stats increment; a replay is a no-op', async () => {
  const restore = quiet()
  try {
    const fake = fakeLedger()
    const store = memoryStore()
    let calls = 0
    const run = () =>
      processEventOnce('evt_inv', store, async () => {
        await handleInvoicePaid(renewalInvoice, { exec: fake.db, invalidate: noInvalidate })
        if (++calls === 1) throw new Error('crash after the insert')
      })

    await assert.rejects(run(), /crash after the insert/)
    assert.equal(await run(), 'processed')
    assert.deepEqual(fake.rows.map((r) => r.key), ['in_renew_1:0'])
    assert.equal(fake.statsIncrements, 1)

    assert.equal(await run(), 'duplicate')
    assert.deepEqual(
      await handleInvoicePaid(renewalInvoice, { exec: fake.db, invalidate: noInvalidate }),
      { recorded: false, reason: 'already_recorded' },
    )
    assert.equal(fake.rows.length, 1)
    assert.equal(fake.statsIncrements, 1)
  } finally {
    restore()
  }
})

test('invoice.paid: the first subscription invoice is left to checkout.session.completed', async () => {
  const fake = fakeLedger()
  const out = await handleInvoicePaid({ ...renewalInvoice, billing_reason: 'subscription_create' }, { exec: fake.db, invalidate: noInvalidate })
  assert.deepEqual(out, { recorded: false, reason: 'not_a_renewal' })
  assert.equal(fake.queries.length, 0)
})

test('sponsoredBlocksLine: the confirmation email names only blocks the payment FUNDED; reserved ones are said to be reserved', async () => {
  const { db } = recordingDb((q) =>
    /^select "id", "name" from "blocks"/.test(q.sql) ? [[11, 'Ogden Ave (S)'], [12, 'Pulaski Rd (N)'], [13, 'Cermak Rd (E)']] : undefined,
  )
  assert.equal(
    await sponsoredBlocksLine(db, [
      { id: 1, blockId: 11, reserved: false },
      { id: 2, blockId: 12, reserved: true },
    ]),
    'Ogden Ave (S); 1 block reserved for their next cleaning: Pulaski Rd (N)',
  )
  assert.equal(
    await sponsoredBlocksLine(db, [
      { id: 1, blockId: 11, reserved: false },
      { id: 3, blockId: 13, reserved: false },
    ]),
    '2 blocks: Ogden Ave (S), Cermak Rd (E)',
  )
  // Nothing funded: no block is presented as sponsored.
  const onlyReserved = await sponsoredBlocksLine(db, [{ id: 2, blockId: 12, reserved: true }])
  assert.equal(onlyReserved, '1 block reserved for their next cleaning: Pulaski Rd (N)')
})
