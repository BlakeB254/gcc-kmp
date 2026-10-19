import { test } from 'node:test'
import assert from 'node:assert/strict'
import { PgDialect } from 'drizzle-orm/pg-core'
import type { SQL } from 'drizzle-orm'
import type { SqlExec } from './db'
import { GRANT_CUTOVER_GRACE_MS, grantCutoverMs, grantRefusal, grantUploadKey, grantsFor, manifestSessionOf, recordUploadGrant } from './grants'

function recordingExec(respond: (sql: string, params: unknown[]) => Record<string, unknown>[] | Error = () => []) {
  const dialect = new PgDialect()
  const queries: { sql: string; params: unknown[] }[] = []
  const exec: SqlExec = async (q: SQL) => {
    const { sql, params } = dialect.sqlToQuery(q)
    const norm = sql.replace(/\s+/g, ' ').trim()
    queries.push({ sql: norm, params })
    const r = respond(norm, params)
    if (r instanceof Error) throw r
    return r
  }
  return { exec, queries }
}

const grant = {
  r2Key: 'raw/1:2/env-1/capture/c.mov', workerId: 7, deviceId: 'dev1', sessionId: 'env-1', folder: 'env-1', claimFolder: true,
}
const deviceKey = {
  r2Key: 'raw/1:2/dev1/env-1-manifest.json', workerId: 7, deviceId: 'dev1', sessionId: null, folder: 'dev1', claimFolder: false,
}

