/**
 * Curation run: regenerate exclusion zones for the 10 blocks with the
 * improved generator (corridor clip + overlap union + sliver filter).
 * Reads rings from block-rings.jsonl, writes results to curated-zones.jsonl.
 * Sequential with a 3s pause between Overpass calls (usage policy).
 */
import { readFileSync, writeFileSync, appendFileSync } from 'node:fs'
import { generateInnerBoundaryFromRing } from '../src/lib/inner-boundary-generator'

const dir = '/Users/codexmetatron/.claude/jobs/011cf90e/tmp'
const rings = readFileSync(`${dir}/block-rings.jsonl`, 'utf8')
  .trim().split('\n').map((l) => JSON.parse(l) as { id: number; code: string; ring: number[][] })

const out = `${dir}/curated-zones.jsonl`
writeFileSync(out, '')

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

async function main() {
  for (const b of rings) {
    try {
      const result = await generateInnerBoundaryFromRing(b.ring)
      appendFileSync(out, JSON.stringify({ id: b.id, code: b.code, multiPolygon: result.multiPolygon }) + '\n')
      console.log(
        `${b.code}: raw=${result.stats.rawIslands} → merged=${result.stats.islands} islands ` +
        `(buildings=${result.stats.buildingSegments} segs, samples=${result.stats.samples})`
      )
    } catch (e) {
      console.log(`${b.code}: FAILED — ${(e as Error).message}`)
    }
    await sleep(3000)
  }
}
main()
