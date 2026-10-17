import { test } from 'node:test'
import assert from 'node:assert/strict'
import crypto from 'crypto'
import {
  isLegacyDocPath, isPublicBlobUrl, legacyKey, migrateLegacyDocs, workerIdFor,
  type MigrationDeps,
} from './legacy-verification-docs'

const ADMIN_KEY_PATTERN = /^verification\/\d+\/[0-9A-Za-z._-]{1,200}$/
const HOST = 'https://abc.public.blob.vercel-storage.com'
const md5 = (b: Buffer) => crypto.createHash('md5').update(b).digest('hex')

/** In-memory Blob + R2 + media table. */
function fakeWorld(opts: { corruptPut?: boolean; rowChanges?: boolean; failDelete?: boolean } = {}) {
  const blobs = new Map<string, Buffer>([
    [`${HOST}/worker-docs/42/government_id-1.jpg`, Buffer.from('id-bytes')],
    [`${HOST}/government-id/abc.jpg`, Buffer.from('mobile-id')],
    [`${HOST}/worker-docs/42/proof_of_address-2.pdf`, Buffer.from('orphan-poa')],
    [`${HOST}/spots/42/photo.jpg`, Buffer.from('spot')],
  ])
  const media = new Map<number, { url: string; category: string }>([
    [1, { url: `${HOST}/worker-docs/42/government_id-1.jpg`, category: 'worker_id' }],
    [2, { url: `${HOST}/government-id/abc.jpg`, category: 'worker_id' }],
    [3, { url: `${HOST}/spots/42/photo.jpg`, category: 'spot_photo' }],
  ])
  const r2 = new Map<string, Buffer>()
  const deps: MigrationDeps = {
    async listLegacyMediaRows() {
      return [
        { id: 1, url: media.get(1)!.url, mimeType: 'image/jpeg', workerId: 42 },
        { id: 2, url: media.get(2)!.url, mimeType: 'image/jpeg', workerId: 7 },
      ].filter((r) => media.get(r.id)!.url.startsWith('https://'))
    },
    async listAllMediaUrls() {
      return new Set([...media.values()].map((m) => m.url))
    },
    async listBlobs(prefix) {
      return [...blobs.keys()].map((url) => ({ url, pathname: url.slice(HOST.length + 1) }))
        .filter((b) => b.pathname.startsWith(prefix))
    },
    async fetchPublic(url) {
      const b = blobs.get(url)
      return b ? { bytes: b, contentType: url.endsWith('.pdf') ? 'application/pdf' : 'image/jpeg' } : null
    },
    async putPrivate(key, bytes) {
      r2.set(key, opts.corruptPut ? Buffer.from('corrupt') : bytes)
    },
    async headPrivate(key) {
      const b = r2.get(key)
      return b ? { size: b.length, etag: `"${md5(b)}"` } : null
    },
    async repointMedia(id, fromUrl, toKey) {
      const row = media.get(id)!
      if (opts.rowChanges || row.url !== fromUrl) return false
      row.url = toKey
      return true
    },
    async isUrlReferenced(url) {
      return [...media.values()].some((m) => m.url === url)
    },
    async deletePublic(url) {
      if (opts.failDelete) throw new Error('blob delete failed')
      blobs.delete(url)
    },
  }
  return { deps, blobs, media, r2 }
}

test('recognises only public Blob URLs', () => {
  assert.equal(isPublicBlobUrl(`${HOST}/x.jpg`), true)
  assert.equal(isPublicBlobUrl('verification/1/x.jpg'), false)
  assert.equal(isPublicBlobUrl('https://evil.example.com/x.jpg'), false)
})

test('legacy document paths include address-change but not other workers/ paths', () => {
  assert.equal(isLegacyDocPath('workers/5/address-change/1-a.pdf'), true)
  assert.equal(isLegacyDocPath('workers/5/avatar.jpg'), false)
  assert.equal(isLegacyDocPath('spots/5/a.jpg'), false)
})

