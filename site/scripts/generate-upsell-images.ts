/**
 * Developer script to generate the 40 upsell card images.
 *
 * Run:   npx tsx scripts/generate-upsell-images.ts [card-slug]
 * With no arg, generates all 10 cards. With a card slug, generates only that card's 4 images.
 *
 * Requires the nanobanana MCP server to be callable (or GEMINI_API_KEY set for the REST fallback).
 * Output:  site/public/images/upsells/<card>-base.jpg
 *          site/public/images/upsells/<card>-monday-coffee.jpg
 *          site/public/images/upsells/<card>-del-kar.jpg
 *          site/public/images/upsells/<card>-barneys.jpg
 *
 * This script is intentionally an outline — the actual image-generation calls
 * are driven interactively during plan execution by the agent using the
 * nanobanana MCP tools directly. The script exists to record prompts, validate
 * inputs, and verify that all 40 files exist at the end.
 */

import * as fs from 'node:fs'
import * as path from 'node:path'
import {
  CARD_PROMPTS,
  SPONSORS,
  SPONSOR_ORDER,
  type CardSlug,
  type SponsorSlug,
} from '../src/components/sponsor/calculator/variationData'

const OUTPUT_DIR = path.resolve(__dirname, '../public/images/upsells')
const CARDS_TO_PROCESS: CardSlug[] = (() => {
  const arg = process.argv[2]
  if (!arg) return Object.keys(CARD_PROMPTS) as CardSlug[]
  if (!(arg in CARD_PROMPTS)) {
    console.error(`Unknown card slug: ${arg}`)
    console.error(`Valid slugs: ${Object.keys(CARD_PROMPTS).join(', ')}`)
    process.exit(1)
  }
  return [arg as CardSlug]
})()

function baseImagePath(slug: CardSlug): string {
  return path.join(OUTPUT_DIR, `${slug}-base.jpg`)
}

function variationImagePath(slug: CardSlug, sponsor: SponsorSlug): string {
  return path.join(OUTPUT_DIR, `${slug}-${sponsor}.jpg`)
}

function verify(): { missing: string[]; found: string[] } {
  const missing: string[] = []
  const found: string[] = []
  for (const slug of CARDS_TO_PROCESS) {
    const paths = [
      baseImagePath(slug),
      ...SPONSOR_ORDER.map((s) => variationImagePath(slug, s)),
    ]
    for (const p of paths) {
      if (fs.existsSync(p)) found.push(p)
      else missing.push(p)
    }
  }
  return { missing, found }
}

function printPromptsFor(slug: CardSlug): void {
  const card = CARD_PROMPTS[slug]
  console.log(`\n=== ${slug} ===`)
  console.log(`Shot hint: ${card.shotHint}`)
  console.log(`\nBASE PROMPT (generate → ${baseImagePath(slug)}):`)
  console.log(card.basePrompt)
  console.log(`\nEDIT SURFACE: ${card.surface}`)
  for (const sponsorSlug of SPONSOR_ORDER) {
    const sponsor = SPONSORS[sponsorSlug]
    console.log(`\nVARIATION ${sponsor.name} (edit from base → ${variationImagePath(slug, sponsorSlug)}):`)
    console.log(`  Sponsor logo PNG: ${sponsor.logoPath}`)
    console.log(`  Campaign: "${sponsor.campaign}"`)
    console.log(`  Full instruction: ${card.surface.replace(/\[sponsor name\]/g, sponsor.name).replace(/\[campaign slogan\]/g, sponsor.campaign)}`)
  }
}

async function main() {
  fs.mkdirSync(OUTPUT_DIR, { recursive: true })
  for (const slug of CARDS_TO_PROCESS) {
    printPromptsFor(slug)
  }
  const { missing, found } = verify()
  console.log(`\n--- Verification ---`)
  console.log(`Found: ${found.length}/${CARDS_TO_PROCESS.length * 4}`)
  if (missing.length > 0) {
    console.log(`Missing: ${missing.length}`)
    missing.forEach((p) => console.log(`  ${p}`))
  } else {
    console.log(`All images present.`)
  }
}

main().catch((err) => {
  console.error(err)
  process.exit(1)
})
