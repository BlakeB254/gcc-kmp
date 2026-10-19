import { test } from 'node:test'
import assert from 'node:assert/strict'
import { gridCell } from '@/lib/world-observations'
import {
  DEFAULT_CIVIC_SR_TYPES,
  PAGE_SIZE,
  buildPageUrl,
  dedupeBySr,
  parseFloating,
  parseSodaRow,
  runCivicSync,
  sodaLiteral,
  type CivicRow,
  type CivicSyncDeps,
} from './civic-sync'

test('the seven cleanup SR types, as CivicReportSync.kt / Config.kt had them', () => {
  assert.deepEqual(DEFAULT_CIVIC_SR_TYPES, [
    'Graffiti Removal Request',
    'Fly Dumping Complaint',
    'Sanitation Code Violation',
    'Weed Removal Request',
    'Clean Vacant Lot Request',
    'Street Cleaning Request',
    'Tree Debris Clean-Up Request',
  ])
})

test('page URL: type filter, strict watermark, ordered, 1000 rows', () => {
  const url = new URL(buildPageUrl(["O'Hare Request", 'Fly Dumping Complaint'], Date.UTC(2026, 8, 8, 14, 3, 7, 999)))
  assert.equal(url.origin + url.pathname, 'https://data.cityofchicago.org/resource/v6vf-nfxy.json')
  assert.equal(
    url.searchParams.get('$where'),
    "sr_type in('O''Hare Request','Fly Dumping Complaint') AND last_modified_date > '2026-09-08T14:03:07'",
  )
  assert.equal(url.searchParams.get('$order'), 'last_modified_date')
  assert.equal(url.searchParams.get('$limit'), String(PAGE_SIZE))
  assert.equal(sodaLiteral(Date.UTC(2026, 0, 1)), '2026-01-01T00:00:00')
})

test('floating portal timestamps read as UTC; junk is null', () => {
  assert.equal(parseFloating('2026-09-08T14:03:07.123'), Date.UTC(2026, 8, 8, 14, 3, 7, 123))
  assert.equal(parseFloating('2026-09-08T14:03:07Z'), Date.UTC(2026, 8, 8, 14, 3, 7))
  assert.equal(parseFloating('09/08/2026'), null)
  assert.equal(parseFloating(null), null)
})

test('SODA row → civic_reports row', () => {
  const row = parseSodaRow({
    sr_number: 'SR26-01234567',
    sr_type: 'Graffiti Removal Request',
    owner_department: 'Streets and Sanitation',
    created_date: '2026-09-01T08:00:00.000',
    last_modified_date: '2026-09-02T09:30:00.000',
    street_address: '123 N STATE ST',
    community_area: '32',
    latitude: '41.8781',
    longitude: '-87.6298',
  })!
  assert.equal(row.status, 'Unknown', 'missing status defaults like Kotlin')
  assert.equal(row.communityArea, 32)
  assert.equal(row.closedDateMs, null)
  // Byte-equal to the shared Kotlin gridCell10m (GridCellTest.kt fixture).
  assert.equal(row.locationCellId, '464846:-721194')
  assert.equal(row.locationCellId, gridCell(41.8781, -87.6298))

  assert.equal(parseSodaRow({ sr_type: 'x' }), null, 'sr_number required')
  assert.equal(parseSodaRow({ sr_number: 'SR1' }), null, 'sr_type required')
  assert.equal(parseSodaRow({ sr_number: 'SR1', sr_type: '   ' }), null, 'blank counts as missing')
  const noGeo = parseSodaRow({ sr_number: 'SR1', sr_type: 't', latitude: '41.8', community_area: '3.5' })!
  assert.equal(noGeo.locationCellId, null, 'a cell needs both coordinates')
  assert.equal(noGeo.communityArea, null, 'community_area is an Int')
  assert.equal(parseSodaRow({ sr_number: 'SR1', sr_type: 'x'.repeat(150) })!.srType.length, 100)
})

test('duplicate sr_numbers in a page collapse to the last', () => {
  const a = { srNumber: 'SR1', status: 'Open' } as CivicRow
  const b = { srNumber: 'SR1', status: 'Completed' } as CivicRow
  assert.deepEqual(dedupeBySr([a, b]).map((r) => r.status), ['Completed'])
})

