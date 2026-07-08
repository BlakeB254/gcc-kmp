import { test } from 'node:test'
import assert from 'node:assert/strict'
import { tilesForBbox, parseOverpassElements } from './route'

// Pure helpers only — no DB access (same pattern as ../coverage/route.test.ts).
// Run with:
//   DATABASE_URL=postgres://test:test@localhost/test npx tsx --test src/app/api/map/buildings/route.test.ts

test('tilesForBbox: one tile for a sub-tile bbox', () => {
  const tiles = tilesForBbox(-87.715, 41.862, -87.714, 41.863)
  assert.equal(tiles.length, 1)
  assert.equal(tiles[0].key, 'b:4186:-8772')
})

test('tilesForBbox: covers all tiles a multi-tile bbox spans', () => {
  assert.ok(tilesForBbox(-87.72, 41.86, -87.70, 41.87).length > 1)
})

test('parseOverpassElements: builds a [lng,lat] ring and bbox from a way geometry', () => {
  const parsed = parseOverpassElements({
    elements: [
      {
        type: 'way',
        id: 42,
        tags: { building: 'house' },
        geometry: [
          { lat: 41.86, lon: -87.72 },
          { lat: 41.86, lon: -87.71 },
          { lat: 41.866, lon: -87.71 },
          { lat: 41.86, lon: -87.72 },
        ],
      },
    ],
  })
  assert.equal(parsed.length, 1)
  assert.equal(parsed[0].osmId, 42)
  assert.equal(parsed[0].buildingType, 'house')
  assert.deepEqual(parsed[0].polygon[0], [-87.72, 41.86]) // [lng,lat]
  assert.ok(Math.abs(parsed[0].minLng - -87.72) < 1e-9)
  assert.ok(Math.abs(parsed[0].maxLat - 41.866) < 1e-9)
})

test('parseOverpassElements: skips ways with fewer than 3 geometry points', () => {
  const parsed = parseOverpassElements({
    elements: [{ type: 'way', id: 1, geometry: [{ lat: 1, lon: 1 }, { lat: 2, lon: 2 }] }],
  })
  assert.equal(parsed.length, 0)
})
