import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { verificationDocKey } from './verification-doc-key'

// Same pattern GET /api/admin/verification-docs accepts.
const ADMIN_KEY_PATTERN = /^verification\/\d+\/[0-9A-Za-z._-]{1,200}$/

test('keys live under the worker’s verification/ prefix', () => {
  assert.equal(verificationDocKey(42, 'government_id', 'jpg', 1700000000000), 'verification/42/government_id-1700000000000.jpg')
})

test('the admin presign route accepts both document kinds and both extensions', () => {
  for (const kind of ['government_id', 'proof_of_address', 'address_change'] as const) {
    for (const ext of ['jpg', 'pdf'] as const) {
      assert.match(verificationDocKey(7, kind, ext), ADMIN_KEY_PATTERN)
    }
  }
})

test('rejects a non-positive or non-integer worker id', () => {
  assert.throws(() => verificationDocKey(0, 'government_id', 'jpg'))
  assert.throws(() => verificationDocKey(-1, 'government_id', 'jpg'))
  assert.throws(() => verificationDocKey(1.5, 'government_id', 'jpg'))
})

// Identity documents must never be written to public storage. Both upload
// routes are checked by source so a regression fails before it ships.
for (const route of [
  'app/api/workers/upload-document/route.ts',
  'app/api/workers/me/verification/upload/route.ts',
  'app/api/workers/address-change/route.ts',
]) {
  test(`${route} never stores documents publicly`, () => {
    const src = readFileSync(join(__dirname, '..', route), 'utf8')
    assert.equal(/access:\s*['"]public['"]/.test(src), false, 'uses public access')
    assert.equal(src.includes('@vercel/blob'), false, 'imports Vercel Blob')
    assert.ok(src.includes('PutObjectCommand'), 'writes to private R2')
  })
}
