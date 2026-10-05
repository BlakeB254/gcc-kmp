import { test } from 'node:test'
import assert from 'node:assert/strict'
import { areaCodePrefix, generateBlockProposals, type OsmWay } from './generate'

// City blocks from a street network. Run with:
//   npx tsx --test src/lib/block-proposals/generate.test.ts
// Pure geometry — no network, no database.

// A 3x3 street grid near North Lawndale: avenues ~110 m apart, streets ~200 m
// apart, which is the size of the hand-drawn pilot blocks.
const LNGS = [-87.7260, -87.7247, -87.7234]
const LATS = [41.8600, 41.8582, 41.8564]
const AVENUES = ['S Pulaski Rd', 'S Harding Ave', 'S Springfield Ave']
const STREETS = ['W 15th St', 'W 16th St', 'W 17th St']

let nextNode = 1
const nodeId = new Map<string, number>()
function node(lng: number, lat: number): number {
  const key = `${lng},${lat}`
  if (!nodeId.has(key)) nodeId.set(key, nextNode++)
  return nodeId.get(key)!
}

function way(id: number, name: string, pts: Array<[number, number]>, highway = 'residential'): OsmWay {
  return {
    id,
    tags: { highway, name },
    nodes: pts.map(([lng, lat]) => node(lng, lat)),
    geometry: pts.map(([lng, lat]) => ({ lon: lng, lat })),
  }
}

function grid(): OsmWay[] {
  const ways: OsmWay[] = []
  let id = 100
  // avenues run north-south, streets east-west; every crossing is a shared node
  LNGS.forEach((lng, i) => ways.push(way(id++, AVENUES[i], LATS.map((lat) => [lng, lat]))))
  LATS.forEach((lat, i) => ways.push(way(id++, STREETS[i], LNGS.map((lng) => [lng, lat]))))
  return ways
}

const AREA: Array<[number, number]> = [
  [-87.73, 41.87],
  [-87.72, 41.87],
  [-87.72, 41.85],
  [-87.73, 41.85],
  [-87.73, 41.87],
]

test('a 3x3 grid of streets makes four city blocks', () => {
  const { proposals } = generateBlockProposals({ ways: grid(), areaRing: AREA, codePrefix: 'NL', neighborhood: 'North Lawndale' })
  assert.equal(proposals.length, 4)
})

test('each block is named for the four streets around it, and ordered north-west first', () => {
  const { proposals } = generateBlockProposals({ ways: grid(), areaRing: AREA, codePrefix: 'NL', neighborhood: 'North Lawndale' })
  const first = proposals[0]
  assert.equal(first.codeHint, 'NL-B001')
  assert.equal(first.fromCorner, 'S Pulaski Rd')
  assert.equal(first.toCorner, 'S Harding Ave')
  assert.equal(first.streetName, 'W 15th St to W 16th St')
  assert.equal(first.name, 'S Pulaski Rd to S Harding Ave, W 15th St to W 16th St')
  assert.equal(first.locationNeighborhood, 'North Lawndale')
  // reading order: the block east of it comes next, then the row to the south
  assert.equal(proposals[1].fromCorner, 'S Harding Ave')
  assert.equal(proposals[2].streetName, 'W 16th St to W 17th St')
  assert.deepEqual(proposals.map((p) => p.codeHint), ['NL-B001', 'NL-B002', 'NL-B003', 'NL-B004'])
})

test('a block is a closed GeoJSON polygon with a centre inside the street grid', () => {
  const { proposals } = generateBlockProposals({ ways: grid(), areaRing: AREA, codePrefix: 'NL', neighborhood: 'North Lawndale' })
  const p = proposals[0]
  const ring = p.locationBoundary.coordinates[0]
  assert.equal(p.locationBoundary.type, 'Polygon')
  assert.deepEqual(ring[0], ring[ring.length - 1])
  const [lng, lat] = p.locationCenter.coordinates
  assert.ok(lng > LNGS[0] && lng < LNGS[1] && lat < LATS[0] && lat > LATS[1])
  // ~110 m x ~200 m
  assert.ok(p.areaSqM > 15000 && p.areaSqM < 30000, `area ${p.areaSqM}`)
})

test('an alley through a block does not split it — alleys are part of the block', () => {
  const ways = grid()
  const midLng = (LNGS[0] + LNGS[1]) / 2
  ways.push(way(900, 'Alley', [[midLng, LATS[0]], [midLng, LATS[1]]], 'service'))
  const { proposals } = generateBlockProposals({ ways, areaRing: AREA, codePrefix: 'NL', neighborhood: 'North Lawndale' })
  assert.equal(proposals.length, 4)
})

