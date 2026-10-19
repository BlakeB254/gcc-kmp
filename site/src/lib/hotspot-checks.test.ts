/**
 * Route-scan hotspot checks (lib/hotspot-checks.ts): volume cap, distance to
 * the route, photo origin, and scan points from server-checked values only.
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  HOTSPOT_ROUTE_BUFFER_METERS,
  MAX_HOTSPOT_VOLUME_LITERS,
  capHotspotVolume,
  distanceToRouteMeters,
  hotspotCountsAsCleaned,
  hotspotPlacement,
  isScanPhotoUrl,
  scanCompletionPoints,
  verificationScoreUpdate,
  type HotspotForPoints,
} from './hotspot-checks'
import { METERS_PER_DEG_LAT, METERS_PER_DEG_LNG } from './geo-utils'

// isOwnEvidenceUrl derives the store host from this token at call time.
// isOwnEvidenceUrl fails closed without a store, so tests configure ours:
// this token's store host is abc.public.blob.vercel-storage.com.
process.env.BLOB_READ_WRITE_TOKEN = 'vercel_blob_rw_abc_testsecret'

const ROUTE = { type: 'LineString', coordinates: [[-87.7202, 41.8601], [-87.7182, 41.8601]] }
const north = (m: number) => 41.8601 + m / METERS_PER_DEG_LAT

test('capHotspotVolume: null for nothing/nonsense, clamps to [0, cap]', () => {
  assert.equal(capHotspotVolume(null), null)
  assert.equal(capHotspotVolume(undefined), null)
  assert.equal(capHotspotVolume('abc'), null)
  assert.equal(capHotspotVolume(Infinity), null)
  assert.equal(capHotspotVolume(-5), 0)
  assert.equal(capHotspotVolume(42.5), 42.5)
  assert.equal(capHotspotVolume(1e6), MAX_HOTSPOT_VOLUME_LITERS)
  assert.equal(MAX_HOTSPOT_VOLUME_LITERS, 200)
})

test('distanceToRouteMeters: perpendicular distance to the segment, endpoint distance past its ends', () => {
  const mid = -87.7192
  assert.ok(Math.abs(distanceToRouteMeters(north(20), mid, ROUTE)! - 20) < 0.01)
  assert.ok(Math.abs(distanceToRouteMeters(41.8601, mid, ROUTE)!) < 0.01)
  // 30 m east of the east end, on the line.
  const pastEnd = -87.7182 + 30 / METERS_PER_DEG_LNG
  assert.ok(Math.abs(distanceToRouteMeters(41.8601, pastEnd, ROUTE)! - 30) < 0.01)
  assert.equal(distanceToRouteMeters(41.8601, mid, null), null)
  assert.equal(distanceToRouteMeters(41.8601, mid, { type: 'Point', coordinates: [mid, 41.8601] }), null)
})

test('hotspotPlacement: within the buffer is ok, beyond is off-route, no geometry is reported', () => {
  const mid = -87.7192
  assert.equal(hotspotPlacement(north(HOTSPOT_ROUTE_BUFFER_METERS - 1), mid, ROUTE), 'ok')
  assert.equal(hotspotPlacement(north(HOTSPOT_ROUTE_BUFFER_METERS + 1), mid, ROUTE), 'off-route')
  assert.equal(hotspotPlacement(north(2000), mid, ROUTE), 'off-route')
  assert.equal(hotspotPlacement(41.8601, mid, null), 'no-geometry')
  // Polygons (block boundaries) are walked as rings.
  const poly = { type: 'Polygon', coordinates: [[[-87.72, 41.86], [-87.719, 41.86], [-87.719, 41.861], [-87.72, 41.86]]] }
  assert.equal(hotspotPlacement(41.86, -87.7195, poly), 'ok')
})

test('isScanPhotoUrl: the caller’s or the scan worker’s own upload on our blob store', () => {
  // Our store (abc…, from the test token), bound by the spots/{id}/ path.
  const base = 'https://abc.public.blob.vercel-storage.com'
  assert.equal(isScanPhotoUrl(`${base}/spots/7/a.jpg`, { workerId: 7 }, 7), true)
  assert.equal(isScanPhotoUrl(`${base}/spots/3/a.jpg`, { workerId: 7 }, 3), true, 'admin performing the scan')
  assert.equal(isScanPhotoUrl(`${base}/spots/9/a.jpg`, { workerId: 7 }, 7), false, 'another worker’s upload')
  assert.equal(isScanPhotoUrl('https://evil.example.com/spots/7/a.jpg', { workerId: 7 }, 7), false)
})

const hs = (over: Partial<HotspotForPoints> = {}): HotspotForPoints => ({
  status: 'cleaned',
  estimatedVolumeLiters: 10,
  beforePhotoUrl: 'https://x/spots/7/b.jpg',
  afterPhotoUrl: 'https://x/spots/7/a.jpg',
  ...over,
})

test('a cleaned hotspot pays only with a distinct after photo', () => {
  assert.equal(hotspotCountsAsCleaned(hs()), true)
  assert.equal(hotspotCountsAsCleaned(hs({ afterPhotoUrl: null })), false)
  assert.equal(hotspotCountsAsCleaned(hs({ afterPhotoUrl: 'https://x/spots/7/b.jpg' })), false, 'before reused as after')
  assert.equal(hotspotCountsAsCleaned(hs({ status: 'skipped' })), false)
})

test('scanCompletionPoints: base + per hotspot + litres, from capped volumes', () => {
  assert.equal(scanCompletionPoints([]), 50)
  assert.equal(scanCompletionPoints([hs(), hs({ estimatedVolumeLiters: 5.9 })]), 50 + 20 + 15)
  // A legacy row stored the client's 1e6 litres: the cap is re-applied.
  assert.equal(scanCompletionPoints([hs({ estimatedVolumeLiters: 1e6 })]), 50 + 10 + MAX_HOTSPOT_VOLUME_LITERS)
  // Not cleaned / no after photo → contributes nothing, volume included.
  assert.equal(scanCompletionPoints([hs({ status: 'detected', estimatedVolumeLiters: 100 }), hs({ afterPhotoUrl: null })]), 50)
})

test('verificationScoreUpdate: null is NULL (not analysed), never 0', () => {
  assert.deepEqual(verificationScoreUpdate({ verificationScore: null }, true), { ok: true, value: null })
  assert.deepEqual(verificationScoreUpdate({ verificationScore: null }, false), { ok: true, value: null })
})

test('verificationScoreUpdate: missing key is NULL on a cleaned claim, else unchanged', () => {
  assert.deepEqual(verificationScoreUpdate({ status: 'cleaned' }, true), { ok: true, value: null })
  assert.deepEqual(verificationScoreUpdate({ status: 'skipped' }, false), { ok: true, value: undefined })
  assert.deepEqual(verificationScoreUpdate(null, false), { ok: true, value: undefined })
})

test('verificationScoreUpdate: finite numbers are kept, clamped to [0, 1]', () => {
  assert.deepEqual(verificationScoreUpdate({ verificationScore: 0.7 }, true), { ok: true, value: 0.7 })
  assert.deepEqual(verificationScoreUpdate({ verificationScore: 0 }, true), { ok: true, value: 0 })
  assert.deepEqual(verificationScoreUpdate({ verificationScore: 3 }, true), { ok: true, value: 1 })
})

test('verificationScoreUpdate: non-numbers and non-finite values are rejected (400)', () => {
  for (const bad of ['abc', '0.7', NaN, Infinity, -Infinity, {}, [0.5], true]) {
    assert.equal(verificationScoreUpdate({ verificationScore: bad }, true).ok, false, String(bad))
  }
})
