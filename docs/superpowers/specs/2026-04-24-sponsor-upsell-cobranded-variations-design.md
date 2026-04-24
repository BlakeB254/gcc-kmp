# Sponsor Upsell Cards — Co-Branded Variation Mockups

**Date**: 2026-04-24
**Project**: GCC Site (`~/cdx/dev-projects/gcc/site`)
**Page**: `/sponsor/calculator`
**Status**: Design proposed, awaiting user review

## Problem

The sponsor calculator's 10 upsell cards (Branding × 4, Services × 4, Events × 1, Media × 1) currently display hand-drawn SVG illustrations. They undersell the premium price points (up to $35,000 per card) and fail to answer the sponsor's core question: *"What does this look like with MY brand on it?"*

## Goal

Replace the 10 SVGs with a motion-driven visual mockup that demonstrates what each GCC cleaning campaign looks like when co-branded with a sponsor. Let the sponsor see themselves in the frame at all three archetypes — local small business, regional/national brand, community nonprofit.

## Mechanic

Each card shows two stacked states with a wipe transition between them:

1. **Pre-wipe (base state)** — The cleaning work fully branded with **GCC only**. Green GCC vests, GCC-logo equipment, "Cleaned by GCC" plaques. Represents the baseline product GCC delivers today.

2. **Post-wipe (co-branded state)** — Same scene, same composition, now co-branded with GCC + one sponsor. Sponsor's logo, campaign slogan, and treatment ride on the card's designated surface (vest patch, cart panel, yard sign, plaque, etc.).

3. **Sponsor cycle** — The post-wipe state rotates through three sponsor variations every ~3 seconds with a subtle crossfade: Monday Coffee → Del Kar Pharmacy → Barney's New Life → loop.

### Animation sequence on a single card

```
t=0.0s  Card enters viewport (IntersectionObserver)
t=0.3s  Wipe begins (left → right, 1.2s duration, ease-out)
t=1.5s  Post-wipe fully revealed — variation #1 (Monday Coffee)
t=4.5s  Crossfade to variation #2 (Del Kar) — 600ms
t=7.5s  Crossfade to variation #3 (Barney's) — 600ms
t=10.5s Crossfade loops back to variation #1
```

### Interaction

- **Draggable wipe handle** — After initial wipe completes, a thin vertical handle remains on the image. Dragging left reveals the pre-wipe GCC-only state for comparison. Releasing snaps back to the current variation. (Desktop mouse + touch pointers.)
- **Sponsor chips below card** — Three small pill chips labeled `Monday Coffee`, `Del Kar`, `Barney's`. Clicking a chip pauses the auto-cycle and locks the post-wipe to that variation. Clicking again resumes cycling.
- **Reduced motion** — When `prefers-reduced-motion: reduce`, no wipe animation runs. The post-wipe variation #1 is shown statically. Sponsor chips still swap variations on click, without crossfade. Draggable handle remains functional.

## Sponsors + Campaigns

| Sponsor | Logo asset | Campaign slogan | Archetype |
|---|---|---|---|
| Monday Coffee | `monday-coffee.png` (red circular MC monogram) | "Monday Morning Street Cleaners" | Local small biz / coffee shop |
| Del Kar Pharmacy | `del-kar-pharmacy.png` (navy serif wordmark + green ✚) | "Healthy Blocks by Del Kar" | Neighborhood pharmacy / wellness |
| Barney's New Life | `barneys-new-life.png` (brown heart-with-leaves symbol) | "New Life in the Streets" | Mission-driven nonprofit |

Del Kar's logo is newly designed for this project (no source file provided); the Monday Coffee and Barney's marks come from user-supplied reference images. All three saved to `site/public/images/mockup-sponsors/` as transparent-background PNGs ≥ 1024×1024 for clean edit-mode reuse.

## Visual Style

**Editorial / lifestyle photography.** Warm natural light with amber + brand-green color grade. Composition leaves room in the lower-third for the overlay gradient the card applies. Medium/wide shots that de-emphasize faces to avoid AI-face uncanny valley. North Lawndale (Chicago) context: brick residential streets, mature trees, intact storefronts.

**Consistency constraints across all 40 images:**
- Same time-of-day lighting within a card's 4-image set (base + 3 sponsors)
- Same camera angle, focal length, and composition within a set
- Same subjects/talent within a set
- Only the logo treatment + campaign slogan change between the 4 images of a set

