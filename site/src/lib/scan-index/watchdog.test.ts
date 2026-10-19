import { test } from 'node:test'
import assert from 'node:assert/strict'
import { DEFAULT_THRESHOLDS, runWatchdog, thresholdsFrom, type WatchdogDeps } from './watchdog'

const NOW = Date.UTC(2026, 9, 18, 12)
const H = 3_600_000

function deps(over: Partial<WatchdogDeps> = {}): WatchdogDeps {
  return {
    async listRaw() {
      return new Map([
        ['raw/1:2/d/a-manifest.json', { size: 1, lastModifiedMs: NOW - 30 * H }],
        ['raw/1:2/d/b-manifest.json', { size: 1, lastModifiedMs: NOW - 1 * H }], // inside the grace window
        ['raw/1:2/d/a/capture/c.mov', { size: 1, lastModifiedMs: NOW - 30 * H }],
      ])
    },
    async indexedManifestKeys() { return new Set(['raw/1:2/d/a-manifest.json']) },
    async civicWatermarkMs() { return NOW - 10 * H },
    async stuckReconJobs() { return { count: 0, oldestMs: null } },
    async manifestRejects() { return [] },
    async markRejectsAlerted() {},
    ...over,
  }
}

test('healthy pipeline', async () => {
  const r = await runWatchdog(deps(), DEFAULT_THRESHOLDS, NOW)
  assert.equal(r.healthy, true)
  assert.equal(r.scanIndex.unindexedOverdue, 0)
})

test('an old unindexed manifest trips the scan check', async () => {
  const r = await runWatchdog(deps({ async indexedManifestKeys() { return new Set() } }), DEFAULT_THRESHOLDS, NOW)
  assert.equal(r.healthy, false)
  assert.equal(r.scanIndex.ok, false)
  assert.equal(r.scanIndex.unindexedOverdue, 1, 'the 1h-old manifest is still within the window')
  assert.deepEqual(r.scanIndex.examples, ['raw/1:2/d/a-manifest.json'])
  assert.match(r.scanIndex.detail, /30\.0h ago/)
})

test('missing R2 config is a failure, not a pass', async () => {
  const r = await runWatchdog(deps({ async listRaw() { return null } }), DEFAULT_THRESHOLDS, NOW)
  assert.equal(r.scanIndex.ok, false)
})

test('a stale or empty 311 mirror trips the civic check', async () => {
  assert.equal((await runWatchdog(deps({ async civicWatermarkMs() { return NOW - 49 * H } }), DEFAULT_THRESHOLDS, NOW)).civicSync.ok, false)
  assert.equal((await runWatchdog(deps({ async civicWatermarkMs() { return null } }), DEFAULT_THRESHOLDS, NOW)).civicSync.ok, false)
})

test('unclaimed reconstruction jobs trip the queue check, with the cutoff passed through', async () => {
  let cutoff = 0
  const r = await runWatchdog(deps({
    async stuckReconJobs(before) {
      cutoff = before
      return { count: 2, oldestMs: NOW - 40 * H }
    },
  }), DEFAULT_THRESHOLDS, NOW)
  assert.equal(cutoff, NOW - 24 * H)
  assert.equal(r.reconQueue.ok, false)
  assert.equal(r.reconQueue.stuck, 2)
})

test('thresholds from the query string; bad values keep defaults', () => {
  assert.deepEqual(thresholdsFrom(new URLSearchParams('scan_hours=2&civic_hours=x&recon_hours=-1')), {
    scanHours: 2, civicHours: 48, reconHours: 24,
  })
})

test('a rejected manifest alerts once, then stays out of the unindexed check', async () => {
  const rows = [{ r2Key: 'raw/1:2/d/a-manifest.json', reason: 'path: expected array', alerted: false }]
  const marked: string[][] = []
  const d = deps({
    async indexedManifestKeys() { return new Set() },
    async manifestRejects() { return rows.map((r) => ({ ...r })) },
    async markRejectsAlerted(keys) {
      marked.push(keys)
      for (const r of rows) if (keys.includes(r.r2Key)) r.alerted = true
    },
  })
  const first = await runWatchdog(d, DEFAULT_THRESHOLDS, NOW)
  assert.equal(first.healthy, false)
  assert.equal(first.rejectedManifests.ok, false)
  assert.deepEqual(first.rejectedManifests.newlyRejected, [{ r2Key: 'raw/1:2/d/a-manifest.json', reason: 'path: expected array' }])
  assert.equal(first.scanIndex.ok, true, 'a rejected manifest is not also "unindexed"')
  assert.deepEqual(marked, [['raw/1:2/d/a-manifest.json']])

  const second = await runWatchdog(d, DEFAULT_THRESHOLDS, NOW)
  assert.equal(second.healthy, true)
  assert.equal(second.rejectedManifests.known, 1)
  assert.equal(marked.length, 1)
})
