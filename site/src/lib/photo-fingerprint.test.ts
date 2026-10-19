/**
 * Evidence photo fingerprints (lib/photo-fingerprint.ts + -compute.ts):
 * dHash math, EXIF capture time, real JPEGs through sharp, and the rules that
 * turn fingerprints into review flags (only exact cross-job reuse refuses).
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import sharp from 'sharp'
import {
  EXIF_STALE_BEFORE_WORK_MS,
  NEAR_DUPLICATE_MAX_HAMMING,
  dhashFromGray9x8,
  evaluateBeforeAfter,
  evaluatePhoto,
  exifCaptureTime,
  hammingDistance64,
  parseExifDateTime,
  type EvidenceContext,
  type FingerprintFacts,
} from './photo-fingerprint'
import { computeFingerprint, fetchBlobBounded, isFetchableBlobUrl, withTimeout } from './photo-fingerprint-compute'
import { flagsForUse } from './photo-fingerprint-db'

// ── dHash math ──────────────────────────────────────────────────────────────

test('dhashFromGray9x8: one bit per left>right comparison, row-major, signed 64-bit', () => {
  const descending = Array.from({ length: 72 }, (_, i) => 255 - (i % 9) * 10)
  assert.equal(dhashFromGray9x8(descending), -1n, 'every left pixel brighter → all 64 bits set')
  const ascending = Array.from({ length: 72 }, (_, i) => (i % 9) * 10)
  assert.equal(dhashFromGray9x8(ascending), 0n)
  assert.throws(() => dhashFromGray9x8(new Uint8Array(10)))
})

test('hammingDistance64 counts differing bits across the signed range', () => {
  assert.equal(hammingDistance64(0n, 0n), 0)
  assert.equal(hammingDistance64(0n, -1n), 64)
  assert.equal(hammingDistance64(0b1011n, 0b0001n), 2)
  assert.equal(hammingDistance64(BigInt.asIntN(64, 1n << 63n), 0n), 1)
})

// ── EXIF ────────────────────────────────────────────────────────────────────

/** Little-endian EXIF block: IFD0 → Exif IFD with DateTimeOriginal (+ offset). */
function exifBlock(dateTimeOriginal: string, offset?: string): Buffer {
  const entries = offset ? 2 : 1
  const exifIfdAt = 8 + 2 + 12 + 4
  const dataAt = exifIfdAt + 2 + entries * 12 + 4
  const tiff = Buffer.alloc(dataAt + 20 + 8)
  tiff.write('II', 0, 'latin1')
  tiff.writeUInt16LE(42, 2)
  tiff.writeUInt32LE(8, 4)
  tiff.writeUInt16LE(1, 8)
  tiff.writeUInt16LE(0x8769, 10)
  tiff.writeUInt16LE(4, 12)
  tiff.writeUInt32LE(1, 14)
  tiff.writeUInt32LE(exifIfdAt, 18)
  tiff.writeUInt16LE(entries, exifIfdAt)
  const entry = (n: number, tag: number, count: number, at: number) => {
    const e = exifIfdAt + 2 + n * 12
    tiff.writeUInt16LE(tag, e)
    tiff.writeUInt16LE(2, e + 2)
    tiff.writeUInt32LE(count, e + 4)
    tiff.writeUInt32LE(at, e + 8)
  }
  entry(0, 0x9003, 20, dataAt)
  tiff.write(`${dateTimeOriginal}\0`, dataAt, 'latin1')
  if (offset) {
    entry(1, 0x9011, 7, dataAt + 20)
    tiff.write(`${offset}\0`, dataAt + 20, 'latin1')
  }
  return Buffer.concat([Buffer.from('Exif\0\0', 'latin1'), tiff])
}

test('exifCaptureTime: DateTimeOriginal with its offset; without one it is read as UTC', () => {
  assert.equal(exifCaptureTime(exifBlock('2026:10:07 08:15:30', '-05:00')), '2026-10-07T13:15:30.000Z')
  assert.equal(exifCaptureTime(exifBlock('2026:10:07 08:15:30')), '2026-10-07T08:15:30.000Z')
})