## Co-Branding Surface Per Card

Each sponsor variation places that sponsor's **logo + campaign slogan** on the designated surface. Pre-wipe shows the same surface with the GCC logo + "Cleaned by GCC" (or workflow-appropriate GCC text).

| Card | Surface (where sponsor brand appears) |
|---|---|
| worker-branding | Vest chest-patch (logo); vest back (campaign slogan) |
| equipment-branding | Cart side panel (logo + slogan) |
| street-advertising | Yard sign on the parkway: logo top, campaign as headline |
| digital-presence | Phone-in-frame showing social post with "Presented by [Sponsor]: [Campaign]" caption |
| enhanced-cleaning | Mural / wall plaque below cleaned graffiti: "[Campaign]" + logo |
| seasonal-services | Magnetic decal on salt spreader + sidewalk chalk campaign text |
| green-services | Tree-well marker plaque: campaign text + logo |
| business-engagement | Branded flyer in worker's hand, campaign as masthead |
| events | Tent banner: campaign headline + logo + "powered by GCC" |
| media | Video lower-third chyron: campaign + logo + "Presented by" |

## Image Assets

**Totals**: 10 base scenes + 30 co-branded variations = **40 image files**.

**Naming convention** in `site/public/images/upsells/`:

```
{card-slug}-base.jpg              # pre-wipe GCC-only
{card-slug}-monday-coffee.jpg     # post-wipe variation #1
{card-slug}-del-kar.jpg           # post-wipe variation #2
{card-slug}-barneys.jpg           # post-wipe variation #3
```

Card slugs: `worker-branding`, `equipment-branding`, `street-advertising`, `digital-presence`, `enhanced-cleaning`, `seasonal-services`, `green-services`, `business-engagement`, `events`, `media`.

**Format**: JPEG, 1920×1280 (3:2 landscape), quality 85. Next.js `<Image>` with `fill` + `sizes="(max-width:1024px) 100vw, 40vw"` handles responsive delivery.

**Existing SVGs** in `public/images/upsells/` are kept as fallback via the component's `onError` handler. Only `imageSrc` paths in the component call sites change.

## Generation Pipeline

Primary generator: **nanobanana MCP** (Gemini 2.5/3 Pro Image) in edit-mode, feeding the sponsor logo PNG as a reference to preserve pixel accuracy of the mark. Text-only logo prompts distort marks and must not be used.

Per card, the flow is:
1. Generate base scene with GCC-only branding (single nanobanana `generate_image` call with rich scene prompt)
2. For each of the 3 sponsors, call `edit_image` against the base scene, supplying the sponsor logo PNG + campaign slogan text, instructing placement on the designated surface.

Fallback: **ComfyUI FLUX.2 Klein 9B NVFP4** (running locally at `127.0.0.1:8188`) for any base scene where nanobanana's realism is insufficient. ComfyUI outputs are then fed into nanobanana edit-mode for the sponsor variations (FLUX doesn't do controlled logo insertion as reliably).

No Seedance locally — ruled out.

## Component Design

New component `site/src/components/sponsor/calculator/VariationShowcase.tsx`.

### Props

```ts
interface VariationShowcaseProps {
  title: string            // "Your Brand on the Frontlines"
  subtitle: string         // "Worker Branding"
  description: string
  benefits: string[]
  baseImageSrc: string     // "/images/upsells/worker-branding-base.jpg"
  variations: Variation[]  // [{slug:'monday-coffee', imageSrc, sponsorName, campaignName}, …]
  accentColor?: 'gold' | 'green' | 'brown' | 'sky'
  icon?: React.ReactNode
}

interface Variation {
  slug: 'monday-coffee' | 'del-kar' | 'barneys'
  imageSrc: string
  sponsorName: string       // displayed in chip
  campaignName: string      // for alt text + optional caption
  logoBackground: 'light' | 'dark'  // hints at gradient darken direction
}
```

### Internal state

- `wipeProgress: number` — 0 to 1, drives the clip-path of the overlay layer
- `activeVariation: 0 | 1 | 2` — index into `variations`
- `isAutoPlaying: boolean` — paused when user drags or clicks a chip
- `hasEntered: boolean` — set true on first IntersectionObserver intersection, gate for initial wipe

### DOM structure

