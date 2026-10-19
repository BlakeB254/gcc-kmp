import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  ManifestError,
  assertManifestKey,
  confineManifestKeys,
  isOwnedKey,
  UNKNOWN_CELL,
  artifactTypeForKey,
  buildChunkRows,
  buildSessionRow,
  cellIdFromKey,
  dedupeChunks,
  parseManifest,
  parseReconstructedKey,
  platformFor,
  videoCoveragePct,
} from './manifest'

function manifestJson(over: Record<string, unknown> = {}): string {
  return JSON.stringify({
    sessionId: 'env-1',
    deviceId: 'dev1',
    deviceModel: 'iPhone16,2',
    osVersion: '19.0',
    startedAtMs: 1_780_000_000_000,
    completedAtMs: 1_780_000_600_000,
    path: [],
    scannedCells: [{ lat: 1, lng: 2 }, { lat: 3, lng: 4 }],
    scannedCellSizeMeters: 10,
    observedItems: [{}],
    photoKeys: [],
    ...over,
  })
}

test('cell id is the segment after the top-level prefix, else null', () => {
  assert.equal(cellIdFromKey('raw/464846:-721194/dev/x-manifest.json'), '464846:-721194')
  assert.equal(cellIdFromKey('raw/-1:-2/x'), '-1:-2')
  assert.equal(cellIdFromKey('raw/unknown/dev/x-manifest.json'), null)
  assert.equal(cellIdFromKey('raw/1:2:3/x'), null)
  assert.equal(cellIdFromKey('photos'), null)
})

test("a manifest key without a cell indexes under the '0:0' sentinel", () => {
  const key = 'raw/nocell/dev1/env-1-manifest.json'
  const m = parseManifest(manifestJson({ photoKeys: ['raw/12:34/dev1/p.jpg', 'elsewhere/p2.jpg'] }))
  const rows = buildChunkRows(m, key, () => undefined, [])
  assert.equal(buildSessionRow(m, key, rows).locationCellId, UNKNOWN_CELL)
  // A photo with its own cell keeps it; one without falls back to the session's.
  assert.deepEqual(rows.map((r) => r.locationCellId), ['0:0', '12:34', '0:0'])
})

test('required manifest fields are enforced like the Kotlin data class', () => {
  for (const field of ['sessionId', 'deviceId', 'deviceModel', 'osVersion', 'startedAtMs', 'completedAtMs', 'path', 'scannedCells', 'scannedCellSizeMeters', 'observedItems', 'photoKeys']) {
    const o = JSON.parse(manifestJson())
    delete o[field]
    assert.throws(() => parseManifest(JSON.stringify(o)), ManifestError, field)
  }
  assert.throws(() => parseManifest('{nope'), ManifestError)
  assert.throws(() => parseManifest(manifestJson({ startedAtMs: 1.5 })), ManifestError)
  assert.throws(() => parseManifest(manifestJson({ photoKeys: [1] })), ManifestError)
  assert.throws(() => parseManifest(manifestJson({ objectCapture: { r2Key: 'x.usdz' } })), ManifestError, 'ObjectCaptureResult needs its dimensions')
  // Unknown keys are ignored; optional fields may be absent or null.
  const m = parseManifest(manifestJson({ somethingNew: true, lapKind: null, captureChunks: null }))
  assert.equal(m.lapKind, null)
  assert.equal(m.captureChunks, null)
})

test('chunk rows follow the manifest, then late capture objects, then usdz and event log', () => {
  const key = 'raw/1:2/dev1/env-1-manifest.json'
  const m = parseManifest(manifestJson({
    photoKeys: ['raw/1:2/dev1/a.jpg'],
    captureChunks: ['raw/1:2/env-1/capture/c0.jsonl', 'raw/1:2/env-1/capture/c0.bin'],
    objectCapture: { r2Key: 'raw/1:2/dev1/o.usdz', widthMeters: 1, heightMeters: 1, depthMeters: 1, volumeLiters: 1, estimatedWeightGrams: 5 },
    eventLogKey: 'raw/1:2/env-1/events.jsonl.gz',
  }))
  const sizes: Record<string, number> = {
    [key]: 100,
    'raw/1:2/dev1/a.jpg': 10,
    'raw/1:2/env-1/capture/c0.jsonl': 1,
    'raw/1:2/env-1/capture/c0.mov': 1000,
  }
  const listing = ['raw/1:2/env-1/capture/c0.jsonl', 'raw/1:2/env-1/capture/c0.mov', 'raw/1:2/env-9/capture/x.mov']
  const rows = buildChunkRows(m, key, (k) => sizes[k], listing)
  assert.deepEqual(rows.map((r) => [r.artifactType, r.format, r.r2Key.split('/').pop(), r.byteSize, r.capturedAtMs != null]), [
    ['manifest', 'json', 'env-1-manifest.json', 100, true],
    ['photo', 'jpg', 'a.jpg', 10, false],
    ['pose', 'jsonl', 'c0.jsonl', 1, false],
    ['other', 'bin', 'c0.bin', null, false],
    ['video', 'mov', 'c0.mov', 1000, false], // late: listed, not named in the manifest
    ['usdz', 'usdz', 'o.usdz', null, true],
    ['event_log', 'gz', 'events.jsonl.gz', null, true],
  ])
  const s = buildSessionRow(m, key, rows)
  assert.equal(s.totalBytes, 1111)
  assert.equal(s.chunkCount, 2, 'chunk_count is the manifest list, not the late objects')
  assert.equal(s.photoCount, 1)
  assert.equal(s.observedItemCount, 1)
  assert.equal(s.scannedCellCount, 2)
  assert.equal(s.hasObjectCapture, true)
  assert.equal(s.platform, 'ios')
})

