# Sponsor Upsell Co-Branded Variations — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the 10 hand-drawn SVG illustrations on `/sponsor/calculator` with editorial photography that wipes from a GCC-only base state to three rotating co-branded variations (Monday Coffee, Del Kar Pharmacy, Barney's New Life).

**Architecture:** One new React component (`VariationShowcase`) that stacks two images, applies a clip-path wipe, cycles the post-wipe overlay through three sponsor variations, and exposes clickable chips + a draggable wipe handle. Forty JPEG assets live in `site/public/images/upsells/`. Ten callsites in four sections swap `CategoryShowcase` → `VariationShowcase` with a per-card data map. A disclaimer caption above the upsell stack makes clear the sponsors are mockups.

**Tech Stack:** Next.js 15 App Router (existing), TypeScript, Tailwind, lucide-react icons, Playwright for E2E. Image generation via nanobanana MCP (Gemini 2.5/3 Pro Image) with ComfyUI FLUX.2 Klein as fallback.

**Reference spec:** `docs/superpowers/specs/2026-04-24-sponsor-upsell-cobranded-variations-design.md` (see spec "Revision 1" section for the info-panel + 4th-sponsor deltas applied mid-execution)

**Revision 1 impact on this plan:**
- Task 1: adds `bpp-cubs.png` to the mockup-sponsor logo set (done inline pre-subagent)
- Task 3: `Sponsor` interface expands with `tagline`, `address`, `websiteUrl`, `instagramHandle?`, `brandColors`; `SPONSOR_ORDER` becomes 4-long; `GCC_BASELINE` constant is added
- Task 6: regenerates 40 variations (not 30) — one extra sponsor per card
- Task 7: `VariationShowcase` gains a `<SponsorInfoPanel>` region that renders the active sponsor's business data and brand colors; swaps in lockstep with the image wipe
- Tasks 9–11: callsite prop shape unchanged (`VariationShowcase` keeps the same API; info panel reads data from `variationData`)

**Branch guidance:** Run on `main` (current branch) or a dedicated worktree. No migrations or DB changes. The 10 existing SVGs stay in place as backup — only `imageSrc` values change in the codebase.

---

## Phase 1: Sponsor logo preparation

### Task 1: Create mockup-sponsors directory and save reference logos

**Files:**
- Create: `site/public/images/mockup-sponsors/README.md`
- Create: `site/public/images/mockup-sponsors/barneys-new-life.png`
- Create: `site/public/images/mockup-sponsors/monday-coffee.png`

- [ ] **Step 1: Create directory and README**

```bash
mkdir -p /home/codex450/cdx/dev-projects/gcc/site/public/images/mockup-sponsors/
```

Write `site/public/images/mockup-sponsors/README.md`:

```markdown
# Mockup Sponsor Logos

These are FICTIONAL sponsor marks used in the `/sponsor/calculator` upsell card mockups. They are not real sponsors of Global Community Cleaners. Any resemblance to real brands is unintentional.

- `monday-coffee.png` — "MC" red circular monogram (Monday Coffee, local coffee shop archetype)
- `del-kar-pharmacy.png` — navy serif wordmark + green pharmacy cross (Del Kar Pharmacy, neighborhood pharmacy archetype)
- `barneys-new-life.png` — brown heart-with-leaves symbol (Barney's New Life, mission-driven nonprofit archetype)

Related spec: `docs/superpowers/specs/2026-04-24-sponsor-upsell-cobranded-variations-design.md`
```

- [ ] **Step 2: Convert the provided Barney's New Life avif to PNG**

```bash
cd /home/codex450/cdx/dev-projects/gcc/site/public/images/mockup-sponsors/ && \
  ffmpeg -y -i "/home/codex450/Downloads/nl logo_edited.avif" barneys-new-life.png
```

Expected: `barneys-new-life.png` exists, ~13KB, square-ish canvas with the brown heart + green leaves mark.

- [ ] **Step 3: Generate a clean Monday Coffee MC monogram via nanobanana**

The user provided an inline image; we need a file. Regenerate it from the visual description at higher resolution.

Using the `mcp__nanobanana__generate_image` tool:

```json
{
  "prompt": "Coffee shop logo for 'Monday Coffee'. A bold geometric monogram of the letters 'M' and 'C' fused into a single glyph inside a solid-fill red circle. The M and C are cut out of the circle in white (negative space). Color: bright tomato red (#E24A42). Style: minimalist, hard-edged, high contrast. Flat design, no gradients, no shadows. Transparent background. Square 1024x1024.",
  "output_path": "/home/codex450/cdx/dev-projects/gcc/site/public/images/mockup-sponsors/monday-coffee.png"
}
```

Expected: A clean red circular MC monogram on transparent background, matching the reference style. If the output does not closely match the reference, iterate on the prompt (describe stroke weight of the letters, exact circle proportions) up to 3 times, then fall back to manually saving the reference image at its original resolution.

- [ ] **Step 4: Verify file sizes and open each to visually inspect**

```bash
ls -la /home/codex450/cdx/dev-projects/gcc/site/public/images/mockup-sponsors/
file /home/codex450/cdx/dev-projects/gcc/site/public/images/mockup-sponsors/*.png
```