test('exifCaptureTime: absent, truncated or garbage EXIF → null, never throws', () => {
  assert.equal(exifCaptureTime(null), null)
  assert.equal(exifCaptureTime(Buffer.from('Exif\0\0MM')), null)
  assert.equal(exifCaptureTime(exifBlock('2026:10:07 08:15:30').subarray(0, 30)), null)
  assert.equal(exifCaptureTime(Buffer.alloc(200, 0xff)), null)
  assert.equal(parseExifDateTime('0000:00:00 00:00:00'), null)
  assert.equal(parseExifDateTime('not a date'), null)
})

// ── Real JPEGs through sharp ────────────────────────────────────────────────

/** A deterministic noisy RGB image, so dHash has structure to hash. */
function noise(seed: number, size = 96): Buffer {
  const px = Buffer.alloc(size * size * 3)
  let s = seed
  for (let i = 0; i < px.length; i++) {
    s = (s * 1103515245 + 12345) & 0x7fffffff
    px[i] = (s >> 16) & 0xff
  }
  return px
}

async function jpeg(seed: number, quality = 85, size = 96): Promise<Buffer> {
  return sharp(noise(seed, size), { raw: { width: size, height: size, channels: 3 } }).blur(3).jpeg({ quality }).toBuffer()
}

test('computeFingerprint: re-encodes of one photo are near-duplicates; a different photo is not', async () => {
  const a = await computeFingerprint(await jpeg(1, 90))
  const aAgain = await computeFingerprint(await jpeg(1, 60))
  const b = await computeFingerprint(await jpeg(2, 90))
  assert.notEqual(a.sha256, aAgain.sha256, 'different bytes')
  assert.ok(hammingDistance64(a.dhash, aAgain.dhash) <= NEAR_DUPLICATE_MAX_HAMMING, 'same picture')
  assert.ok(hammingDistance64(a.dhash, b.dhash) > NEAR_DUPLICATE_MAX_HAMMING * 2, 'different picture')
  assert.equal(a.width, 96)
  assert.equal(a.height, 96)
  assert.equal(a.sha256.length, 64)
})

test('computeFingerprint: EXIF comes from the original upload when the stored copy has none', async () => {
  const original = await sharp(await jpeg(3))
    .withExif({ IFD0: { DateTime: '2026:10:01 12:00:00' } })
    .jpeg()
    .toBuffer()
  const stored = await sharp(original).jpeg().toBuffer() // re-encoded, metadata stripped
  assert.equal((await computeFingerprint(stored)).exifCapturedAt, null)
  assert.equal((await computeFingerprint(stored, original)).exifCapturedAt, '2026-10-01T12:00:00.000Z')
})

test('computeFingerprint rejects bytes that are not an image (caller records unverified)', async () => {
  await assert.rejects(computeFingerprint(Buffer.from('not an image')))
})

// ── Blob fetch safety (Grok review HIGH 1) ──────────────────────────────────

const STORE = 'abc.public.blob.vercel-storage.com'
const BLOB_URL = `https://${STORE}/spots/7/a.jpg`

/** A fetcher that records whether it was called and with what init. */
function stubFetch(make: () => Response) {
  const calls: Array<{ url: string; init: RequestInit }> = []
  const fetcher = async (url: string, init: RequestInit) => {
    calls.push({ url, init })
    return make()
  }
  return { fetcher, calls }
}

test('fetchBlobBounded: only https on our blob store — anything else is never requested', async () => {
  for (const url of [
    `http://${STORE}/spots/7/a.jpg`,
    'https://evil.example.com/spots/7/a.jpg',
    `https://other.public.blob.vercel-storage.com/spots/7/a.jpg`,
    `https://${STORE}:8443/spots/7/a.jpg`,
    `https://user:pw@${STORE}/spots/7/a.jpg`,
    'http://169.254.169.254/latest/meta-data/',
  ]) {
    const { fetcher, calls } = stubFetch(() => new Response(new Uint8Array([1])))
    await assert.rejects(fetchBlobBounded(url, fetcher, 1000, STORE), /not our blob store/, url)
    assert.equal(calls.length, 0, `${url} must not be fetched`)
  }
  assert.equal(isFetchableBlobUrl(BLOB_URL, STORE), true)
  assert.equal(isFetchableBlobUrl(`https://zzz.public.blob.vercel-storage.com/x.jpg`, null), true, 'no token: any Vercel Blob host')
  assert.equal(isFetchableBlobUrl('https://evil.example.com/x.jpg', null), false)
})