function soda(pages: unknown[][], watermark: number | null) {
  const urls: string[] = []
  const written: CivicRow[][] = []
  const deps: CivicSyncDeps = {
    async currentWatermarkMs() { return watermark },
    async fetchJson(url) {
      urls.push(url)
      return { ok: true, body: pages.shift() ?? [] }
    },
    async upsert(rows) {
      written.push(rows)
      return rows.length
    },
  }
  return { deps, urls, written }
}

const row = (n: number, modified: string) => ({ sr_number: `SR${n}`, sr_type: 'Graffiti Removal Request', status: 'Open', last_modified_date: modified })

test('watermark advances page to page and resumes from the table', async () => {
  const full = Array.from({ length: PAGE_SIZE }, (_, i) => row(i, `2026-09-09T00:00:${String(i % 60).padStart(2, '0')}.000`))
  const s = soda([full, [row(5000, '2026-09-10T00:00:00.000')]], Date.UTC(2026, 8, 8))
  const r = await runCivicSync(s.deps, { deadlineMs: Number.POSITIVE_INFINITY })
  assert.equal(r.pages, 2)
  assert.equal(r.fetched, PAGE_SIZE + 1)
  assert.equal(r.caughtUp, true, 'a short page ends the sweep')
  assert.match(new URL(s.urls[0]).searchParams.get('$where')!, /> '2026-09-08T00:00:00'$/)
  assert.match(new URL(s.urls[1]).searchParams.get('$where')!, /> '2026-09-09T00:00:59'$/)
  assert.equal(r.watermark, '2026-09-10T00:00:00.000Z')
})

test('empty table backfills from now − backfill days', async () => {
  const now = Date.UTC(2026, 9, 18, 12)
  const s = soda([[]], null)
  const r = await runCivicSync(s.deps, { deadlineMs: Number.POSITIVE_INFINITY, backfillDays: 7, now: () => now })
  assert.match(new URL(s.urls[0]).searchParams.get('$where')!, /> '2026-10-11T12:00:00'$/)
  assert.equal(r.caughtUp, true)
  assert.equal(r.pages, 0)
})

test('a full page that does not move the watermark stops instead of spinning', async () => {
  const stuck = Array.from({ length: PAGE_SIZE }, (_, i) => row(i, '2026-09-08T00:00:00.000'))
  const s = soda([stuck, stuck], Date.UTC(2026, 8, 8))
  const r = await runCivicSync(s.deps, { deadlineMs: Number.POSITIVE_INFINITY })
  assert.equal(r.pages, 1)
  assert.equal(r.caughtUp, false)
})

test('a portal error stops the sweep and is reported', async () => {
  const deps: CivicSyncDeps = {
    async currentWatermarkMs() { return 0 },
    async fetchJson() { return { ok: false, status: 429, text: 'throttled' } },
    async upsert() { throw new Error('unreachable') },
  }
  const r = await runCivicSync(deps, { deadlineMs: Number.POSITIVE_INFINITY })
  assert.deepEqual(r.httpError, { status: 429, body: 'throttled' })
  assert.equal(r.pages, 0)
})

test('no request is allowed to outlive the hard deadline', async () => {
  const timeouts: number[] = []
  const deps: CivicSyncDeps = {
    async currentWatermarkMs() { return 0 },
    async fetchJson(_url, timeoutMs) {
      timeouts.push(timeoutMs)
      return { ok: true, body: [] }
    },
    async upsert() { return 0 },
  }
  await runCivicSync(deps, { deadlineMs: 10_000, hardDeadlineMs: 20_000, now: () => 5_000 })
  await runCivicSync(deps, { deadlineMs: Number.POSITIVE_INFINITY })
  assert.deepEqual(timeouts, [15_000, 60_000])
})

test('the time budget and page cap bound a run', async () => {
  const full = () => Array.from({ length: PAGE_SIZE }, (_, i) => row(i, `2026-09-0${1 + (i % 8)}T00:00:00.000`))
  let t = 0
  const s = soda([full(), full(), full()], 0)
  const r = await runCivicSync(s.deps, { deadlineMs: 2, now: () => t++ })
  assert.ok(r.pages < 3)
  assert.equal(r.caughtUp, false)
  const capped = soda([full(), full()], 0)
  assert.equal((await runCivicSync(capped.deps, { deadlineMs: Number.POSITIVE_INFINITY, maxPages: 1 })).pages, 1)
})
