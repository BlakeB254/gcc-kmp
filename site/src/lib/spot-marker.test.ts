import { test } from 'node:test'
import assert from 'node:assert/strict'
import { toSpotMarker } from './spot-marker'

const base = {
  id: 7,
  coordinates: [-87.7, 41.85],
  affectedRadiusMeters: '15',
  title: null,
  type: 'sighting',
  affectedArea: null,
  nextVerificationDue: null,
  mediaIds: null,
}

// The bug this module exists to prevent: GET /api/spots served map markers
// with no media field at all, so NeighborhoodMapSection's photo block
// (`selectedSpot.photoUrl || selectedSpot.mediaUrls?.length`) never rendered
// — even though the same response carried the URLs in its `data` array.
test('carries the photos onto the marker', () => {
  const m = toSpotMarker({ ...base, mediaIds: ['https://blob/a.jpg', 'https://blob/b.jpg'] }, [])
  assert.deepEqual(m.mediaUrls, ['https://blob/a.jpg', 'https://blob/b.jpg'])
  assert.equal(m.photoUrl, 'https://blob/a.jpg', 'photoUrl is the first photo')
})

test('a spot with no photos is explicit, not undefined', () => {
  const m = toSpotMarker(base, [])
  assert.deepEqual(m.mediaUrls, [], 'empty array, so .length checks are safe')
  assert.equal(m.photoUrl, null)
})

test('normalises array coordinates to [lng, lat]', () => {
  const m = toSpotMarker({ ...base, coordinates: [-87.7, 41.85] }, [])
  assert.deepEqual(m.coordinates, [-87.7, 41.85])
})

test('normalises object coordinates to [lng, lat]', () => {
  const m = toSpotMarker({ ...base, coordinates: { lat: 41.85, lng: -87.7 } }, [])
  assert.deepEqual(m.coordinates, [-87.7, 41.85])
})

test('tolerates the legacy lon spelling', () => {
  const m = toSpotMarker({ ...base, coordinates: { lat: 41.85, lon: -87.7 } }, [])
  assert.deepEqual(m.coordinates, [-87.7, 41.85])
})

test('missing coordinates fall back to [0,0] rather than throwing', () => {
  const m = toSpotMarker({ ...base, coordinates: null }, [])
  assert.deepEqual(m.coordinates, [0, 0])
})

test('labels a sighting and a cleanup differently', () => {
  assert.equal(toSpotMarker({ ...base, type: 'sighting' }, []).label, 'Sighting')
  assert.equal(toSpotMarker({ ...base, type: 'volunteer_cleanup' }, []).label, 'Volunteer Cleanup')
  assert.equal(toSpotMarker({ ...base, title: 'Alley pile' }, []).label, 'Alley pile')
})

test('needsVerification is true when nothing is scheduled', () => {
  assert.equal(toSpotMarker(base, []).needsVerification, true)
})

test('needsVerification is false when the next check is in the future', () => {
  const future = new Date(Date.now() + 86_400_000).toISOString()
  assert.equal(toSpotMarker({ ...base, nextVerificationDue: future }, []).needsVerification, false)
})