test('the sliver between the two sides of a boulevard is not a block', () => {
  // A second carriageway 12 m east of the first avenue. The cross streets get
  // a node where they meet it, as they do in OpenStreetMap.
  const twin = LNGS[0] + 0.000145
  const ways: OsmWay[] = []
  let id = 500
  LNGS.forEach((lng, i) => ways.push(way(id++, AVENUES[i], LATS.map((lat) => [lng, lat]))))
  ways.push(way(id++, 'S Pulaski Rd', LATS.map((lat) => [twin, lat])))
  LATS.forEach((lat, i) =>
    ways.push(way(id++, STREETS[i], [LNGS[0], twin, LNGS[1], LNGS[2]].map((lng) => [lng, lat]))),
  )
  const { proposals, skipped } = generateBlockProposals({ ways, areaRing: AREA, codePrefix: 'NL', neighborhood: 'North Lawndale' })
  assert.equal(skipped.tooSmallOrThin, 2) // one sliver per row of blocks
  assert.equal(proposals.length, 4)
  assert.ok(proposals.every((p) => p.areaSqM > 5000))
})

test('a boulevard median 30 m wide is not a block, however long it is', () => {
  // Two carriageways 30 m apart: the strip between them is parkway, and at
  // 30 m x 200 m it is too compact to be caught as a sliver.
  const twin = LNGS[0] + 0.00036
  const ways: OsmWay[] = []
  let id = 700
  LNGS.forEach((lng, i) => ways.push(way(id++, AVENUES[i], LATS.map((lat) => [lng, lat]))))
  ways.push(way(id++, 'S Pulaski Rd', LATS.map((lat) => [twin, lat])))
  LATS.forEach((lat, i) =>
    ways.push(way(id++, STREETS[i], [LNGS[0], twin, LNGS[1], LNGS[2]].map((lng) => [lng, lat]))),
  )
  const { proposals, skipped } = generateBlockProposals({ ways, areaRing: AREA, codePrefix: 'NL', neighborhood: 'North Lawndale' })
  assert.equal(skipped.tooSmallOrThin, 2)
  assert.equal(proposals.length, 4)
})

test('land with the same street on both sides is a median, whatever its width', () => {
  // Carriageways 60 m apart — wider than some real blocks — but both are the
  // same boulevard, so the land between them is its median.
  const twin = LNGS[0] + 0.00072
  const ways: OsmWay[] = []
  let id = 800
  LNGS.forEach((lng, i) => ways.push(way(id++, AVENUES[i], LATS.map((lat) => [lng, lat]))))
  ways.push(way(id++, 'S Pulaski Rd', LATS.map((lat) => [twin, lat])))
  LATS.forEach((lat, i) =>
    ways.push(way(id++, STREETS[i], [LNGS[0], twin, LNGS[1], LNGS[2]].map((lng) => [lng, lat]))),
  )
  const { proposals, skipped } = generateBlockProposals({ ways, areaRing: AREA, codePrefix: 'NL', neighborhood: 'North Lawndale' })
  assert.equal(skipped.median, 2)
  assert.ok(proposals.every((p) => p.fromCorner !== p.toCorner))
})

test('blocks outside the community area are left out', () => {
  const smallArea: Array<[number, number]> = [
    [-87.7262, 41.8602],
    [-87.7246, 41.8602],
    [-87.7246, 41.8580],
    [-87.7262, 41.8580],
    [-87.7262, 41.8602],
  ]
  const { proposals, skipped } = generateBlockProposals({ ways: grid(), areaRing: smallArea, codePrefix: 'NL', neighborhood: 'North Lawndale' })
  assert.equal(proposals.length, 1)
  assert.equal(skipped.outsideArea, 3)
})

test('a block that already exists is not proposed again', () => {
  const existing: Array<[number, number]> = [
    [LNGS[0], LATS[0]],
    [LNGS[1], LATS[0]],
    [LNGS[1], LATS[1]],
    [LNGS[0], LATS[1]],
    [LNGS[0], LATS[0]],
  ]
  const { proposals, skipped } = generateBlockProposals({
    ways: grid(),
    areaRing: AREA,
    codePrefix: 'NL',
    neighborhood: 'North Lawndale',
    existingRings: [existing],
  })
  assert.equal(proposals.length, 3)
  assert.equal(skipped.alreadyExists, 1)
})

test('areaCodePrefix: North Lawndale keeps NL; every other area is unique by number', () => {
  assert.equal(areaCodePrefix(29), 'NL')
  assert.equal(areaCodePrefix(22), 'C22') // Logan Square
  assert.equal(areaCodePrefix(4), 'C04') // Lincoln Square — both would be "LS" by initials
  assert.notEqual(areaCodePrefix(22), areaCodePrefix(4))
})
