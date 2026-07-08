/**
 * GCC Design Tokens — shared visual constants matching mobile app (MapStyles.kt)
 * and admin app (AdminTheme.kt). Keep in sync across all platforms.
 */

export const MAP_STYLE_URL = 'https://tiles.openfreemap.org/styles/liberty'

export const CHICAGO_CENTER = { lat: 41.8603, lng: -87.7228 } as const
export const CHICAGO_CENTER_LNG_LAT: [number, number] = [-87.7228, 41.8603]

export const ZOOM = {
  CITY: 11.0,
  SERVICE_AREA: 13.2,
  NEIGHBORHOOD: 14.0,
  ROUTE: 15.0,
  ACTIVE_JOB: 17.0,
  STREET: 19.0,
} as const

export const ROUTE_COLORS = {
  LINE: '#22C55E',
  CASING: '#14532D',
  GLOW: '#22C55E',
  COMPLETED: '#6B7280',
} as const

export const ROUTE_WIDTHS = {
  GLOW: 14,
  CASING: 8,
  CORE: 5,
} as const

export const ROUTE_STATUS_COLORS = {
  published: { line: '#22C55E', casing: '#14532D' },
  claimed: { line: '#F59E0B', casing: '#78350F' },
  active: { line: '#3B82F6', casing: '#1E3A8A' },
  completed: { line: '#6B7280', casing: '#1F2937' },
} as const

export const CHECKPOINT_COLORS = {
  START: '#16A34A',
  END: '#F97316',
  PHOTO: '#FFFFFF',
  DEFAULT: '#FFFFFF',
} as const

export const BOUNTY_COLORS = {
  OPEN: '#EF4444',
  IN_PROGRESS: '#3B82F6',
  COMPLETED: '#22C55E',
  PULSE: '#F97316',
  RADIUS_FILL: 'rgba(239, 68, 68, 0.1)',
} as const

export const SPOT_STATUS_COLORS: Record<string, string> = {
  pending: '#F59E0B',
  verified: '#EF4444',
  acknowledged: '#3B82F6',
  resolved: '#22C55E',
} as const

export const SPOT_COLORS = {
  MARKER: '#F97316',
  RADIUS_FILL: 'rgba(249, 115, 22, 0.15)',
  RADIUS_STROKE: '#F97316',
  HEATMAP_PEAK: '#FFFFFF',
} as const

// Per-item severity coloring for the close-zoom marker layer (Task 10) — LOW
// reuses SPOT_COLORS.MARKER so the common case is visually unchanged; MEDIUM/
// HIGH step through the same amber/red vocabulary already used for heat-weight
// (getSpotHeatWeight) and bounty urgency (BOUNTY_COLORS.OPEN) elsewhere here.
export const SPOT_SEVERITY_COLORS = {
  LOW: SPOT_COLORS.MARKER,
  MEDIUM: '#F59E0B',
  HIGH: '#EF4444',
} as const

// Vegetation-domain observations (trees/bushes/etc, auto-registered from a
// scan's landmark pass) render distinctly from litter — informational, not
// an urgency signal, so it gets the brand green rather than the litter ramp.
export const VEGETATION_MARKER_COLOR = '#16763A'

// Zoom thresholds for the heatmap↔dots crossfade. Heatmap owns z<14.5
// (intensity ramping up then fading out by 14.5). Dots fade in over 14→15
// via opacity (0→0.8→1). The map's minZoom=14 + fitBounds maxZoom=15
// settling means users only ever see this in the "dots fully visible,
// heatmap effectively invisible" portion of the crossfade — they don't
// experience the two-layers-at-once overlap.
export const SPOT_ZOOM = {
  HEATMAP_MIN: 8,
  HEATMAP_MAX: 15,
  HEATMAP_FADE_OUT: 14.5,
  DOTS_MIN: 14,
} as const

// Litter-footprint layer (Pillar 8 Task 2/3): the close-zoom, true-scale,
// per-observation shapes that replace the removed spot pin/glyph/
// verification-ring layers — web mirror of the mobile footprint layer in
// composeApp/.../GccMap.ios.kt. Domain-tinted per the mobile palette;
// "color carries noticeability, size carries truth" (spec).
export const LITTER_FOOTPRINT_COLORS = {
  vegetation: '#16A34A',
  construction: '#6B7280',
  other: '#F97316', // litter/unspecified — same hex as SPOT_COLORS.MARKER
} as const

// Fade-in band: starts at SPOT_ZOOM.HEATMAP_FADE_OUT (the web heatmap's own
// fade-OUT zoom, 14.5) so the two layers hand off with zero dead band by
// construction, same trick mobile uses anchoring its fade-in start to
// IOS_SPOT_HEAT_FADE_ZOOM. Reaches full opacity at z15.5 — matches mobile's
// footprintFadeInFullZoom exactly (not heat-fade-zoom-dependent; the shapes
// need to be legible at the same zoom on both platforms).
export const LITTER_FOOTPRINT_ZOOM = {
  FADE_IN_START: SPOT_ZOOM.HEATMAP_FADE_OUT,
  FADE_IN_FULL: 15.5,
} as const

export type SpotHeatWeightInput = {
  status?: string | null
  bountyId?: string | number | null
  amountCents?: number | null
  observerRewardPoints?: number | null
  radiusMeters?: number | null
  affectedRadiusMeters?: number | null
  needsVerification?: boolean | number | null
}

