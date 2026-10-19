/**
 * POST /api/captures/upload-url records an upload grant for every key it
 * presigns (lib/scan-index/grants.ts), with the request shapes the app sends
 * (EnvScanSessionUploader / CaptureChunkUploader): the legacy device-folder
 * shape and the session-artifact shape. A real collision is 409; anything the
 * app must keep the file for (R2 unsure, another account's session on a
 * shared handset) is 503 + Retry-After — checked against the app's own retry
 * rule at the bottom.
 *
 * The database is a fake (src/test-support/fake-db.ts); presigning is local.
 */
import { test, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { NextRequest } from 'next/server'

process.env.JWT_SECRET = 'test-secret-for-upload-grant-tests-0123456789'
process.env.DATABASE_URL ??= 'postgres://test:test@localhost/test'
process.env.R2_ACCOUNT_ID = 'acct'
process.env.R2_ACCESS_KEY_ID = 'AKIATEST'
process.env.R2_SECRET_ACCESS_KEY = 'secret'
process.env.R2_BUCKET_NAME = 'bucket'

import { __setDbForTests } from '@/db'
import { createFakeDb, type FakeQuery } from '@/test-support/fake-db'
import { createMobileToken } from '@/lib/mobile-auth'
import { r2Probe } from '@/lib/scan-index/deps'
import { POST } from './route'

import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import type { ObjectState } from '@/lib/scan-index/grants'

let objectThere: ObjectState = 'absent'
r2Probe.state = async () => ({ state: objectThere, lastModifiedMs: objectThere === 'exists' ? Date.UTC(2026, 0, 1) : null }) // no network in tests

type Held = false | 'namespace' | 'key' | 'object'

async function call(body: unknown, held: Held) {
  const fake = createFakeDb((q: FakeQuery) => {
    if (q.op === 'select' && q.table === 'workers') return [{ email: 'w@example.org', emailVerified: true, tokenVersion: 0 }]
    if (q.op === 'execute' && q.sqlText?.includes('capture_upload_grants')) {
      return [{ owner: held === 'namespace' ? 8 : 7, granted: held ? 0 : 1, had_grant: held === 'key' ? 1 : 0 }]
    }
    return []
  })
  __setDbForTests(fake.db)
  const { token } = await createMobileToken({ id: 7, email: 'w@example.org', emailVerified: true, tokenVersion: 0 })
  const res = await POST(new NextRequest('http://localhost/api/captures/upload-url', {
    method: 'POST',
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
    body: JSON.stringify(body),
  }))
  return { res, json: await res.json(), grantQueries: fake.log.filter((q) => q.sqlText?.includes('capture_upload_grants')) }
}

afterEach(() => {
  __setDbForTests(null)
  objectThere = 'absent'
})

test('the manifest request (device folder) is presigned and granted', async () => {
  const { res, json, grantQueries } = await call(
    { locationCellId: '1:2', deviceId: 'dev1', capturedAt: 'env-1789149214237-2wj1ux-manifest', ext: 'json' },
    false,
  )
  assert.equal(res.status, 200)
  assert.equal(json.data.r2Key, 'raw/1:2/dev1/env-1789149214237-2wj1ux-manifest.json')
  assert.equal(grantQueries.length, 1)
})

test('a capture chunk request (session folder) is presigned and granted', async () => {
  const { res, json, grantQueries } = await call(
    { locationCellId: '1:2', deviceId: 'dev1', capturedAt: 'env-1789149214237-2wj1ux-chunk0-video', ext: 'mov', sessionId: 'env-1789149214237-2wj1ux', artifactPath: 'capture/chunk-0.mov' },
    false,
  )
  assert.equal(res.status, 200)
  assert.equal(json.data.r2Key, 'raw/1:2/env-1789149214237-2wj1ux/capture/chunk-0.mov')
  assert.equal(grantQueries.length, 1)
})

const deviceShape = { locationCellId: '1:2', deviceId: 'dev1', capturedAt: 'env-1789149214237-2wj1ux-manifest', ext: 'json' }
const sessionShape = { ...deviceShape, ext: 'mov', sessionId: 'env-1789149214237-2wj1ux', artifactPath: 'capture/c.mov' }

test("another worker's exact key is a 409 collision", async () => {
  for (const body of [deviceShape, sessionShape]) {
    const { res, json, grantQueries } = await call(body, 'key')
    assert.equal(res.status, 409)
    assert.equal(json.success, false)
    assert.equal(json.data, null)
    assert.equal(grantQueries[0].sqlText!.includes('insert into capture_namespace_owners'), 'sessionId' in body,
      'only a session-folder key claims its folder (device folders are shared)')
  }
})

test("another account's session on a shared handset is retryable: 503 + Retry-After, the app keeps the file", async () => {
  for (const body of [sessionShape, deviceShape]) {
    const { res, json } = await call(body, 'namespace')
    assert.equal(res.status, 503)
    assert.ok(Number(res.headers.get('retry-after')) > 0)
    assert.equal(json.success, false)
  }
})

test('R2 unable to say whether the key is free (timeout, 5xx, 403): 503, and nothing is granted', async () => {
  objectThere = 'unknown'
  const { res, grantQueries } = await call(sessionShape, false)
  assert.equal(res.status, 503)
  assert.ok(Number(res.headers.get('retry-after')) > 0)
  assert.equal(grantQueries.length, 0)
})

test("a manifest names its session: the folder owner and that session's owner are checked independently", async () => {
  const { grantQueries } = await call(deviceShape, false)
  const q = grantQueries[0].sqlText!.replace(/\s+/g, ' ')
  assert.match(q, /\(select worker_id from capture_namespace_owners where ns_id = \$\d+\) as folder_owner/)
  assert.match(q, /\(select worker_id from capture_namespace_owners where ns_id = \$\d+\) as related_owner/)
  assert.match(q, /when \(o\.folder_owner is null or o\.folder_owner = \$\d+::int\) and \(o\.related_owner is null or o\.related_owner = \$\d+::int\)/)
})

test('over-long or odd key parts are refused with 400 before anything is granted', async () => {
  const long = 'x'.repeat(2000)
  for (const body of [
    { locationCellId: '1:2', deviceId: 'dev1', capturedAt: long, ext: 'json' },
    { locationCellId: '1:2', deviceId: 'dev1', capturedAt: 'bad\nvalue', ext: 'json' },
    { locationCellId: `${'1'.repeat(20)}:2`, deviceId: 'dev1', capturedAt: 'c', ext: 'json' },
    { locationCellId: '1:2', deviceId: long, capturedAt: 'c', ext: 'json' },
    { locationCellId: '1:2', deviceId: long, capturedAt: 'c', ext: 'mov', sessionId: 'env-1789149214237-2wj1ux', artifactPath: 'capture/c.mov' },
    { locationCellId: '1:2', deviceId: 'dev1', capturedAt: 'c', ext: 'mov', sessionId: long, artifactPath: 'capture/c.mov' },
    { locationCellId: '1:2', deviceId: 'dev1', capturedAt: 'c', ext: 'mov', sessionId: 'env 1', artifactPath: 'capture/c.mov' },
  ]) {
    const { res, grantQueries } = await call(body, false)
    assert.equal(res.status, 400, JSON.stringify(body).slice(0, 80))
    assert.equal(grantQueries.length, 0)
  }
})

test("the app's real capturedAt shapes still work", async () => {
  for (const capturedAt of ['2026-10-18T22:32:34.988Z', 'oc-1789149214237', 'env-1789149214237-2wj1ux-events']) {
    const { res } = await call({ locationCellId: '1:2', deviceId: '0123456789abcdef0123456789abcdef', capturedAt, ext: 'jpg' }, false)
    assert.equal(res.status, 200, capturedAt)
  }
})

test('the first grant of a key where an object already exists is refused (legacy data stays unowned)', async () => {
  objectThere = 'exists'
  const { res, json } = await call({ locationCellId: '1:2', deviceId: 'dev1', capturedAt: 'env-1789149214237-2wj1ux-manifest', ext: 'json' }, 'object')
  assert.equal(res.status, 409)
  assert.equal(json.success, false)
})

// Contract with the app (no app change in this PR): CaptureChunkUploader drops
// a chunk for good on 4xx (isClientErrorMessage: code in 400..499) and keeps
// it for a later retry otherwise; createCaptureUploadUrl special-cases 501.
// The codes this route uses for "keep the file" must stay outside that range.
test('retryable refusals use a code the app retries; collisions one it drops', () => {
  const root = join(__dirname, '../../../../../..')
  const uploader = readFileSync(join(root, 'composeApp/src/commonMain/kotlin/com/gcc/app/platform/capture/CaptureChunkUploader.kt'), 'utf8')
  const range = /return code in (\d{3})\.\.(\d{3})/.exec(uploader)
  assert.ok(range, 'CaptureChunkUploader.isClientErrorMessage still poisons a status range')
  const [lo, hi] = [Number(range[1]), Number(range[2])]
  assert.match(uploader, /isClientErrorMessage\(e\.message\)\)\s*\{\s*poisonChunk\(chunk\)/, 'presign failures go through that rule')
  const poisons = (code: number) => code >= lo && code <= hi
  assert.equal(poisons(503), false, '503 keeps the file')
  assert.equal(poisons(409), true, '409 drops it (a real collision)')
})

test('session and device id formats are disjoint: either in the other shape is a 400', async () => {
  const sessionShape = { locationCellId: '1:2', deviceId: '0123456789abcdef0123456789abcdef', capturedAt: 'c', ext: 'mov', artifactPath: 'capture/c.mov' }
  // A session id must be the app's env-<epoch ms>-<6 chars>; a device id in that slot is refused.
  for (const sessionId of ['0123456789abcdef0123456789abcdef', 'env-1', 'env-1789149214237-2WJ1UX', 'env-1789149214237-2wj1u']) {
    const { res, grantQueries } = await call({ ...sessionShape, sessionId }, false)
    assert.equal(res.status, 400, sessionId)
    assert.equal(grantQueries.length, 0)
  }
  // A device id may not look like a session id (on either request shape).
  for (const body of [
    { locationCellId: '1:2', deviceId: 'env-1789149214237-2wj1ux', capturedAt: 'x', ext: 'jpg' },
    { ...sessionShape, deviceId: 'env-1789149214237-2wj1ux', sessionId: 'env-1789149214238-aaaaaa' },
  ]) {
    const { res, grantQueries } = await call(body, false)
    assert.equal(res.status, 400)
    assert.equal(grantQueries.length, 0)
  }
  // The real shapes pass.
  assert.equal((await call({ ...sessionShape, sessionId: 'env-1789149214237-2wj1ux' }, false)).res.status, 200)
})

test("the app's generators can only produce disjoint ids", () => {
  const root = join(__dirname, '../../../../../..')
  const device = readFileSync(join(root, 'composeApp/src/commonMain/kotlin/com/gcc/app/platform/CoverageDeviceId.kt'), 'utf8')
  assert.match(device, /val hex = "0123456789abcdef"/)
  assert.match(device, /repeat\(32\) \{ append\(hex\[Random\.nextInt\(16\)\]\) \}/, 'device id = 32 lowercase hex')
  const session = readFileSync(join(root, 'composeApp/src/commonMain/kotlin/com/gcc/app/platform/EnvScanSessionRecorder.kt'), 'utf8')
  assert.match(session, /append\("env-"\)\s*append\(Clock\.System\.now\(\)\.toEpochMilliseconds\(\)\)\s*append\("-"\)\s*repeat\(6\) \{ append\("abcdefghijklmnopqrstuvwxyz0123456789"/, 'session id = env-<ms>-<6 [a-z0-9]>')
})