test('duplicate keys collapse to the last row but still count toward total_bytes', () => {
  const key = 'raw/1:2/dev1/env-1-manifest.json'
  const m = parseManifest(manifestJson({ photoKeys: ['raw/1:2/dev1/a.jpg', 'raw/1:2/dev1/a.jpg'] }))
  const rows = buildChunkRows(m, key, () => 5, [])
  assert.equal(buildSessionRow(m, key, rows).totalBytes, 15)
  assert.equal(dedupeChunks(rows).length, 2)
})

test('total_bytes is null, not zero, when nothing was sized', () => {
  const key = 'raw/1:2/dev1/env-1-manifest.json'
  const m = parseManifest(manifestJson())
  assert.equal(buildSessionRow(m, key, buildChunkRows(m, key, () => undefined, [])).totalBytes, null)
})

test('artifact type by extension', () => {
  assert.equal(artifactTypeForKey('a/b.MOV'), 'video')
  assert.equal(artifactTypeForKey('a/b.hevc'), 'video')
  assert.equal(artifactTypeForKey('a/b.jsonl'), 'pose')
  assert.equal(artifactTypeForKey('a/b.png'), 'depth')
  assert.equal(artifactTypeForKey('a/b.ply'), 'pointcloud')
  assert.equal(artifactTypeForKey('a/b.HEIC'), 'photo')
  assert.equal(artifactTypeForKey('a/b.bin'), 'other')
  assert.equal(artifactTypeForKey('a/noext'), 'other')
})

test('platform is ios only for Apple handsets', () => {
  assert.equal(platformFor('iPad13,1'), 'ios')
  assert.equal(platformFor('Pixel 9'), null)
  assert.equal(platformFor(null), null)
})

test('video coverage matches ScanIndexerCaptureHealthTest', () => {
  assert.equal(videoCoveragePct(100, 50), 50)
  assert.equal(videoCoveragePct(60, 60), 100)
  assert.equal(videoCoveragePct(null, 50), null)
  assert.equal(videoCoveragePct(100, null), null)
  assert.equal(videoCoveragePct(0, 10), null)
  assert.equal(videoCoveragePct(-5, 10), null)
  assert.equal(videoCoveragePct(10, 40), 100)
  assert.equal(videoCoveragePct(10, -5), 0)
})

test('reconstructed keys parse; candidate bundles never do', () => {
  assert.deepEqual(parseReconstructedKey('reconstructed/1:2/7/splat.ply'), { cell: '1:2', version: 7, assetType: 'splat', format: 'ply' })
  assert.deepEqual(parseReconstructedKey('reconstructed/1:2/7/sub/mesh.GLB'), { cell: '1:2', version: 7, assetType: 'mesh', format: 'glb' })
  assert.equal(parseReconstructedKey('reconstructed/1:2/7/verified-candidate/verified.ply'), null)
  assert.equal(parseReconstructedKey('reconstructed/1:2/7/verified-candidate/manifest.json'), null)
  assert.equal(parseReconstructedKey('reconstructed/1:2/7'), null)
  assert.equal(parseReconstructedKey('reconstructed/x/7/a.ply'), null)
  assert.equal(parseReconstructedKey('reconstructed/1:2/v7/a.ply'), null)
  assert.equal(parseReconstructedKey('reconstructed/1:2/7/noext'), null)
  assert.equal(parseReconstructedKey('reconstructed/1:2/7/.ply'), null)
})

test('manifest key must be raw/{cell}/{deviceId}/{sessionId}-manifest.json for the ids it claims', () => {
  const m = parseManifest(manifestJson())
  assert.doesNotThrow(() => assertManifestKey('raw/1:2/dev1/env-1-manifest.json', m))
  assert.doesNotThrow(() => assertManifestKey('raw/0:0/dev1/env-1-manifest.json', m))
  for (const key of [
    'raw/1:2/dev2/env-1-manifest.json', // another device's folder
    'raw/1:2/dev1/env-2-manifest.json', // another session's name
    'raw/nocell/dev1/env-1-manifest.json',
    'raw/1:2/dev1/sub/env-1-manifest.json',
    'other/1:2/dev1/env-1-manifest.json',
  ]) assert.throws(() => assertManifestKey(key, m), ManifestError, key)
})