test('fetchBlobBounded: never follows a redirect, and refuses a response that landed elsewhere', async () => {
  const { fetcher, calls } = stubFetch(() => new Response(new Uint8Array([1, 2, 3])))
  assert.deepEqual([...(await fetchBlobBounded(BLOB_URL, fetcher, 1000, STORE))], [1, 2, 3])
  assert.equal(calls[0].init.redirect, 'error', 'redirects are not followed')

  const redirect = stubFetch(() => new Response(null, { status: 302, headers: { location: 'http://169.254.169.254/' } }))
  await assert.rejects(fetchBlobBounded(BLOB_URL, redirect.fetcher, 1000, STORE), /redirect/)

  const moved = stubFetch(() => {
    const res = new Response(new Uint8Array([1]))
    Object.defineProperty(res, 'url', { value: 'https://evil.example.com/a.jpg' })
    return res
  })
  await assert.rejects(fetchBlobBounded(BLOB_URL, moved.fetcher, 1000, STORE), /another URL/)
  await assert.rejects(fetchBlobBounded(BLOB_URL, stubFetch(() => new Response('', { status: 404 })).fetcher, 1000, STORE), /404/)
})

test('fetchBlobBounded: the byte cap is enforced while streaming, not after buffering', async () => {
  const lying = stubFetch(() => new Response('x', { headers: { 'content-length': String(64 * 1024 * 1024) } }))
  await assert.rejects(fetchBlobBounded(BLOB_URL, lying.fetcher, 1000, STORE), /too large/)

  // No Content-Length, endless 1 MB chunks: must stop just past the cap.
  let pulled = 0
  const endless = new ReadableStream<Uint8Array>({
    pull(controller) {
      pulled++
      controller.enqueue(new Uint8Array(1024 * 1024))
    },
  })
  await assert.rejects(fetchBlobBounded(BLOB_URL, async () => new Response(endless), 1000, STORE), /too large/)
  assert.ok(pulled <= 20, `stopped after ${pulled} chunks`)
  await assert.rejects(withTimeout(new Promise(() => {}), 20), /timeout/)
})

// ── Rules ───────────────────────────────────────────────────────────────────

const JOB: EvidenceContext = { kind: 'job', id: 10, workStartedAt: '2026-10-07T09:00:00.000Z' }
const SCAN: EvidenceContext = { kind: 'scan', id: 20, workStartedAt: '2026-10-07T09:00:00.000Z' }

const facts = (over: Partial<FingerprintFacts> = {}): FingerprintFacts => ({
  url: 'https://x/spots/7/a.jpg',
  status: 'ok',
  sha256: 'a'.repeat(64),
  dhash: 0x0f0f0f0f0f0f0f0fn,
  exifCapturedAt: null,
  firstJobId: null,
  firstScanId: null,
  ...over,
})

test('a fresh photo with nothing like it: no flags, not refused', () => {
  assert.deepEqual(evaluatePhoto(facts(), JOB, [], []), { flags: [], reject: null })
})