Expected: Both PNGs present, each reports "PNG image data" with dimensions ≥ 1024×1024 (or whatever native resolution the source had for Barney's).

- [ ] **Step 5: Commit**

```bash
git add site/public/images/mockup-sponsors/
git commit -m "feat(sponsor-calculator): add mockup sponsor logos for upsell cards

Monday Coffee and Barney's New Life marks for the co-branded
variation mockups on /sponsor/calculator. Del Kar Pharmacy
logo added in next task.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Design Del Kar Pharmacy wordmark logo

**Files:**
- Create: `site/public/images/mockup-sponsors/del-kar-pharmacy.png`

- [ ] **Step 1: Generate Del Kar Pharmacy logo via nanobanana**

Using `mcp__nanobanana__generate_image`:

```json
{
  "prompt": "A simple, professional pharmacy logo for 'Del Kar Pharmacy'. A bold navy blue serif wordmark reading 'Del Kar' in a classic typeface (similar to Bodoni or Didot). To the left of the wordmark, a small forest-green medical plus sign (pharmacy cross). The 'Pharmacy' label sits below 'Del Kar' in smaller all-caps sans-serif letterspaced text, also in navy. Transparent background. Square 1024x1024 canvas. No other decorative elements, no frames, no illustrations. Warm and trustworthy, neighborhood independent pharmacy feel.",
  "output_path": "/home/codex450/cdx/dev-projects/gcc/site/public/images/mockup-sponsors/del-kar-pharmacy.png"
}
```

Expected: A clean navy serif wordmark with a small green plus-sign to the left, on transparent background.

- [ ] **Step 2: Visual review — does it match the spec's "neighborhood pharmacy" voice?**

Open the generated file. Criteria:
- "Del Kar" is clearly legible at 200px wide
- Green ✚ is present and reads as a medical symbol (not confused with a star or flower)
- Navy is dark enough to read on a light background AND light enough to read over a dark photo (we'll add a subtle white outline later in component CSS if needed)
- Transparent background (not a white rectangle)

If any criterion fails, iterate on the prompt up to 3 times. If still not right after 3 tries, fall back to ComfyUI FLUX.2 Klein with a more specific prompt.

- [ ] **Step 3: Commit**

```bash
git add site/public/images/mockup-sponsors/del-kar-pharmacy.png
git commit -m "feat(sponsor-calculator): add Del Kar Pharmacy mockup logo

Generated via nanobanana (Gemini 2.5 Pro Image). Navy serif wordmark
with small green pharmacy cross. Represents the neighborhood pharmacy
archetype in the calculator upsell co-branding mockups.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Phase 2: Scene prompt data module

### Task 3: Build the sponsor-variation data module

**Files:**
- Create: `site/src/components/sponsor/calculator/variationData.ts`

This is a TypeScript data module consumed by both (a) the image generation scripts and (b) the `VariationShowcase` component. Having one source of truth prevents drift between generated assets and component props.

- [ ] **Step 1: Create the data module with types and constants**

Write `site/src/components/sponsor/calculator/variationData.ts`:

```ts
/**
 * Single source of truth for the 10 upsell cards' co-branded variation mockups.
 * Consumed by the VariationShowcase component and by scripts/generate-upsell-images.ts.
 *
 * Related spec: docs/superpowers/specs/2026-04-24-sponsor-upsell-cobranded-variations-design.md
 */

export type CardSlug =
  | 'worker-branding'
  | 'equipment-branding'
  | 'street-advertising'
  | 'digital-presence'
  | 'enhanced-cleaning'
  | 'seasonal-services'
  | 'green-services'
  | 'business-engagement'
  | 'events'
  | 'media'

export type SponsorSlug = 'monday-coffee' | 'del-kar' | 'barneys'

export interface Sponsor {
  slug: SponsorSlug
  name: string
  campaign: string
  logoPath: string
}

export const SPONSORS: Record<SponsorSlug, Sponsor> = {
  'monday-coffee': {
    slug: 'monday-coffee',
    name: 'Monday Coffee',
    campaign: 'Monday Morning Street Cleaners',
    logoPath: '/images/mockup-sponsors/monday-coffee.png',
  },
  'del-kar': {
    slug: 'del-kar',
    name: 'Del Kar Pharmacy',
    campaign: 'Healthy Blocks by Del Kar',
    logoPath: '/images/mockup-sponsors/del-kar-pharmacy.png',
  },
  barneys: {
    slug: 'barneys',
    name: "Barney's New Life",
    campaign: 'New Life in the Streets',
    logoPath: '/images/mockup-sponsors/barneys-new-life.png',
  },
}

export const SPONSOR_ORDER: SponsorSlug[] = ['monday-coffee', 'del-kar', 'barneys']

export interface CardPrompt {
  slug: CardSlug
  /** Base scene prompt — generates the GCC-only pre-wipe image */
  basePrompt: string
  /** Surface description fed into the edit-mode sponsor variations */
  surface: string
  /** Camera/lens hint shared by all 4 images in the set */
  shotHint: string
}

export const CARD_PROMPTS: Record<CardSlug, CardPrompt> = {
  'worker-branding': {
    slug: 'worker-branding',
    basePrompt:
      "Documentary editorial photograph of a Black community worker in a bright kelly-green GCC-branded work vest, sweeping a clean brick sidewalk in North Lawndale Chicago. Mature trees line the residential street. Late morning natural light, warm amber + green color grade. Mid-range shot, subject three-quarter view, face partially out of frame. Vest clearly shows white 'GCC' wordmark on chest patch. 3:2 landscape.",
    surface:
      "Add the sponsor's logo as a clean embroidered patch on the upper-left chest of the vest, matching the position and style of the GCC chest patch. Add the sponsor's campaign slogan text in white across the shoulder blades of the vest back where visible. Keep the original GCC chest patch intact — this is a CO-branded uniform, not a replacement.",
    shotHint: 'Mid-range 50mm lens, natural light',
  },
  'equipment-branding': {
    slug: 'equipment-branding',
    basePrompt:
      'Documentary editorial photograph of a bright kelly-green GCC-branded wheeled cleaning cart with push broom and rake resting against it, parked on a clean Chicago residential sidewalk. Cart side panel shows white GCC logo. Early morning soft light, warm amber + green color grade. 3/4 angle shot. 3:2 landscape.',
    surface:
      "Add the sponsor's logo as a printed vinyl decal on the lower third of the cart's side panel, below the GCC logo. Add the sponsor's campaign slogan text horizontally across the cart's rocker panel. Preserve the original GCC branding.",
    shotHint: '3/4 angle, 35mm lens, morning light',
  },
  'street-advertising': {
    slug: 'street-advertising',
    basePrompt:
      'Documentary editorial photograph of a freshly painted yard sign staked into the parkway grass of a North Lawndale Chicago residential block. Sign is clean, simple, planted at an inviting angle. Brick two-flats in soft-focus background. Golden hour warm light. 3:2 landscape. The sign currently reads "Cleaned by GCC" in green on white.',
    surface:
      "Replace the 'Cleaned by GCC' text on the yard sign with the sponsor's logo at the top of the sign, the campaign slogan as the headline, and a smaller 'in partnership with GCC' line below. Keep a small green GCC wordmark in the bottom-right corner of the sign.",
    shotHint: 'Low angle, 50mm lens, golden hour',
  },
  'digital-presence': {
    slug: 'digital-presence',
    basePrompt:
      "Documentary editorial photograph of a smartphone held at eye level showing a social media post: a high-quality before/after photo of a cleaned Chicago street. The post caption area clearly shows 'Cleaned by @GCC' with green GCC branding visible. Out-of-focus North Lawndale street in the background. Late afternoon warm light. 3:2 landscape.",
    surface:
      "Change the social media post caption on the phone screen to 'Presented by [sponsor name]: [campaign slogan]' with GCC tagged as a partner. Add a small sponsor logo watermark in the bottom-right of the post image. Preserve the GCC mention in the caption.",
    shotHint: 'Over-shoulder view, phone in focus, 50mm lens',
  },
  'enhanced-cleaning': {
    slug: 'enhanced-cleaning',
    basePrompt:
      'Documentary editorial photograph of a pristine, freshly pressure-washed brick wall in North Lawndale Chicago, with a small rectangular plaque mounted on it that reads "Cleaned by GCC — Community Partnership Program" in white text on a green plate. Worker in green GCC vest walks away in soft focus. Overcast cool-warm light. 3:2 landscape.',
    surface:
      "Change the plaque on the wall to display the sponsor's campaign slogan as the headline, the sponsor's logo prominently, and a smaller 'in partnership with GCC' line below. Keep the GCC vest on the walking worker intact.",
    shotHint: 'Eye-level, 50mm lens, overcast natural light',
  },
  'seasonal-services': {
    slug: 'seasonal-services',
    basePrompt:
      'Documentary editorial photograph of a cleared snow-covered North Lawndale Chicago sidewalk with a salt spreader in frame. A worker in green GCC winter parka is visible in soft focus. The spreader currently has a green GCC magnetic decal on its side. Crisp winter morning light. 3:2 landscape.',
    surface:
      "Replace the GCC magnetic decal on the salt spreader with a co-branded panel showing the sponsor's logo on top, the campaign slogan as the main text, and 'with GCC' as a subtitle. Add subtle sidewalk chalk text with the campaign slogan visible on the cleared path in the foreground.",
    shotHint: 'Low angle, 35mm lens, winter morning cool light',
  },
  'green-services': {
    slug: 'green-services',
    basePrompt:
      'Documentary editorial photograph of a freshly planted and mulched urban tree well on a North Lawndale Chicago residential street, with a small wooden marker plaque staked beside it that reads "Planted by GCC" in white sans-serif letters on a green background. Out-of-focus brick two-flat in background. Warm spring morning light. 3:2 landscape.',
    surface:
      "Change the tree-well marker plaque text to display the sponsor's campaign slogan as the headline and the sponsor's logo at top of the plaque. Add a small 'Planted with GCC' line at the bottom of the plaque.",
    shotHint: 'Low angle close-up, 50mm lens, spring warm light',
  },
  'business-engagement': {
    slug: 'business-engagement',
    basePrompt:
      'Documentary editorial photograph of a worker in a green GCC vest handing a printed flyer to a North Lawndale Chicago resident on a sunny sidewalk. The flyer is the focal subject, held forward toward the viewer, with a clean white background. The flyer masthead reads "GCC Community Notes" in green. Warm afternoon natural light. 3:2 landscape.',
    surface:
      "Replace the 'GCC Community Notes' masthead on the flyer with the sponsor's campaign slogan as the headline, the sponsor's logo at the top, and a small 'distributed by GCC' line. Keep the worker's GCC vest and the warm sidewalk scene unchanged.",
    shotHint: 'Over-shoulder view, flyer in sharp focus, 35mm lens',
  },
  events: {
    slug: 'events',
    basePrompt:
      'Documentary editorial photograph of a community block-party event in North Lawndale Chicago — a white pop-up tent with a green GCC banner reading "GCC Community Day" stretched across the top, a few community members milling around, a cleaning crew in green vests in the background. Warm afternoon golden light. 3:2 landscape.',
    surface:
      "Replace the 'GCC Community Day' banner text on the tent with the sponsor's campaign slogan as the big headline, the sponsor's logo prominently on the left side of the banner, and 'powered by GCC' as a smaller tagline on the right. Keep the crew's green GCC vests intact.",
    shotHint: 'Wide angle, 24mm lens, golden hour warm light',
  },
  media: {
    slug: 'media',
    basePrompt:
      'Documentary editorial photograph of a professional photographer (camera raised, subject-facing-away) capturing a GCC cleaning crew at work on a North Lawndale Chicago residential street. A small drone is visible in the sky overhead. Bottom of the frame has a subtle lower-third video-chyron graphic that reads "GCC Impact Story" in white on green. Cinematic dusk light, warm amber grade. 3:2 landscape.',
    surface:
      "Replace the 'GCC Impact Story' chyron text with the sponsor's campaign slogan as the headline, the sponsor's logo on the left of the chyron, and 'Presented in partnership with GCC' as a smaller subtitle. Keep the crew's vests intact.",
    shotHint: 'Wide angle, 24mm lens, dusk cinematic light',
  },
}
```

- [ ] **Step 2: Type-check the module**

```bash
cd /home/codex450/cdx/dev-projects/gcc/site && npx tsc --noEmit src/components/sponsor/calculator/variationData.ts
```

Expected: No errors.

- [ ] **Step 3: Commit**

```bash
git add site/src/components/sponsor/calculator/variationData.ts
git commit -m "feat(sponsor-calculator): add variationData module with 10 card prompts

Single source of truth for card slugs, sponsor metadata (logos +
campaigns), and per-card scene prompts consumed by both the image
generation script and the upcoming VariationShowcase component.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Phase 3: Image generation

### Task 4: Write the image generation driver script

**Files:**
- Create: `site/scripts/generate-upsell-images.ts`

This is a node script that iterates the 10 cards × 4 images (1 base + 3 variations) and invokes the generation APIs. It's a developer-only script — not shipped with the app.

- [ ] **Step 1: Create the script**

Write `site/scripts/generate-upsell-images.ts`:

```ts
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
```

- [ ] **Step 2: Run the script to print prompts for all 10 cards**

```bash
cd /home/codex450/cdx/dev-projects/gcc/site && npx tsx scripts/generate-upsell-images.ts
```

Expected output: ten `=== <card-slug> ===` sections each listing the base prompt, edit surface, and three variation instructions. Verification reports 40 missing files (none generated yet).

- [ ] **Step 3: Commit**

```bash
git add site/scripts/generate-upsell-images.ts
git commit -m "feat(sponsor-calculator): add image generation driver script

Prints per-card prompts and variation instructions from variationData,
verifies that all 40 upsell images exist. The actual nanobanana calls
are driven interactively by the agent during plan execution.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Generate 10 base scenes (GCC-only branding)

**Files:**
- Create: `site/public/images/upsells/worker-branding-base.jpg`
- Create: `site/public/images/upsells/equipment-branding-base.jpg`
- Create: `site/public/images/upsells/street-advertising-base.jpg`
- Create: `site/public/images/upsells/digital-presence-base.jpg`
- Create: `site/public/images/upsells/enhanced-cleaning-base.jpg`
- Create: `site/public/images/upsells/seasonal-services-base.jpg`
- Create: `site/public/images/upsells/green-services-base.jpg`
- Create: `site/public/images/upsells/business-engagement-base.jpg`
- Create: `site/public/images/upsells/events-base.jpg`
- Create: `site/public/images/upsells/media-base.jpg`

- [ ] **Step 1: For each card slug (worker-branding first), generate the base scene**

For `worker-branding`, call `mcp__nanobanana__generate_image` with:

```json
{
  "prompt": "<CARD_PROMPTS['worker-branding'].basePrompt from variationData.ts>",
  "output_path": "/home/codex450/cdx/dev-projects/gcc/site/public/images/upsells/worker-branding-base.jpg",
  "aspect_ratio": "3:2",
  "quality": "high"
}
```

Expected: A 1920×1280 JPEG photorealistic editorial scene with visible GCC green vest branding, saved to the path.

- [ ] **Step 2: Visual quality check on worker-branding-base.jpg**

Open the file. Verify:
- Subject is clearly a community worker sweeping
- Vest color is brand-green (hex close to #16a34a / #22c55e)
- "GCC" text is legible on the vest chest patch
- Composition leaves ~40% negative space on one side for gradient overlay + text readability
- Image is 1920×1280 or larger (no upscaling needed)
- No AI artifacts around hands, feet, or tools

If any check fails, iterate on the prompt (add specifics about hand position, vest fit, text sharpness) and regenerate up to 3 times. If still failing, fall back to ComfyUI FLUX.2 Klein via the running instance at `127.0.0.1:8188` with the same prompt text.

- [ ] **Step 3: Repeat Steps 1-2 for the remaining 9 cards**

Generate in this order (roughly left-to-right across the calculator flow):
1. `equipment-branding`
2. `street-advertising`
3. `digital-presence`
4. `enhanced-cleaning`
5. `seasonal-services`
6. `green-services`
7. `business-engagement`
8. `events`
9. `media`

Use the corresponding `CARD_PROMPTS[slug].basePrompt` for each. Apply the same quality check from Step 2 after each. Do not batch more than 3 at a time — nanobanana throughput and cost makes it worth inspecting each before proceeding.

- [ ] **Step 4: Run the verification script**

```bash
cd /home/codex450/cdx/dev-projects/gcc/site && npx tsx scripts/generate-upsell-images.ts
```

Expected: Reports "Found: 10/40" (10 base images present, 30 variations still missing).

- [ ] **Step 5: Commit all 10 base images**

```bash
git add site/public/images/upsells/*-base.jpg
git commit -m "feat(sponsor-calculator): add 10 GCC-only base scenes for upsell cards

Editorial/lifestyle photography for each of the 10 upsell card
categories showing GCC-only branding. Generated via nanobanana MCP
(Gemini 2.5/3 Pro Image). These are the pre-wipe state.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Generate 30 co-branded variations via edit-mode

**Files:**
- Create: 30 files in `site/public/images/upsells/` named `<card>-<sponsor>.jpg`

For each of the 10 cards, generate three edits off its base image — one per sponsor — using the edit-mode API with the sponsor's logo PNG as a reference.

- [ ] **Step 1: For `worker-branding`, generate the Monday Coffee variation**

Call `mcp__nanobanana__edit_image` with:

```json
{
  "source_image": "/home/codex450/cdx/dev-projects/gcc/site/public/images/upsells/worker-branding-base.jpg",
  "reference_images": ["/home/codex450/cdx/dev-projects/gcc/site/public/images/mockup-sponsors/monday-coffee.png"],
  "prompt": "Edit this photo: Add the Monday Coffee logo (red circular MC monogram from reference image) as a clean embroidered patch on the upper-left chest of the vest, next to the existing GCC patch. The patch should be approximately 60% the size of the GCC patch, positioned slightly below and to the right of it. Add the text 'Monday Morning Street Cleaners' in clean white sans-serif letters arched across the shoulder blades of the vest back where visible. Preserve the original GCC chest patch, the worker's pose, and all lighting. This is a CO-branded uniform, not a replacement.",
  "output_path": "/home/codex450/cdx/dev-projects/gcc/site/public/images/upsells/worker-branding-monday-coffee.jpg"
}
```

Expected: A new image matching the base scene with the Monday Coffee mark and campaign slogan now visible on the vest, alongside the original GCC branding.

- [ ] **Step 2: Visual check for worker-branding-monday-coffee.jpg**

Verify:
- Original base scene composition and lighting are preserved
- Monday Coffee red MC circle is pixel-accurate (not a distorted approximation)
- The word "Monday Morning Street Cleaners" is legible
- GCC chest patch is still visible

If any check fails, iterate with clearer placement instructions. If the logo is still distorted after 3 attempts, the edit-mode may be failing to lock to the reference — in that case, compose the logo overlay manually in a separate step using an image-composition library (sharp, installed via `npm i sharp --save-dev` as a dev dep) and a hand-placed `(x, y, width)` coordinate for the logo. Document the fallback in the plan's checkpoint notes.

- [ ] **Step 3: Generate worker-branding-del-kar.jpg**

Same call pattern with:
- `reference_images`: `["/.../del-kar-pharmacy.png"]`
- Prompt substitutes "Monday Coffee" → "Del Kar Pharmacy" and campaign slogan → "Healthy Blocks by Del Kar"
- `output_path`: `worker-branding-del-kar.jpg`

- [ ] **Step 4: Generate worker-branding-barneys.jpg**

Same call pattern with:
- `reference_images`: `["/.../barneys-new-life.png"]`
- Prompt substitutes → "Barney's New Life" and campaign slogan → "New Life in the Streets"
- `output_path`: `worker-branding-barneys.jpg`

- [ ] **Step 5: Visual check — all three variations share the same base composition**

Open `worker-branding-base.jpg`, `worker-branding-monday-coffee.jpg`, `worker-branding-del-kar.jpg`, `worker-branding-barneys.jpg` side by side. The four images MUST share the same camera angle, subject pose, lighting, and background. Only the logo + slogan differ. If any of the three variations has drifted (different pose, different background), regenerate that variation using the base image as the source again.

- [ ] **Step 6: Repeat Steps 1-5 for the remaining 9 cards**

Cards remaining: equipment-branding, street-advertising, digital-presence, enhanced-cleaning, seasonal-services, green-services, business-engagement, events, media.

For each card:
- Use the card's `basePrompt` scene as the unchanged source image
- Apply the card's `surface` instruction (from `variationData.ts`) with the sponsor name + campaign slogan interpolated
- Feed the correct sponsor logo PNG as the reference image
- Verify composition consistency across the 4 images of that card's set

- [ ] **Step 7: Run the verification script**

```bash
cd /home/codex450/cdx/dev-projects/gcc/site && npx tsx scripts/generate-upsell-images.ts
```

Expected: "Found: 40/40" and no missing files.

- [ ] **Step 8: Commit all 30 variations**

```bash
git add site/public/images/upsells/*-monday-coffee.jpg \
        site/public/images/upsells/*-del-kar.jpg \
        site/public/images/upsells/*-barneys.jpg
git commit -m "feat(sponsor-calculator): add 30 co-branded upsell variations

Three sponsor variations per card (Monday Coffee, Del Kar, Barney's)
edited from each base scene via nanobanana edit-mode with the sponsor
logo PNG as a reference. Each variation preserves the base composition
and adds the sponsor logo + campaign slogan on the defined surface.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Phase 4: VariationShowcase component

### Task 7: Scaffold the VariationShowcase component

**Files:**
- Create: `site/src/components/sponsor/calculator/VariationShowcase.tsx`

- [ ] **Step 1: Write the component**

Write `site/src/components/sponsor/calculator/VariationShowcase.tsx`:

```tsx
'use client'

import Image from 'next/image'
import { useEffect, useMemo, useRef, useState } from 'react'
import { cn } from '@/lib/utils'
import { SPONSORS, SPONSOR_ORDER, type SponsorSlug } from '@/components/sponsor/calculator/variationData'

interface VariationShowcaseProps {
  title: string
  subtitle: string
  description: string
  benefits: string[]
  /** Path to the GCC-only base scene (pre-wipe) */
  baseImageSrc: string
  /** Map of sponsor slug -> image path for the co-branded variations */
  variationImageSrcs: Record<SponsorSlug, string>
  imageAlt?: string
  accentColor?: 'gold' | 'green' | 'brown' | 'sky'
  icon?: React.ReactNode
}

const accentStyles = {
  gold: {
    bg: 'bg-gradient-to-br from-brand-green-100 to-amber-100',
    border: 'border-brand-green-200',
    text: 'text-brand-green-800',
    badge: 'bg-brand-green-500 text-white',
    chip: 'bg-brand-green-500 text-white',
    chipInactive: 'bg-white/80 text-brand-green-800 border-brand-green-300',
  },
  green: {
    bg: 'bg-gradient-to-br from-brand-green-100 to-emerald-100',
    border: 'border-brand-green-200',
    text: 'text-brand-green-800',
    badge: 'bg-brand-green-500 text-white',
    chip: 'bg-brand-green-500 text-white',
    chipInactive: 'bg-white/80 text-brand-green-800 border-brand-green-300',
  },
  brown: {
    bg: 'bg-gradient-to-br from-brand-green-100 to-amber-100',
    border: 'border-brand-green-200',
    text: 'text-brand-green-800',
    badge: 'bg-brand-green-700 text-white',
    chip: 'bg-brand-green-700 text-white',
    chipInactive: 'bg-white/80 text-brand-green-800 border-brand-green-300',
  },
  sky: {
    bg: 'bg-gradient-to-br from-brand-blue-100 to-blue-100',
    border: 'border-brand-blue-200',
    text: 'text-brand-blue-800',
    badge: 'bg-brand-blue-500 text-white',
    chip: 'bg-brand-blue-500 text-white',
    chipInactive: 'bg-white/80 text-brand-blue-800 border-brand-blue-300',
  },
}

const WIPE_DURATION_MS = 1200
const SPONSOR_CYCLE_MS = 3000
const CROSSFADE_MS = 600

export function VariationShowcase({
  title,
  subtitle,
  description,
  benefits,
  baseImageSrc,
  variationImageSrcs,
  imageAlt,
  accentColor = 'gold',
  icon,
}: VariationShowcaseProps) {
  const styles = accentStyles[accentColor]
  const rootRef = useRef<HTMLDivElement>(null)

  const [hasEntered, setHasEntered] = useState(false)
  const [wipeProgress, setWipeProgress] = useState(0) // 0..1
  const [activeIdx, setActiveIdx] = useState<0 | 1 | 2>(0)
  const [isPaused, setIsPaused] = useState(false)
  const [isDragging, setIsDragging] = useState(false)
  const [prefersReducedMotion, setPrefersReducedMotion] = useState(false)

  // Detect prefers-reduced-motion
  useEffect(() => {
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)')
    setPrefersReducedMotion(mq.matches)
    const handler = (e: MediaQueryListEvent) => setPrefersReducedMotion(e.matches)
    mq.addEventListener('change', handler)
    return () => mq.removeEventListener('change', handler)
  }, [])

  // IntersectionObserver: set hasEntered once when card enters viewport
  useEffect(() => {
    const node = rootRef.current
    if (!node || hasEntered) return
    const io = new IntersectionObserver(
      (entries) => {
        for (const e of entries) {
          if (e.isIntersecting) {
            setHasEntered(true)
            io.disconnect()
          }
        }
      },
      { threshold: 0.35 }
    )
    io.observe(node)
    return () => io.disconnect()
  }, [hasEntered])

  // Initial wipe animation once hasEntered triggers
  useEffect(() => {
    if (!hasEntered) return
    if (prefersReducedMotion) {
      setWipeProgress(1)
      return
    }
    let raf = 0
    const start = performance.now()
    const tick = (now: number) => {
      const t = Math.min(1, (now - start) / WIPE_DURATION_MS)
      setWipeProgress(t)
      if (t < 1) raf = requestAnimationFrame(tick)
    }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [hasEntered, prefersReducedMotion])

  // Cycle through sponsors
  useEffect(() => {
    if (!hasEntered || isPaused || isDragging || prefersReducedMotion) return
    const interval = setInterval(() => {
      setActiveIdx((prev) => (((prev + 1) % SPONSOR_ORDER.length) as 0 | 1 | 2))
    }, SPONSOR_CYCLE_MS + CROSSFADE_MS)
    return () => clearInterval(interval)
  }, [hasEntered, isPaused, isDragging, prefersReducedMotion])

  const activeSponsor = useMemo(() => SPONSORS[SPONSOR_ORDER[activeIdx]], [activeIdx])
  const activeImageSrc = variationImageSrcs[SPONSOR_ORDER[activeIdx]]

  // Drag handlers on the wipe handle — map pointer X to wipeProgress [0..1]
  const containerRef = useRef<HTMLDivElement>(null)

  const handlePointerDown = (e: React.PointerEvent) => {
    setIsDragging(true)
    setIsPaused(true)
    ;(e.target as HTMLElement).setPointerCapture(e.pointerId)
  }

  const handlePointerMove = (e: React.PointerEvent) => {
    if (!isDragging) return
    const rect = containerRef.current?.getBoundingClientRect()
    if (!rect) return
    const x = Math.max(0, Math.min(rect.width, e.clientX - rect.left))
    setWipeProgress(x / rect.width)
  }

  const handlePointerUp = () => {
    setIsDragging(false)
    // Snap back to fully revealed after release so cycling resumes meaningfully
    setWipeProgress(1)
    setIsPaused(false)
  }

  const handleChipClick = (slug: SponsorSlug) => {
    const idx = SPONSOR_ORDER.indexOf(slug) as 0 | 1 | 2
    setActiveIdx(idx)
    setIsPaused((prev) => !prev || SPONSOR_ORDER[activeIdx] !== slug)
  }

  const inset = `inset(0 ${(1 - wipeProgress) * 100}% 0 0)`

  return (
    <div
      ref={rootRef}
      className={cn('rounded-2xl border overflow-hidden mb-6', styles.border, styles.bg)}
    >
      <div className="flex flex-col lg:flex-row">
        {/* Image wipe container */}
        <div
          ref={containerRef}
          className="relative lg:w-2/5 h-48 lg:h-auto min-h-[12rem] overflow-hidden select-none"
        >
          {/* Base layer (pre-wipe, GCC-only) */}
          <Image
            src={baseImageSrc}
            alt={imageAlt ? `${imageAlt} — GCC-only baseline` : title}
            fill
            className="object-cover"
            sizes="(max-width: 1024px) 100vw, 40vw"
            priority={false}
          />
          {/* Overlay layer (post-wipe, co-branded, clip-pathed) */}
          <div
            className="absolute inset-0"
            style={{ clipPath: inset, WebkitClipPath: inset, transition: isDragging ? 'none' : `clip-path ${CROSSFADE_MS}ms ease-out` }}
          >
            <Image
              key={activeImageSrc}
              src={activeImageSrc}
              alt={`${imageAlt ?? title} co-branded with ${activeSponsor.name}. Campaign: ${activeSponsor.campaign}.`}
              fill
              className="object-cover"
              sizes="(max-width: 1024px) 100vw, 40vw"
              priority={false}
            />
          </div>
          {/* Gradient overlay for text readability */}
          <div className="absolute inset-0 bg-gradient-to-t from-black/50 via-transparent to-transparent lg:bg-gradient-to-r pointer-events-none" />
          {/* Wipe handle */}
          {hasEntered && !prefersReducedMotion && (
            <div
              role="slider"
              aria-label="Wipe between GCC-only and co-branded variation"
              aria-valuenow={Math.round(wipeProgress * 100)}
              aria-valuemin={0}
              aria-valuemax={100}
              tabIndex={0}
              className="absolute top-0 bottom-0 w-1 bg-white/90 cursor-ew-resize shadow-lg"
              style={{ left: `calc(${wipeProgress * 100}% - 2px)` }}
              onPointerDown={handlePointerDown}
              onPointerMove={handlePointerMove}
              onPointerUp={handlePointerUp}
              onKeyDown={(e) => {
                if (e.key === 'ArrowLeft') setWipeProgress((v) => Math.max(0, v - 0.1))
                if (e.key === 'ArrowRight') setWipeProgress((v) => Math.min(1, v + 0.1))
              }}
            >
              <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-6 h-6 rounded-full bg-white shadow-md flex items-center justify-center">
                <span className="text-xs text-neutral-700">⇔</span>
              </div>
            </div>
          )}
          {/* Icon badge fallback */}
          {icon && (
            <div className="absolute top-3 left-3 w-10 h-10 rounded-full bg-white/90 backdrop-blur-sm flex items-center justify-center shadow">
              {icon}
            </div>
          )}
        </div>

        {/* Content section */}
        <div className="flex-1 p-6">
          <div className="flex items-start justify-between mb-3">
            <div>
              <span className={cn('text-xs font-semibold uppercase tracking-wider', styles.text)}>
                {subtitle}
              </span>
              <h3 className="text-xl font-bold text-neutral-900 mt-1">{title}</h3>
            </div>
            <span className={cn('text-xs font-bold px-3 py-1 rounded-full', styles.badge)}>
              High Impact
            </span>
          </div>

          <p className="text-neutral-700 mb-4">{description}</p>

          {/* Sponsor chips */}
          <div className="flex flex-wrap items-center gap-2 mb-4">
            <span className="text-xs text-neutral-500 mr-1">Preview sponsor:</span>
            {SPONSOR_ORDER.map((slug, i) => {
              const sponsor = SPONSORS[slug]
              const isActive = i === activeIdx
              return (
                <button
                  key={slug}
                  onClick={() => handleChipClick(slug)}
                  aria-pressed={isActive}
                  className={cn(
                    'px-3 py-1 rounded-full text-xs font-semibold border transition-colors',
                    isActive ? styles.chip : styles.chipInactive
                  )}
                >
                  {sponsor.name}
                </button>
              )
            })}
          </div>

          <div className="space-y-2">
            <p className="text-sm font-semibold text-neutral-900">Why sponsors love this:</p>
            <ul className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              {benefits.map((benefit, index) => (
                <li key={index} className="flex items-start gap-2 text-sm text-neutral-700">
                  <span className={cn('flex-shrink-0 w-5 h-5 rounded-full flex items-center justify-center text-xs', styles.badge)}>
                    {index + 1}
                  </span>
                  {benefit}
                </li>
              ))}
            </ul>
          </div>

          <p className="mt-3 text-[11px] text-neutral-400 italic">
            Mockup only — Monday Coffee, Del Kar Pharmacy, and Barney&apos;s New Life are fictional example sponsors.
          </p>
        </div>
      </div>
    </div>
  )
}
```

- [ ] **Step 2: Type-check the component**

```bash
cd /home/codex450/cdx/dev-projects/gcc/site && npx tsc --noEmit
```

Expected: No errors.

- [ ] **Step 3: Lint the component**

```bash
cd /home/codex450/cdx/dev-projects/gcc/site && npx next lint --file src/components/sponsor/calculator/VariationShowcase.tsx
```

Expected: No errors or warnings specific to this file. If lint config reports any, fix inline.

- [ ] **Step 4: Commit**

```bash
git add site/src/components/sponsor/calculator/VariationShowcase.tsx
git commit -m "feat(sponsor-calculator): add VariationShowcase component

Stacks two images (GCC-only base + co-branded overlay), wipes left
to right on IntersectionObserver entry, cycles through three sponsor
variations with crossfade, exposes draggable wipe handle and sponsor
chips. prefers-reduced-motion skips animation.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Phase 5: Wire into calculator sections

### Task 8: Add per-card-slug image path helper

**Files:**
- Modify: `site/src/components/sponsor/calculator/variationData.ts`

- [ ] **Step 1: Append an image-path helper to variationData.ts**

Open `site/src/components/sponsor/calculator/variationData.ts` and append to the bottom:

```ts

export function baseImageSrcFor(slug: CardSlug): string {
  return `/images/upsells/${slug}-base.jpg`
}

export function variationImageSrcsFor(slug: CardSlug): Record<SponsorSlug, string> {
  return {
    'monday-coffee': `/images/upsells/${slug}-monday-coffee.jpg`,
    'del-kar': `/images/upsells/${slug}-del-kar.jpg`,
    barneys: `/images/upsells/${slug}-barneys.jpg`,
  }
}
```

- [ ] **Step 2: Type-check**

```bash
cd /home/codex450/cdx/dev-projects/gcc/site && npx tsc --noEmit
```

Expected: No errors.

- [ ] **Step 3: Commit**

```bash
git add site/src/components/sponsor/calculator/variationData.ts
git commit -m "feat(sponsor-calculator): add image-src helpers to variationData

baseImageSrcFor and variationImageSrcsFor return stable path strings
keyed by card slug, so callsites in the 4 section components stay
declarative and drift-free.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Wire BrandingSection to VariationShowcase (4 callsites)

**Files:**
- Modify: `site/src/components/sponsor/calculator/sections/BrandingSection.tsx`

- [ ] **Step 1: Update imports**

Open `site/src/components/sponsor/calculator/sections/BrandingSection.tsx`. Replace the existing `CategoryShowcase` import line with:

```tsx
import { CategoryShowcase, categoryShowcases } from '@/components/sponsor/calculator/CategoryShowcase'
import { VariationShowcase } from '@/components/sponsor/calculator/VariationShowcase'
import { baseImageSrcFor, variationImageSrcsFor } from '@/components/sponsor/calculator/variationData'
```

(Keep the existing `CategoryShowcase` import — it is still needed elsewhere in the file if any non-upsell cards use it; remove only if no usages remain after the edit.)

- [ ] **Step 2: Replace each of the 4 CategoryShowcase calls**

Find the four `<CategoryShowcase {...categoryShowcases.xxx} ... />` invocations at the lines listed below and replace each:

**Line ~97-101 (workerBranding):**

```tsx
<VariationShowcase
  {...categoryShowcases.workerBranding}
  baseImageSrc={baseImageSrcFor('worker-branding')}
  variationImageSrcs={variationImageSrcsFor('worker-branding')}
  imageAlt="Community worker in green GCC vest sweeping a Chicago sidewalk"
/>
```

**Line ~122-126 (equipmentBranding):**

```tsx
<VariationShowcase
  {...categoryShowcases.equipmentBranding}
  baseImageSrc={baseImageSrcFor('equipment-branding')}
  variationImageSrcs={variationImageSrcsFor('equipment-branding')}
  imageAlt="GCC-branded cleaning cart with broom and rake on a Chicago sidewalk"
/>
```

**Line ~147-151 (streetAdvertising):**

```tsx
<VariationShowcase
  {...categoryShowcases.streetAdvertising}
  baseImageSrc={baseImageSrcFor('street-advertising')}
  variationImageSrcs={variationImageSrcsFor('street-advertising')}
  imageAlt="Yard sign on North Lawndale residential parkway"
/>
```

**Line ~171-175 (digitalPresence):**

```tsx
<VariationShowcase
  {...categoryShowcases.digitalPresence}
  baseImageSrc={baseImageSrcFor('digital-presence')}
  variationImageSrcs={variationImageSrcsFor('digital-presence')}
  imageAlt="Smartphone showing a GCC social media post"
/>
```

- [ ] **Step 3: Type-check**

```bash
cd /home/codex450/cdx/dev-projects/gcc/site && npx tsc --noEmit
```

Expected: No errors.

- [ ] **Step 4: Commit**

```bash
git add site/src/components/sponsor/calculator/sections/BrandingSection.tsx
git commit -m "feat(sponsor-calculator): wire Branding tab to VariationShowcase

Swaps the 4 CategoryShowcase invocations in BrandingSection for
VariationShowcase, feeding it base + per-sponsor image paths from
the new variationData helpers.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: Wire ServicesSection to VariationShowcase (4 callsites)

**Files:**
- Modify: `site/src/components/sponsor/calculator/sections/ServicesSection.tsx`

- [ ] **Step 1: Update imports**

Open `site/src/components/sponsor/calculator/sections/ServicesSection.tsx`. After the existing `CategoryShowcase` import, add:

```tsx
import { VariationShowcase } from '@/components/sponsor/calculator/VariationShowcase'
import { baseImageSrcFor, variationImageSrcsFor } from '@/components/sponsor/calculator/variationData'
```

- [ ] **Step 2: Replace each of the 4 CategoryShowcase calls**

**Line ~56 (enhancedCleaning):**

```tsx
<VariationShowcase
  {...categoryShowcases.enhancedCleaning}
  baseImageSrc={baseImageSrcFor('enhanced-cleaning')}
  variationImageSrcs={variationImageSrcsFor('enhanced-cleaning')}
  imageAlt="Freshly pressure-washed brick wall with GCC plaque"
/>
```

**Line ~80 (seasonalServices):**

```tsx
<VariationShowcase
  {...categoryShowcases.seasonalServices}
  baseImageSrc={baseImageSrcFor('seasonal-services')}
  variationImageSrcs={variationImageSrcsFor('seasonal-services')}
  imageAlt="Cleared snow-covered sidewalk with GCC salt spreader"
/>
```

**Line ~104 (greenServices):**

```tsx
<VariationShowcase
  {...categoryShowcases.greenServices}
  baseImageSrc={baseImageSrcFor('green-services')}
  variationImageSrcs={variationImageSrcsFor('green-services')}
  imageAlt="Mulched and planted urban tree well with GCC marker"
/>
```

**Line ~138 (businessEngagement):**

```tsx
<VariationShowcase
  {...categoryShowcases.businessEngagement}
  baseImageSrc={baseImageSrcFor('business-engagement')}
  variationImageSrcs={variationImageSrcsFor('business-engagement')}
  imageAlt="GCC worker handing a branded flyer to a resident"
/>
```

- [ ] **Step 3: Type-check**

```bash
cd /home/codex450/cdx/dev-projects/gcc/site && npx tsc --noEmit
```

Expected: No errors.

- [ ] **Step 4: Commit**

```bash
git add site/src/components/sponsor/calculator/sections/ServicesSection.tsx
git commit -m "feat(sponsor-calculator): wire Services tab to VariationShowcase

Swaps the 4 CategoryShowcase invocations in ServicesSection for
VariationShowcase with per-sponsor image paths.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 11: Wire EventsSection + MediaSection to VariationShowcase

**Files:**
- Modify: `site/src/components/sponsor/calculator/sections/EventsSection.tsx`
- Modify: `site/src/components/sponsor/calculator/sections/MediaSection.tsx`

- [ ] **Step 1: Update EventsSection imports and callsite**

Open `site/src/components/sponsor/calculator/sections/EventsSection.tsx`. After the existing `CategoryShowcase` import, add:

```tsx
import { VariationShowcase } from '@/components/sponsor/calculator/VariationShowcase'
import { baseImageSrcFor, variationImageSrcsFor } from '@/components/sponsor/calculator/variationData'
```

Replace the CategoryShowcase call (around line 63):

```tsx
<VariationShowcase
  {...categoryShowcases.events}
  baseImageSrc={baseImageSrcFor('events')}
  variationImageSrcs={variationImageSrcsFor('events')}
  imageAlt="Community block-party event with GCC-branded tent and crew"
/>
```

- [ ] **Step 2: Update MediaSection imports and callsite**

Open `site/src/components/sponsor/calculator/sections/MediaSection.tsx`. Replace the existing import line:

```tsx
import { CategoryShowcase, categoryShowcases } from '@/components/sponsor/calculator/CategoryShowcase'
```

with:

```tsx
import { categoryShowcases } from '@/components/sponsor/calculator/CategoryShowcase'
import { VariationShowcase } from '@/components/sponsor/calculator/VariationShowcase'
import { baseImageSrcFor, variationImageSrcsFor } from '@/components/sponsor/calculator/variationData'
```

Replace the CategoryShowcase call (around line 52-57):

```tsx
<VariationShowcase
  {...categoryShowcases.media}
  baseImageSrc={baseImageSrcFor('media')}
  variationImageSrcs={variationImageSrcsFor('media')}
  imageAlt="Professional photographer and drone capturing a GCC cleaning crew"
  icon={<Camera className="w-8 h-8 text-brand-blue-600" />}
/>
```

- [ ] **Step 3: Type-check**

```bash
cd /home/codex450/cdx/dev-projects/gcc/site && npx tsc --noEmit
```

Expected: No errors.

- [ ] **Step 4: Commit**

```bash
git add site/src/components/sponsor/calculator/sections/EventsSection.tsx \
        site/src/components/sponsor/calculator/sections/MediaSection.tsx
git commit -m "feat(sponsor-calculator): wire Events + Media tabs to VariationShowcase

Last 2 upsell callsites (events, media) switch from CategoryShowcase
to VariationShowcase. All 10 upsell cards now drive off the
variationData helpers.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Phase 6: Verification

### Task 12: Playwright E2E test for the wipe + sponsor chips

**Files:**
- Create: `site/e2e/sponsor-calculator-variations.spec.ts`

- [ ] **Step 1: Write the E2E test**

Write `site/e2e/sponsor-calculator-variations.spec.ts`:

```ts
import { test, expect } from '@playwright/test'

const CARDS_PER_TAB = {
  Branding: 4,
  Services: 4,
  Events: 1,
  Media: 1,
}

test.describe('Sponsor calculator variation showcases', () => {
  test('each upsell card renders a variation showcase with 3 sponsor chips', async ({ page }) => {
    await page.goto('/sponsor/calculator')

    for (const tab of Object.keys(CARDS_PER_TAB)) {
      await page.getByRole('button', { name: tab }).click()

      // Wait for tab content to render
      await page.waitForTimeout(500)

      // Each VariationShowcase renders 3 sponsor chip buttons: Monday Coffee, Del Kar Pharmacy, Barney's New Life
      const mondayChips = page.getByRole('button', { name: 'Monday Coffee' })
      await expect(mondayChips.first()).toBeVisible()
      await expect(mondayChips).toHaveCount(CARDS_PER_TAB[tab as keyof typeof CARDS_PER_TAB])
    }
  })

  test('clicking a sponsor chip marks it pressed', async ({ page }) => {
    await page.goto('/sponsor/calculator')
    await page.getByRole('button', { name: 'Branding' }).click()
    await page.waitForTimeout(500)

    const firstDelKar = page.getByRole('button', { name: 'Del Kar Pharmacy' }).first()
    await firstDelKar.click()
    await expect(firstDelKar).toHaveAttribute('aria-pressed', 'true')
  })

  test('base + variation images both resolve for each card', async ({ page, request }) => {
    const slugs = [
      'worker-branding',
      'equipment-branding',
      'street-advertising',
      'digital-presence',
      'enhanced-cleaning',
      'seasonal-services',
      'green-services',
      'business-engagement',
      'events',
      'media',
    ]
    const sponsors = ['monday-coffee', 'del-kar', 'barneys']
    const base = 'http://localhost:7350'

    for (const slug of slugs) {
      const baseResp = await request.get(`${base}/images/upsells/${slug}-base.jpg`)
      expect(baseResp.status(), `base image for ${slug}`).toBe(200)
      for (const sponsor of sponsors) {
        const r = await request.get(`${base}/images/upsells/${slug}-${sponsor}.jpg`)
        expect(r.status(), `${slug}-${sponsor} image`).toBe(200)
      }
    }
  })
})
```

- [ ] **Step 2: Run the tests against a running dev server**

In one terminal:

```bash
cd /home/codex450/cdx/dev-projects/gcc/site && npm run dev
```

Wait for "Ready in …" output.

In another terminal:

```bash
cd /home/codex450/cdx/dev-projects/gcc/site && npx playwright test e2e/sponsor-calculator-variations.spec.ts --project='Desktop Chrome'
```

Expected: All 3 tests pass.

If a test fails, diagnose: is it a missing image file? Wrong selector? Animation timing? Fix the root cause, not the test.

- [ ] **Step 3: Commit**

```bash
git add site/e2e/sponsor-calculator-variations.spec.ts
git commit -m "test(e2e): sponsor calculator variation showcases

Three Playwright tests verify each upsell card renders 3 sponsor
chips, chip clicks set aria-pressed, and all 40 image assets resolve
over HTTP.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 13: Manual visual verification in dev server

**Files:** (none modified)

- [ ] **Step 1: Start dev server**

```bash
cd /home/codex450/cdx/dev-projects/gcc/site && npm run dev
```

Wait for "Ready" output at `http://localhost:7350`.

- [ ] **Step 2: Load /sponsor/calculator and walk through every tab**

Open `http://localhost:7350/sponsor/calculator` in Chrome. Select "Branding" → advance through to step 4 (Branding), then the Services/Events/Media tabs. For each of the 10 upsell cards verify:

- Pre-wipe base image shows visible GCC branding
- Wipe animates left → right on scroll-in (≤1.2s)
- Post-wipe image shows sponsor logo on the designated surface + campaign slogan
- Post-wipe cycles through all three sponsors with a crossfade
- Clicking a sponsor chip locks the variation; aria-pressed is visible in DevTools
- Wipe handle can be dragged back and forth
- Mockup disclaimer caption is visible under the content

- [ ] **Step 3: Mobile viewport check**

In Chrome DevTools, switch to "iPhone 14 Pro" device. Reload the page. Verify each card still renders, wipe still animates (vertically stacked layout), chips wrap gracefully, and text is readable.

- [ ] **Step 4: Reduced-motion check**

Enable `prefers-reduced-motion` in DevTools (Rendering tab → Emulate CSS media feature). Reload. Verify no wipe animation runs; cards show a static post-wipe variation #1; chips still work.

- [ ] **Step 5: Lighthouse check**

Run Lighthouse Performance audit on `/sponsor/calculator`. Compare to the baseline score (captured before starting this plan). Expected: Performance drop ≤ 5 points. If drop is larger, investigate whether `priority` loading was left enabled on too many cards, or whether any images exceed intended size.

If drop exceeds 5 points, tune Next.js Image `sizes` and add `loading="lazy"` where missing. Re-audit.

- [ ] **Step 6: No-commit — update task as verified**

This task produces no file changes. Update the task board to mark all verification tasks complete.

---

## Self-Review Notes

**Spec coverage:** Every spec section maps to at least one task — mechanic (Task 7), sponsors+campaigns (Tasks 1-2), visual style (Tasks 5-6), co-branding surfaces (Task 3 via CARD_PROMPTS), image assets (Tasks 5-6), component design (Task 7), callsite updates (Tasks 9-11), accessibility (Task 7 aria-pressed, aria-label, keyboard handlers), risks (mitigations baked into Tasks 5-6 visual checks and Task 13 Lighthouse check), success criteria (Task 12 E2E + Task 13 manual verify).

**Placeholders:** None. Every step has real code, real paths, real commands.

**Type consistency:** `CardSlug`, `SponsorSlug`, `Sponsor`, `CardPrompt` defined in Task 3; helpers `baseImageSrcFor` and `variationImageSrcsFor` added in Task 8; `VariationShowcaseProps` defined in Task 7; no divergent method signatures detected.

**One gap patched inline:** The spec mentioned a per-page "mockup disclaimer caption" above the upsell stack. Rather than thread that through the calculator page, the disclaimer is embedded inside each `VariationShowcase` (Task 7, final `<p>` block). That keeps it co-located with the fictional sponsor content and makes it clear which cards are mockups vs. real sponsors (if any real sponsors are added later).