```
<figure>
  <div class="image-wipe-container">
    <img src={baseImageSrc} class="layer-base"/>           // bottom layer
    <img src={variations[active].imageSrc}                  // top layer, clip-pathed
         class="layer-overlay"
         style={clipPath: `inset(0 ${100-wipeProgress*100}% 0 0)`}/>
    <div class="wipe-handle" onDrag…/>                      // draggable divider
    <div class="gradient-overlay"/>                          // keeps text readable
  </div>
  <div class="sponsor-chips">
    {variations.map(v => <Chip active={...} onClick={...}/>)}
  </div>
</figure>
<div class="content">{/* title, subtitle, description, benefits */}</div>
```

The image-wipe-container occupies the same `lg:w-2/5 h-48 lg:h-auto` box as today's `CategoryShowcase` image area. The content section is structurally unchanged from `CategoryShowcase`.

### Callsites to update

`CategoryShowcase` stays in the codebase — it's still used by non-upsell cards. Only the 10 upsell invocations swap to `VariationShowcase`:

- `BrandingSection.tsx` — 4 callsites (worker, equipment, street-ad, digital)
- `ServicesSection.tsx` — 4 callsites (enhanced, seasonal, green, business-engagement)
- `EventsSection.tsx` — 1 callsite (events)
- `MediaSection.tsx` — 1 callsite (media)

Each call passes `baseImageSrc` + a `variations` array of three entries. The bulk of the wiring is a per-card data map so callsites stay declarative.

## Accessibility

- `<figure>` wraps the image stack; `<figcaption>` holds the campaign+sponsor text for screen readers
- Alt text updates with the active variation: `"Worker Branding mockup — GCC cleaning crew co-branded with Monday Coffee. Campaign: Monday Morning Street Cleaners."`
- All animation paths respect `prefers-reduced-motion`
- Sponsor chips are real `<button>` elements with `aria-pressed` reflecting active state
- Draggable handle exposes keyboard controls: `←/→` arrow keys nudge the wipe 10% at a time when focused
- Color contrast: the gradient overlay ensures title/subtitle text on top of either image state maintains WCAG AA

## Risks + Mitigations

| Risk | Mitigation |
|---|---|
| Nanobanana renders distorted logos from text prompts | Always use edit-mode with logo PNG reference; never text-describe the logo |
| 40 images × full-res = page weight | Next.js `<Image>` + `sizes` + JPEG 85; lazy-load cards outside the first viewport |
| Cumulative Layout Shift on card mount | Fixed aspect-ratio container; `priority` on first visible card only |
| Wipe animation nausea on long scroll | `prefers-reduced-motion` fallback; only animates once per card (on entry) |
| Logo copyright perception (fictional but nearby to real brands) | Add a visually subtle "Mockup — not an actual sponsorship" caption on the page above the upsell stack |
| Sponsor chips feel unrelated to the card content | Small hint caption under the chip row: "Preview with different sponsor types" |
| Base scene and sponsor variations drift in composition | Always run the 3 sponsor edits from the SAME base scene output; never regenerate the base per variation |

## Out of Scope

- Swapping images on the non-upsell cards on the page (standard-tier packages, enterprise CTA, etc.)
- Back-of-house admin UI to add new mock sponsors (hardcoded in the data map for this spec)
- Video / moving sponsor demos (Seedance not available locally; deferred)
- Real sponsor onboarding (i.e. actual Monday Coffee logo becoming a real partner) — these are mockups only
- Updating the homepage, FAQ, or supporter wall
- Image optimization pass (the existing `public/images/optimized/` pipeline is separate)

## Success Criteria

1. All 10 upsell cards display an editorial-quality base scene with only GCC branding visible
2. On scroll-in, each card wipes left-to-right within 1.2s to reveal a co-branded variation
3. Each card cycles through all 3 sponsors with visible logo + campaign slogan on the designated surface
4. Sponsor chips work; draggable wipe handle works; reduced-motion path works
5. Logo on every variation is pixel-accurate (not distorted by generative blur)
6. Calculator page Lighthouse Performance score does not drop more than 5 points vs. current state
7. No regression on the other 10+ cards per tab that are not in scope here

## Open Questions

(None remaining after user approval of campaigns and mechanic on 2026-04-24)

## Changelog

- 2026-04-24: Initial design, approved by user