test('owned keys live under the folder of their namespace', () => {
  const ids = { deviceId: 'dev1', sessionId: 'env-1' }
  assert.equal(isOwnedKey('raw/1:2/dev1/p.jpg', ids, 'device'), true)
  assert.equal(isOwnedKey('raw/9:9/env-1/capture/c.mov', ids, 'session'), true, 'cell may differ from the manifest')
  assert.equal(isOwnedKey('raw/1:2/env-1/p.jpg', ids, 'device'), false, 'device keys never in the session folder')
  assert.equal(isOwnedKey('raw/1:2/dev1/capture/c.mov', ids, 'session'), false, 'session keys never in the device folder')
  assert.equal(isOwnedKey('raw/1:2/dev2/p.jpg', ids, 'device'), false)
  assert.equal(isOwnedKey('raw/1:2/env-2/capture/c.mov', ids, 'session'), false)
  assert.equal(isOwnedKey('raw/1:2/dev1/../dev2/p.jpg', ids, 'device'), false)
  assert.equal(isOwnedKey('raw/1:2/dev1//p.jpg', ids, 'device'), false)
  assert.equal(isOwnedKey('raw/x/dev1/p.jpg', ids, 'device'), false)
  assert.equal(isOwnedKey('verification/1/id.jpg', ids, 'device'), false)
})

test('a manifest whose device id equals its session id is refused', () => {
  const m = parseManifest(manifestJson({ deviceId: 'env-1' }))
  assert.throws(() => assertManifestKey('raw/1:2/env-1/env-1-manifest.json', m), /must differ/)
})

test('lists are confined to their own namespace, and the gate can veto any key', () => {
  const m = parseManifest(manifestJson({
    photoKeys: [
      'raw/1:2/dev1/ok.jpg',
      'raw/9:9/env-1/photos/photo-0.jpg', // keyframe, as CaptureChunkUploader writes it
      'raw/1:2/env-1/photo-in-session-root.jpg',
      'raw/1:2/env-1/capture/frame.jpg',
    ],
    captureChunks: ['raw/1:2/env-1/photos/photo-0.jpg', 'raw/1:2/dev1/capture/c.mov'],
  }))
  const { manifest, dropped } = confineManifestKeys(m)
  assert.deepEqual(manifest.photoKeys, ['raw/1:2/dev1/ok.jpg', 'raw/9:9/env-1/photos/photo-0.jpg'])
  assert.deepEqual(manifest.captureChunks, ['raw/1:2/env-1/photos/photo-0.jpg'])
  assert.equal(dropped.length, 3)
  const seen: string[] = []
  const gated = confineManifestKeys(m, (k, ns) => {
    seen.push(`${ns}:${k.split('/').pop()}`)
    return k !== 'raw/1:2/dev1/ok.jpg'
  })
  assert.deepEqual(gated.manifest.photoKeys, ['raw/9:9/env-1/photos/photo-0.jpg'])
  assert.ok(seen.includes('session:photo-0.jpg'), 'a session-folder photo is gated as a session key')
})

test('foreign keys are dropped from every list the manifest names', () => {
  const m = parseManifest(manifestJson({
    photoKeys: ['raw/1:2/dev1/ok.jpg', 'raw/1:2/devX/no.jpg'],
    captureChunks: ['raw/1:2/env-1/capture/ok.mov', 'raw/1:2/env-9/capture/no.mov'],
    objectCapture: { r2Key: 'verification/7/id.jpg', widthMeters: 1, heightMeters: 1, depthMeters: 1, volumeLiters: 1, estimatedWeightGrams: 1 },
    eventLogKey: 'raw/1:2/env-9/events.jsonl.gz',
  }))
  const { manifest, dropped } = confineManifestKeys(m)
  assert.deepEqual(manifest.photoKeys, ['raw/1:2/dev1/ok.jpg'])
  assert.deepEqual(manifest.captureChunks, ['raw/1:2/env-1/capture/ok.mov'])
  assert.deepEqual(manifest.objectCapture, { r2Key: null })
  assert.equal(manifest.eventLogKey, null)
  assert.equal(dropped.length, 4)
})

test('late capture objects must be in the session folder', () => {
  const key = 'raw/1:2/dev1/env-1-manifest.json'
  const m = parseManifest(manifestJson())
  const rows = buildChunkRows(m, key, () => 1, ['raw/1:2/env-1/capture/a.mov', 'raw/1:2/x/env-1/capture/b.mov'])
  assert.deepEqual(rows.map((r) => r.r2Key), [key, 'raw/1:2/env-1/capture/a.mov'])
})
