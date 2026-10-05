/**
 * Generate block proposals for a Chicago community area from OpenStreetMap.
 *
 *   npx tsx scripts/generate-block-proposals.ts --area 29
 *   npx tsx scripts/generate-block-proposals.ts --area 29 --load
 *
 * Without --load it only writes two files under ../data/block-proposals/:
 *   <slug>.json     rows in the shape of the `block_proposals` table
 *   <slug>.geojson  the same blocks, to look at on a map (geojson.io, QGIS)
 *
 * With --load it also inserts the rows into `block_proposals` as `pending`
 * (needs DATABASE_URL in .env.local or .env). That is the admin-only staging
 * table reviewed at /admin/block-proposals: loading proposals creates no
 * block, and opens nothing to workers or sponsors. Re-running is safe — a
 * code already present in the table is skipped.
 *
 * How blocks are found, and the size limits: src/lib/block-proposals/generate.ts.
 * --area takes a community-area number (29 = North Lawndale); the same
 * command works for any of the 77.
 */
import { mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { COMMUNITY_AREAS } from '../src/lib/chicago-geo-data'
import { NORTH_LAWNDALE_BLOCKS } from '../src/data/blocks'
import { areaCodePrefix, generateBlockProposals, type OsmWay } from '../src/lib/block-proposals/generate'

const OVERPASS_URL = process.env.OVERPASS_URL || 'https://overpass-api.de/api/interpreter'
const HERE = dirname(fileURLToPath(import.meta.url))
const OUT_DIR = join(HERE, '..', '..', 'data', 'block-proposals')

function arg(name: string): string | undefined {
  const i = process.argv.indexOf(`--${name}`)
  return i >= 0 ? process.argv[i + 1] : undefined
}
const flag = (name: string) => process.argv.includes(`--${name}`)

async function fetchStreets(bounds: { north: number; south: number; east: number; west: number }): Promise<OsmWay[]> {
  // A margin, so blocks on the edge of the area still have all four streets.
  const m = 0.003
  const bbox = `${bounds.south - m},${bounds.west - m},${bounds.north + m},${bounds.east + m}`
  const query = `[out:json][timeout:180];way["highway"](${bbox});out geom;`
  const res = await fetch(OVERPASS_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      'User-Agent': 'gcc-block-proposals/1.0 (globalcommunitycleaners.org)',
    },
    body: `data=${encodeURIComponent(query)}`,
  })
  if (!res.ok) throw new Error(`Overpass answered ${res.status}`)
  const body = (await res.json()) as { elements: OsmWay[] }
  return body.elements.filter((e) => Array.isArray(e.geometry) && e.geometry.length > 1)
}

async function main() {
  const areaNumber = Number(arg('area'))
  const area = COMMUNITY_AREAS.find((a) => a.number === areaNumber)
  if (!area) {
    console.error('Usage: generate-block-proposals.ts --area <community area number, e.g. 29> [--load] [--osm file.json]')
    process.exit(2)
  }

  // --osm reuses a saved Overpass response instead of fetching.
  const osmFile = arg('osm')
  const ways: OsmWay[] =
    osmFile && existsSync(osmFile)
      ? (JSON.parse(readFileSync(osmFile, 'utf8')) as OsmWay[])
      : await fetchStreets(area.bounds)
  if (osmFile && !existsSync(osmFile)) writeFileSync(osmFile, JSON.stringify(ways))

  // chicago-geo-data stores [lat, lng]; the generator wants a closed [lng, lat] ring.
  const areaRing = area.polygon.map(([lat, lng]) => [lng, lat] as [number, number])
  const first = areaRing[0]
  const last = areaRing[areaRing.length - 1]
  if (first[0] !== last[0] || first[1] !== last[1]) areaRing.push(first)

  // The hand-drawn pilot blocks already exist; do not propose them again.
  const existingRings =
    areaNumber === 29
      ? NORTH_LAWNDALE_BLOCKS.map((b) => b.geo.polygon.coordinates[0] as Array<[number, number]>)
      : []

  const slug = area.name.replace(/_/g, '-')
  const batch = `${slug}-${new Date().toISOString().slice(0, 10)}`
  const { proposals, skipped } = generateBlockProposals({
    ways,
    areaRing,
    codePrefix: areaCodePrefix(areaNumber),
    neighborhood: area.displayName,
    existingRings,
  })

  const rows = proposals.map((p) => ({
    codeHint: p.codeHint,
    name: p.name,
    locationNeighborhood: p.locationNeighborhood,
    locationBoundary: p.locationBoundary,
    locationCenter: p.locationCenter,
    streetName: p.streetName,
    fromCorner: p.fromCorner,
    toCorner: p.toCorner,
    source: 'osm_street_faces',
    generationBatch: batch,
    sourceRefs: { ...p.sourceRefs, areaSqM: p.areaSqM },
  }))

  mkdirSync(OUT_DIR, { recursive: true })
  writeFileSync(join(OUT_DIR, `${slug}.json`), JSON.stringify(rows, null, 1))
  writeFileSync(
    join(OUT_DIR, `${slug}.geojson`),
    JSON.stringify({
      type: 'FeatureCollection',
      features: proposals.map((p) => ({
        type: 'Feature',
        properties: { code: p.codeHint, name: p.name, areaSqM: p.areaSqM },
        geometry: p.locationBoundary,
      })),
    }),
  )

  const areas = proposals.map((p) => p.areaSqM).sort((a, b) => a - b)
  console.log(`${area.displayName} (community area ${areaNumber})`)
  console.log(`  streets read from OpenStreetMap: ${ways.length} ways`)
  console.log(`  blocks proposed: ${proposals.length}`)
  console.log(`  median block: ${areas[Math.floor(areas.length / 2)] ?? 0} m2`)
  console.log(`  left out — outside the area: ${skipped.outsideArea}, too small or thin: ${skipped.tooSmallOrThin}, boulevard median: ${skipped.median}, too large: ${skipped.tooLarge}, already a pilot block: ${skipped.alreadyExists}`)
  console.log(`  wrote ${join(OUT_DIR, slug)}.json and .geojson`)

  if (flag('load')) {
    const dotenv = await import('dotenv')
    dotenv.config({ path: '.env.local' })
    dotenv.config({ path: '.env' })
    const { db } = await import('../src/db')
    const { blockProposals } = await import('../src/db/schema')
    const existing = new Set(
      (await db.select({ codeHint: blockProposals.codeHint }).from(blockProposals)).map((r) => r.codeHint),
    )
    const fresh = rows.filter((r) => !existing.has(r.codeHint))
    for (let i = 0; i < fresh.length; i += 100) {
      await db.insert(blockProposals).values(fresh.slice(i, i + 100))
    }
    console.log(`  loaded ${fresh.length} pending proposals (${rows.length - fresh.length} already there)`)
  }
}

main().catch((err) => {
  console.error(err)
  process.exit(1)
})
