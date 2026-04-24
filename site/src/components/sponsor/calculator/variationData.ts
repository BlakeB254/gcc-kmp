/**
 * Single source of truth for the 10 upsell cards' co-branded variation mockups.
 * Consumed by the VariationShowcase component and by scripts/generate-upsell-images.ts.
 *
 * Related spec: docs/superpowers/specs/2026-04-24-sponsor-upsell-cobranded-variations-design.md
 * (see "Revision 1" for the 4-sponsor + info-panel expansion)
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

export type SponsorSlug = 'monday-coffee' | 'del-kar' | 'barneys' | 'bpp-cubs'

export interface BrandColors {
  /** Tailwind-friendly background class (solid or gradient-from) */
  bg: string
  /** Text color class on top of bg */
  text: string
  /** Accent color class for chip, dividers, slogan underline */
  accent: string
}

export interface Sponsor {
  slug: SponsorSlug | 'gcc'
  name: string
  tagline: string
  campaign: string
  logoPath: string
  address: string
  websiteUrl: string
  instagramHandle?: string
  brandColors: BrandColors
}

/**
 * GCC baseline — rendered as the pre-wipe sponsor info panel content.
 * Same shape as a Sponsor so the panel renders from a single data type.
 */
export const GCC_BASELINE: Sponsor = {
  slug: 'gcc',
  name: 'Global Community Cleaners',
  tagline: 'Paid block cleaning by North Lawndale residents',
  campaign: 'GCC Community Partnership',
  logoPath: '/images/gcc-logo.png',
  address: '1512 S. Millard Ave, Chicago, IL 60623',
  websiteUrl: 'globalcommunitycleaners.org',
  instagramHandle: '@gcc_cleaners',
  brandColors: {
    bg: 'bg-brand-green-700',
    text: 'text-white',
    accent: 'bg-brand-green-400',
  },
}

export const SPONSORS: Record<SponsorSlug, Sponsor> = {
  'monday-coffee': {
    slug: 'monday-coffee',
    name: 'Monday Coffee',
    tagline: 'Small-batch coffee roaster on Pulaski Rd.',
    campaign: 'Monday Morning Street Cleaners',
    logoPath: '/images/mockup-sponsors/monday-coffee.png',
    address: '3511 W. Pulaski Rd, Chicago, IL 60624',
    websiteUrl: 'mondaycoffee.example',
    instagramHandle: '@mondaycoffee.co',
    brandColors: {
      bg: 'bg-[#E24A42]',
      text: 'text-white',
      accent: 'bg-[#F4B400]',
    },
  },
  'del-kar': {
    slug: 'del-kar',
    name: 'Del Kar Pharmacy',
    tagline: 'Your neighborhood pharmacy since 1987',
    campaign: 'Healthy Blocks by Del Kar',
    logoPath: '/images/mockup-sponsors/del-kar-pharmacy.png',
    address: '1442 S. Ogden Ave, Chicago, IL 60608',
    websiteUrl: 'delkarpharmacy.example',
    instagramHandle: '@delkarrx',
    brandColors: {
      bg: 'bg-[#1E3A8A]',
      text: 'text-white',
      accent: 'bg-[#15803D]',
    },
  },
  barneys: {
    slug: 'barneys',
    name: "Barney's New Life",
    tagline: 'Organic grocery + community health foods',
    campaign: 'New Life in the Streets',
    logoPath: '/images/mockup-sponsors/barneys-new-life.png',
    address: '2812 W. Madison St, Chicago, IL 60612',
    websiteUrl: 'barneysnewlife.example',
    instagramHandle: '@barneysnewlife',
    brandColors: {
      bg: 'bg-[#8B5A2B]',
      text: 'text-white',
      accent: 'bg-[#A7C957]',
    },
  },
  'bpp-cubs': {
    slug: 'bpp-cubs',
    name: 'Black Panther Party Cubs',
    tagline: 'Youth empowerment + mentorship program',
    campaign: 'Cubs Clean the Block',
    logoPath: '/images/mockup-sponsors/bpp-cubs.png',
    address: '1601 S. Christiana Ave, Chicago, IL 60623',
    websiteUrl: 'bppcubs.example',
    instagramHandle: '@bpp.cubs',
    brandColors: {
      bg: 'bg-[#0A0A0A]',
      text: 'text-[#F4B400]',
      accent: 'bg-[#F4B400]',
    },
  },
}

