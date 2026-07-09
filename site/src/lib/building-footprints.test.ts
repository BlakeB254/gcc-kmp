import { test } from 'node:test'
import assert from 'node:assert/strict'
import { tilesForBbox, parseOverpassElements, parseBboxString } from './building-footprints'

// Pure helpers only — no DB / no Overpass. Run with:
//   DATABASE_URL=postgres://test:test@localhost/test npx tsx --test src/lib/building-footprints.test.ts

test('tilesForBbox: one tile for a sub-tile bbox, stable key', () => {
  const tiles = tilesForBbox(-87.715, 41.862, -87.714, 41.863)
  assert.equal(tiles.length, 1)
  assert.equal(tiles[0].key, 'b:4186:-8772')
})

test('tilesForBbox: covers all tiles a multi-tile bbox spans', () => {
  assert.ok(tilesForBbox(-87.72, 41.86, -87.70, 41.87).length > 1)
})

test('parseBboxString: valid + invalid', () => {
  assert.deepEqual(parseBboxString('-87.72,41.86,-87.71,41.866'), {
    minLng: -87.72, minLat: 41.86, maxLng: -87.71, maxLat: 41.866,
  })
  assert.equal(parseBboxString(null), null)
  assert.equal(parseBboxString('1,2,3'), null)
  assert.equal(parseBboxString('a,b,c,d'), null)
})

test('parseOverpassElements: builds a [lng,lat] ring + bbox from a way', () => {
  const p = parseOverpassElements({
    elements: [
      {
        type: 'way', id: 42, tags: { building: 'house' },
        geometry: [
          { lat: 41.86, lon: -87.72 }, { lat: 41.86, lon: -87.71 },
          { lat: 41.866, lon: -87.71 }, { lat: 41.86, lon: -87.72 },
        ],
      },
    ],
  })
  assert.equal(p.length, 1)
  assert.equal(p[0].osmId, 42)
  assert.equal(p[0].buildingType, 'house')
  assert.deepEqual(p[0].polygon[0], [-87.72, 41.86]) // [lng,lat]
  assert.ok(Math.abs(p[0].minLng - -87.72) < 1e-9)
  assert.ok(Math.abs(p[0].maxLat - 41.866) < 1e-9)
})

test('parseOverpassElements: skips ways with fewer than 3 geometry points', () => {
  assert.equal(
    parseOverpassElements({ elements: [{ type: 'way', id: 1, geometry: [{ lat: 1, lon: 1 }, { lat: 2, lon: 2 }] }] }).length,
    0,
  )
})