test('a session-folder key claims its folder segment; folder check and grant are one statement', async () => {
  const { exec, queries } = recordingExec(() => [{ owner: 7, granted: 1, had_grant: 0 }])
  assert.equal(await recordUploadGrant(exec, { ...grant, objectExists: false }), 'granted')
  assert.equal(queries.length, 1)
  const q = queries[0].sql
  assert.match(q, /^with ns as \( insert into capture_namespace_owners \(ns_id, worker_id\) values \(\$1, \$2::int\) on conflict \(ns_id\) do update set ns_id = excluded\.ns_id returning worker_id \)/)
  assert.ok(!q.includes('kind'), 'owned by the folder segment alone')
  assert.match(q, /from ns where ns\.worker_id = \$\d+::int and \(not \$\d+::boolean or exists \(select 1 from prior where prior\.worker_id = \$\d+::int\) or \(/)
  assert.ok(!q.includes('--'), 'no SQL comments: one line of normalized SQL must still be the same statement')
  assert.match(q, /where capture_upload_grants\.worker_id = excluded\.worker_id or \(capture_upload_grants\.last_issued_at < now\(\) - make_interval\(secs => \$\d+::int\) and not \$\d+::boolean\)/)
})

test('a device-folder key claims nothing but still respects an owned segment of the same name', async () => {
  const { exec, queries } = recordingExec(() => [{ owner: 7, granted: 1, had_grant: 0 }])
  assert.equal(await recordUploadGrant(exec, { ...deviceKey, objectExists: false }), 'granted')
  const q = queries[0].sql
  assert.ok(!q.includes('insert into capture_namespace_owners'), 'shared handsets: a device folder is never claimed')
  assert.match(q, /^with ns as \( select case when \(o\.folder_owner is null or o\.folder_owner = \$\d+::int\) and \(o\.related_owner is null or o\.related_owner = \$\d+::int\)/)
  assert.ok(!/select coalesce\( \(select worker_id/.test(q), 'no coalesce fallback between the two owners')
  // deviceId = someone's session id: the segment is owned, so refused.
  assert.equal(await recordUploadGrant(recordingExec(() => [{ owner: 8, granted: 0, had_grant: 0 }]).exec, { ...deviceKey, objectExists: false }), 'namespace-owned')
})

test('outcomes', async () => {
  const run = (row: Record<string, unknown>, objectExists = false) =>
    recordUploadGrant(recordingExec(() => [row]).exec, { ...grant, objectExists })
  assert.equal(await run({ owner: 8, granted: 0, had_grant: 0 }), 'namespace-owned')
  assert.equal(await run({ owner: 7, granted: 0, had_grant: 1 }), 'key-owned')
  assert.equal(await run({ owner: 7, granted: 0, had_grant: 0 }, true), 'object-exists', 'legacy data predating every grant cannot be claimed')
  assert.equal(await run({ owner: 7, granted: 1, had_grant: 1 }, true), 'granted', 'same worker refreshing its own used key')
})

test('the R2 answer is taken before the single grant statement', async () => {
  const seen: boolean[] = []
  const exec = recordingExec((_sql, params) => {
    seen.push(params.includes(true))
    return [{ owner: 7, granted: 0, had_grant: 0 }]
  }).exec
  assert.equal(await grantUploadKey(exec, grant, async () => ({ state: 'exists', lastModifiedMs: 1 })), 'object-exists')
  assert.deepEqual(seen, [true])
})

test('R2 unable to answer: unavailable, and the database is not touched', async () => {
  const { exec, queries } = recordingExec(() => [{ owner: 7, granted: 1, had_grant: 0 }])
  assert.equal(await grantUploadKey(exec, grant, async () => ({ state: 'unknown' })), 'unavailable')
  assert.equal(queries.length, 0)
})

test('an existing object is only bypassed for the same worker\'s key, or a session owned since before it was written', async () => {
  const { exec, queries } = recordingExec(() => [{ owner: 7, granted: 1, had_grant: 0 }])
  await recordUploadGrant(exec, { ...grant, objectExists: true, objectLastModifiedMs: Date.UTC(2026, 9, 18) })
  const q = queries[0].sql
  assert.match(q, /or exists \(select 1 from prior where prior\.worker_id = \$\d+::int\)/, 'a prior grant of THIS worker on this key')
  assert.match(q, /or \(\$\d+::boolean and exists \( select 1 from capture_namespace_owners o where o\.ns_id = \$\d+ and o\.worker_id = \$\d+::int and o\.first_granted_at < \$\d+::timestamptz\)\)/)
  const params = queries[0].params
  assert.ok(params.includes('2026-10-18T00:00:00.000Z'), "the object's LastModified is the bound")
  // A device-shaped key never gets the folder bypass.
  const device = recordingExec(() => [{ owner: 7, granted: 0, had_grant: 0 }])
  await recordUploadGrant(device.exec, { ...deviceKey, objectExists: true, objectLastModifiedMs: 1 })
  assert.ok(device.queries[0].params.includes(false), 'claimFolder=false switches the folder bypass off')
})

test('refusals map to what the app does with them', () => {
  assert.equal(grantRefusal('granted'), null)
  assert.equal(grantRefusal('unavailable')!.status, 503)
  assert.equal(grantRefusal('namespace-owned')!.status, 503)
  assert.ok(grantRefusal('namespace-owned')!.retryAfter! > 0)
  assert.equal(grantRefusal('key-owned')!.status, 409)
  assert.equal(grantRefusal('object-exists')!.status, 409)
})

test('a manifest capturedAt names its session', () => {
  assert.equal(manifestSessionOf('env-1789149214237-2wj1ux-manifest', 'json'), 'env-1789149214237-2wj1ux')
  assert.equal(manifestSessionOf('env-1789149214237-2wj1ux-manifest', 'jpg'), null)
  assert.equal(manifestSessionOf('env-1789-abc-manifest', 'json'), null, 'not the app session format')
  assert.equal(manifestSessionOf('oc-1789', 'json'), null)
  assert.equal(manifestSessionOf('-manifest', 'json'), null)
})

test('cutover is the first grant plus the grace; none before any grant', async () => {
  assert.equal(await grantCutoverMs(recordingExec(() => [{ first_ms: 1000 }]).exec), 1000 + GRANT_CUTOVER_GRACE_MS)
  assert.equal(await grantCutoverMs(recordingExec(() => [{ first_ms: null }]).exec), null)
})

test('grantsFor maps rows by key, with the issue window', async () => {
  const { exec } = recordingExec(() => [{ r2_key: 'k', worker_id: '7', device_id: 'dev1', session_id: null, issued_ms: 1, last_issued_ms: 2 }])
  assert.deepEqual(await grantsFor(exec, ['k']), new Map([['k', { workerId: 7, deviceId: 'dev1', sessionId: null, issuedAtMs: 1, lastIssuedAtMs: 2 }]]))
  assert.equal((await grantsFor(exec, [])).size, 0)
})

test('before migration 0054 uploads keep working and nothing is post-cutover', async () => {
  const missing = Object.assign(new Error('relation "capture_upload_grants" does not exist'), { code: '42P01' })
  const { exec } = recordingExec(() => missing)
  assert.equal(await recordUploadGrant(exec, { ...grant, objectExists: false }), 'granted')
  assert.equal(await grantCutoverMs(exec), null)
  assert.equal((await grantsFor(exec, ['k'])).size, 0)
  const other = recordingExec(() => new Error('connection reset'))
  await assert.rejects(recordUploadGrant(other.exec, { ...grant, objectExists: true }), /connection reset/)
})