export const SPONSOR_ORDER: SponsorSlug[] = [
  'monday-coffee',
  'del-kar',
  'barneys',
  'bpp-cubs',
]

export interface CardPrompt {
  slug: CardSlug
  /** Base scene prompt — generates the GCC-only pre-wipe image */
  basePrompt: string
  /** Surface description fed into the edit-mode sponsor variations */
  surface: string
  /** Camera/lens hint shared by all 4 images in the set */
  shotHint: string
}

export function baseImageSrcFor(slug: CardSlug): string {
  return `/images/upsells/${slug}-base.jpg`
}

export function variationImageSrcsFor(slug: CardSlug): Record<SponsorSlug, string> {
  return {
    'monday-coffee': `/images/upsells/${slug}-monday-coffee.jpg`,
    'del-kar': `/images/upsells/${slug}-del-kar.jpg`,
    barneys: `/images/upsells/${slug}-barneys.jpg`,
    'bpp-cubs': `/images/upsells/${slug}-bpp-cubs.jpg`,
  }
}

/**
 * A square (rw == rh) fractional region of the card image to show as a
 * picture-in-picture zoom, for cards where the sponsor branding lands on
 * a small surface that's hard to read at thumbnail scale (chest patch,
 * tree-well marker, spreader decal, etc).
 *
 * Cards where the branding is already a clear hero (yard sign, banner,
 * chyron, flyer) omit the entry — no PiP renders.
 */
export interface ZoomRegion {
  /** X of top-left, 0..1 as fraction of image width */
  x: number
  /** Y of top-left, 0..1 as fraction of image height */
  y: number
  /** Square size (same width and height), 0..1 as fraction of image width */
  size: number
  /** Short label rendered under the PiP */
  label: string
}

/**
 * No PiP zoom on any card — every card's base image is composed tightly
 * enough that the co-branding surface reads at card-thumbnail scale without
 * a magnified inset. Kept as a typed empty map so the rendering code path
 * still works if a future card wants to opt in.
 */
export const ZOOM_REGIONS: Partial<Record<CardSlug, ZoomRegion>> = {}

export function zoomRegionFor(slug: CardSlug): ZoomRegion | undefined {
  return ZOOM_REGIONS[slug]
}

export const CARD_PROMPTS: Record<CardSlug, CardPrompt> = {
  'worker-branding': {
    slug: 'worker-branding',
    basePrompt:
      "Close-in documentary editorial photograph shot DIRECTLY BEHIND a Black community worker, the camera positioned about 4 feet behind the subject's shoulder blades. The back of a bright kelly-green high-visibility work vest DOMINATES the composition, filling approximately 70% of the frame area, with the top edge of the vest collar at roughly 15% from the top and the vest bottom hem at 95% from the top. The vest back is clean and flat, displaying a LARGE bold white 'GCC' wordmark centered between the shoulder blades (the letters should be massive, roughly 22% of the frame height tall, perfectly readable from across a street), plus the tagline 'GLOBAL COMMUNITY CLEANERS' in clear white uppercase text arched below it. Two horizontal reflective silver safety strips cross the vest. Only the worker's beanie hat and the top of their shoulders and the crook of their arms are visible — no face, no head turn. A wooden broom handle angles down and forward out of frame. Soft-focus brick North Lawndale Chicago sidewalk and faint tree bokeh fill the narrow strip around the vest. Warm late-morning natural light, amber + green color grade, shallow depth of field so the vest text is tack sharp. 3:2 landscape. Medium-close shot, 85mm lens compression.",
    surface:
      "Keep the EXACT same tight behind-the-subject composition and lighting. Modify ONLY the back of the green vest: keep a small centered 'GCC' wordmark at the top of the vest back (about 30% smaller than the base image), place the sponsor's official logo prominently in the center of the vest back between the shoulder blades (large enough to be instantly legible, roughly 30% of the frame width), and set the sponsor's campaign slogan in bold white uppercase text arched across the lower back of the vest above the bottom hem. The sponsor logo + slogan must be the primary visuals on the vest back, perfectly readable at card-thumbnail scale. Do not change the worker, broom, background, or lighting.",
    shotHint: 'Tight behind-the-subject medium-close, 85mm lens, shallow DOF',
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