export function getSpotHeatWeight(spot: SpotHeatWeightInput): number {
  const statusWeight =
    spot.status === 'verified' ? 1.45 :
    spot.status === 'acknowledged' ? 1.25 :
    spot.status === 'pending' || !spot.status ? 1.0 :
    0.8

  const bountyBoost = spot.bountyId != null || (spot.amountCents ?? 0) > 0 ? 0.4 : 0
  const verificationBoost = spot.needsVerification === true || spot.needsVerification === 1 ? 0.25 : 0
  const rewardBoost = Math.min(Math.max(spot.observerRewardPoints ?? 0, 0), 25) / 100
  const radiusMeters = spot.radiusMeters ?? spot.affectedRadiusMeters ?? 15
  const radiusBoost = Math.min(Math.max((radiusMeters - 20) / 180, 0), 0.35)

  return Math.min(Math.max(statusWeight + bountyBoost + verificationBoost + rewardBoost + radiusBoost, 0.65), 2.4)
}

export const SPOT_HEATMAP_PAINT: any = {
  'heatmap-weight': [
    'interpolate', ['linear'], ['to-number', ['coalesce', ['get', 'heatWeight'], 1]],
    0, 0.6,
    1, 1,
    2.4, 2.4,
  ],
  'heatmap-intensity': [
    'interpolate', ['linear'], ['zoom'],
    8, 0.05,
    10, 0.08,
    12, 0.13,
    13, 0.16,
    14, 0.10,
    SPOT_ZOOM.HEATMAP_FADE_OUT, 0.03,
    SPOT_ZOOM.HEATMAP_MAX, 0.02,
  ],
  // Doppler-radar ramp — GCC's own colors (green → yellow → orange → red → white).
  // Apple-precipitation *style* (soft feathered blooms on a muted base), our hues.
  'heatmap-color': [
    'interpolate', ['linear'], ['heatmap-density'],
    0,    'rgba(0,0,0,0)',
    0.05, 'rgba(46,234,82,0.5)',    // green — Light
    0.16, '#2EEA52',                // green — Light
    0.32, '#7CFC2F',                // lime-green — Light
    0.46, '#FDE047',                // yellow — Moderate
    0.60, '#F59E0B',                // amber — Moderate
    0.72, '#F97316',                // orange — Heavy
    0.83, '#EF4444',                // red — Heavy
    0.93, '#DC2626',                // red — Heavy
    1.0,  '#B91C1C',                // deep-red core — Extreme (no white blowout)
  ],
  // Smaller radius at low zoom keeps concentrated clusters from melting into a
  // perfect-circle bullseye; grows with zoom where points spread out on screen.
  'heatmap-radius': [
    'interpolate', ['linear'], ['zoom'],
    8, 9,
    10, 16,
    12, 34,
    13, 50,
    13.75, 40,
    14.25, 22,
    SPOT_ZOOM.HEATMAP_FADE_OUT, 0,
    SPOT_ZOOM.HEATMAP_MAX, 0,
  ],
  'heatmap-opacity': [
    'interpolate', ['linear'], ['zoom'],
    8, 0.74,
    10, 0.82,
    12, 0.86,
    13, 0.82,
    13.75, 0.54,
    14.25, 0.22,
    SPOT_ZOOM.HEATMAP_FADE_OUT, 0,
  ],
}

// Neutral scrim drawn beneath the heatmap so weather colors pop (Apple precipitation feel).
export const SPOT_SCRIM_PAINT: any = {
  'fill-color': '#F5F6F8',
  'fill-opacity': [
    'interpolate', ['linear'], ['zoom'],
    8, 0.55,
    13, 0.5,
    14, 0.2,
    SPOT_ZOOM.HEATMAP_FADE_OUT, 0,
  ],
}

export const COMMUNITY_AREA_COLORS = {
  FILL: 'rgba(239, 68, 68, 0.08)',
  STROKE: '#EF4444',
  STROKE_WIDTH: 2,
} as const

export const BLOCK_STATUS_COLORS = {
  unfunded: { fill: '#6B7280', opacity: 0.2 },
  available: { fill: '#22C55E', opacity: 0.5 },
  claimed: { fill: '#F59E0B', opacity: 0.6 },
  in_progress: { fill: '#3B82F6', opacity: 0.7 },
  pending_review: { fill: '#8B5CF6', opacity: 0.6 },
  completed: { fill: '#10B981', opacity: 0.4 },
} as const

export const STATUS_COLORS = {
  draft: { bg: '#374151', text: '#9CA3AF' },
  published: { bg: '#065F46', text: '#34D399' },
  archived: { bg: '#78350F', text: '#FBBF24' },
  open: { bg: '#7F1D1D', text: '#FCA5A5' },
  in_progress: { bg: '#1E3A8A', text: '#93C5FD' },
  completed: { bg: '#065F46', text: '#6EE7B7' },
} as const

export const BOUNTY_MINIMUM_CENTS = 1000

// Checkpoint border colors (used in RouteMap.tsx)
export const CHECKPOINT_BORDER_COLORS = {
  start: '#15803d',
  end: '#c2410c',
  default: '#475569',
} as const

// Social brand colors (used in ShareButtons.tsx)
export const SOCIAL_COLORS = {
  facebook: '#1877F2',
  linkedin: '#0A66C2',
  twitter: '#000000',
} as const