test('exact reuse across different jobs is the one hard refusal', () => {
  const sameUrlElsewhere = evaluatePhoto(facts({ firstJobId: 99 }), JOB, [], [])
  assert.deepEqual(sameUrlElsewhere.flags, ['reused_other_job'])
  assert.match(sameUrlElsewhere.reject ?? '', /job #99/)

  const reuploaded = evaluatePhoto(facts(), JOB, [{ url: 'https://x/spots/7/old.jpg', firstJobId: 98, firstScanId: null, distance: 0 }], [])
  assert.match(reuploaded.reject ?? '', /job #98/, 'same bytes under a new URL')

  assert.equal(evaluatePhoto(facts({ firstJobId: 10 }), JOB, [], []).reject, null, 'resubmitting for the same job is fine')
})

test('reuse across scans, or between a scan and a job, is flagged but not refused', () => {
  const otherScan = evaluatePhoto(facts({ firstScanId: 21 }), SCAN, [], [])
  assert.deepEqual(otherScan, { flags: ['reused_other_scan'], reject: null })
  const fromScan = evaluatePhoto(facts({ firstScanId: 20 }), JOB, [], [])
  assert.deepEqual(fromScan, { flags: ['reused_across_job_and_scan'], reject: null })
})

test('near-duplicates of photos from other work are flagged; of nothing bound, ignored', () => {
  const near = [{ url: 'https://x/n.jpg', firstJobId: 50, firstScanId: null, distance: 3 }]
  assert.deepEqual(evaluatePhoto(facts(), JOB, [], near).flags, ['near_duplicate_other_work'])
  const unbound = [{ url: 'https://x/n.jpg', firstJobId: null, firstScanId: null, distance: 3 }]
  assert.deepEqual(evaluatePhoto(facts(), JOB, [], unbound).flags, [])
  const far = [{ url: 'https://x/n.jpg', firstJobId: 50, firstScanId: null, distance: NEAR_DUPLICATE_MAX_HAMMING + 1 }]
  assert.deepEqual(evaluatePhoto(facts(), JOB, [], far).flags, [])
})

test('EXIF far older than the work is flagged; within a day (timezone slack) it is not', () => {
  const started = Date.parse(JOB.workStartedAt!)
  const old = new Date(started - EXIF_STALE_BEFORE_WORK_MS - 60_000).toISOString()
  const tzSkew = new Date(started - 13 * 3600_000).toISOString()
  assert.deepEqual(evaluatePhoto(facts({ exifCapturedAt: old }), JOB, [], []).flags, ['exif_before_work'])
  assert.deepEqual(evaluatePhoto(facts({ exifCapturedAt: tzSkew }), JOB, [], []).flags, [])
})

test('an unverified fingerprint is flagged, and still not refused', () => {
  assert.deepEqual(evaluatePhoto(facts({ status: 'unverified', sha256: null, dhash: null }), JOB, [], []), {
    flags: ['unverified'],
    reject: null,
  })
})

test('before/after: identical bytes or URL, near-identical, or distinct', () => {
  const before = facts({ url: 'https://x/b.jpg', sha256: 'b'.repeat(64), dhash: 0n })
  assert.deepEqual(evaluateBeforeAfter(before, before), ['identical_before_after'])
  assert.deepEqual(evaluateBeforeAfter(before, facts({ url: 'https://x/c.jpg', sha256: 'b'.repeat(64) })), ['identical_before_after'])
  assert.deepEqual(evaluateBeforeAfter(before, facts({ url: 'https://x/c.jpg', sha256: 'c'.repeat(64), dhash: 0b111n })), [
    'near_duplicate_before_after',
  ])
  assert.deepEqual(evaluateBeforeAfter(before, facts({ url: 'https://x/c.jpg', sha256: 'c'.repeat(64), dhash: -1n })), [])
  assert.deepEqual(evaluateBeforeAfter(before, facts({ url: 'https://x/c.jpg', sha256: null, dhash: null })), [])
})

test('flagsForUse: first use keeps its recorded flags; any other work sees the reuse', () => {
  const stored = { status: 'ok', flags: ['exif_before_work'], firstJobId: null, firstScanId: 20 }
  assert.deepEqual(flagsForUse(stored, { kind: 'scan', id: 20 }), ['exif_before_work'])
  assert.deepEqual(flagsForUse(stored, { kind: 'scan', id: 21 }), ['reused_other_scan'])
  assert.deepEqual(flagsForUse({ ...stored, firstJobId: 10 }, { kind: 'job', id: 11 }), ['reused_other_job'])
  assert.deepEqual(flagsForUse(stored, { kind: 'job', id: 11 }), ['exif_before_work'], 'scan photo shown on a job: its own flags')
  assert.deepEqual(flagsForUse(undefined, { kind: 'job', id: 11 }), [])
})
