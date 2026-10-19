/**
 * withCronLease releases only the lease it took: the release is fenced on the
 * token (locked_at) returned by the acquire, so a run that outlived its TTL
 * can't free a lease a newer tick has since taken.
 */
import { test, afterEach } from 'node:test'
import assert from 'node:assert/strict'

process.env.DATABASE_URL ??= 'postgres://test:test@localhost/test'

import { __setDbForTests } from '@/db'
import { createFakeDb, type FakeQuery } from '@/test-support/fake-db'
import { withCronLease } from './cron-lease'

afterEach(() => __setDbForTests(null))

test('the release is fenced to the token the acquire returned', async () => {
  const fake = createFakeDb((q: FakeQuery) =>
    q.sqlText?.startsWith('\n    insert into cron_leases') || q.sqlText?.includes('insert into cron_leases')
      ? { rows: [{ token: '2026-10-19 01:02:03.456789+00' }] }
      : { rows: [] })
  __setDbForTests(fake.db)
  const out = await withCronLease('scan-index', 120, async () => 42)
  assert.deepEqual(out, { ran: true, result: 42 })
  const acquire = fake.log.find((q) => q.sqlText?.includes('insert into cron_leases'))!
  const release = fake.log.find((q) => q.sqlText?.includes('update cron_leases'))!
  assert.match(acquire.sqlText!, /returning locked_at::text as token/)
  assert.match(release.sqlText!, /where name = \$1 and locked_at = \$2::timestamptz/)
})

test('a held lease skips without releasing anything', async () => {
  const fake = createFakeDb(() => ({ rows: [] }))
  __setDbForTests(fake.db)
  assert.deepEqual(await withCronLease('scan-index', 120, async () => 1), { ran: false, skipped: 'lease-held' })
  assert.equal(fake.log.filter((q) => q.sqlText?.includes('update cron_leases')).length, 0)
})