test('worker id falls back from the row to the path to 0', () => {
  assert.equal(workerIdFor({ workerId: 9 }, 'government-id/a.jpg'), 9)
  assert.equal(workerIdFor({ workerId: null }, 'worker-docs/12/a.jpg'), 12)
  assert.equal(workerIdFor({ workerId: null }, 'government-id/a.jpg'), 0)
})

test('legacy keys pass the admin presign pattern', () => {
  assert.match(legacyKey(42, 'm17', 'pdf'), ADMIN_KEY_PATTERN)
  assert.match(legacyKey(0, 'orphan-0123456789abcdef', 'jpg'), ADMIN_KEY_PATTERN)
})

test('dry run plans every document and touches nothing', async () => {
  const w = fakeWorld()
  const r = await migrateLegacyDocs(w.deps, { apply: false, limit: 50 })
  assert.equal(r.candidates, 3) // 2 media rows + 1 orphan; the spot photo is not a document
  assert.ok(r.manifest.every((m) => m.status === 'planned'))
  assert.equal(w.r2.size, 0)
  assert.equal(w.blobs.size, 4)
  assert.ok(w.media.get(1)!.url.startsWith('https://'))
})

test('apply copies, verifies, repoints, then deletes the public copy', async () => {
  const w = fakeWorld()
  const r = await migrateLegacyDocs(w.deps, { apply: true, limit: 50 })
  assert.equal(r.moved, 3)
  assert.equal(r.failed, 0)
  assert.equal(r.remaining, 0)
  assert.equal(w.media.get(1)!.url, 'verification/42/legacy-m1.jpg')
  assert.equal(w.media.get(2)!.url, 'verification/7/legacy-m2.jpg')
  assert.deepEqual(w.r2.get('verification/42/legacy-m1.jpg'), Buffer.from('id-bytes'))
  assert.deepEqual([...w.blobs.keys()], [`${HOST}/spots/42/photo.jpg`]) // only the spot photo is left public
  assert.equal(w.media.get(3)!.url, `${HOST}/spots/42/photo.jpg`)
  const orphan = r.manifest.find((m) => m.kind === 'orphan')!
  assert.match(orphan.to, /^verification\/42\/legacy-orphan-[0-9a-f]{16}\.pdf$/)
})

test('a failed checksum keeps the public copy and the row', async () => {
  const w = fakeWorld({ corruptPut: true })
  const r = await migrateLegacyDocs(w.deps, { apply: true, limit: 50 })
  assert.equal(r.moved, 0)
  assert.ok(r.manifest.every((m) => m.status === 'verify-failed'))
  assert.equal(w.blobs.size, 4)
  assert.ok(w.media.get(1)!.url.startsWith('https://'))
})

test('a row that changed underneath keeps its public copy', async () => {
  const w = fakeWorld({ rowChanges: true })
  const r = await migrateLegacyDocs(w.deps, { apply: true, limit: 50 })
  assert.equal(r.manifest.filter((m) => m.kind === 'media').every((m) => m.status === 'row-changed'), true)
  assert.ok(w.blobs.has(`${HOST}/worker-docs/42/government_id-1.jpg`))
})

test('a failed public delete is reported, not counted as moved', async () => {
  const w = fakeWorld({ failDelete: true })
  const r = await migrateLegacyDocs(w.deps, { apply: true, limit: 50 })
  assert.equal(r.moved, 0)
  assert.ok(r.manifest.every((m) => m.status === 'error'))
})

test('limit bounds one run; the rest stays for the next', async () => {
  const w = fakeWorld()
  const r = await migrateLegacyDocs(w.deps, { apply: true, limit: 1 })
  assert.equal(r.processed, 1)
  assert.equal(r.moved, 1)
  assert.equal(r.remaining, 2)
})

test('a document row pointing at a non-document path is never migrated or deleted', async () => {
  const w = fakeWorld()
  w.media.set(1, { url: `${HOST}/spots/42/photo.jpg`, category: 'worker_id' })
  const r = await migrateLegacyDocs(w.deps, { apply: true, limit: 50 })
  assert.ok(r.manifest.every((m) => !m.from.startsWith('spots/')))
  assert.ok(w.blobs.has(`${HOST}/spots/42/photo.jpg`))
})
