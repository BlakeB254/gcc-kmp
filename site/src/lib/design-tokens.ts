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
  // Heatmap: broad green field -> yellow/orange activity -> red/white hot cores.
  HEATMAP_EDGE: '#2EEA52',
  HEATMAP_LOW: '#7CFC2F',
  CLEAN_ZONE: '#27D94F',
  HEATMAP_MED: '#FDE047',
  HEATMAP_HIGH: '#F97316',
  HEATMAP_EXTREME: '#DC2626',
  HEATMAP_PEAK: '#FFFFFF',
} as const

// Legacy zoom thresholds — kept for back-compat with code that still imports
// these constants. The web maps no longer use heatmap+dots crossfade; both
// NeighborhoodMapSection and CombinedMap now render colored dots only at all
// zoom levels, so the user gets a single consistent layer instead of the
// "heatmap and dots showing at the same time" overlap that the dual-layer
// approach produced after MAP_BOUNDS expanded.
export const SPOT_ZOOM = {
  HEATMAP_MIN: 8,
  HEATMAP_MAX: 15,
  HEATMAP_FADE_OUT: 14.5,
  DOTS_MIN: 10,        // still exported but no longer gates dot visibility on
                       // the simplified maps; left at 10 for any consumer
                       // that wants a sensible "close-zoom" floor.
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

export const SPOT_CLEAN_BASE_PAINT: any = {
  'fill-color': SPOT_COLORS.CLEAN_ZONE,
  'fill-opacity': [
    'interpolate', ['linear'], ['zoom'],
    7, 0.16,
    8, 0.24,
    10, 0.3,
    12, 0.27,
    13, 0.2,
    14, 0.08,
    SPOT_ZOOM.HEATMAP_FADE_OUT, 0,
  ],
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
    8, 0.08,
    10, 0.13,
    12, 0.21,
    13, 0.26,
    14, 0.16,
    SPOT_ZOOM.HEATMAP_FADE_OUT, 0.05,
    SPOT_ZOOM.HEATMAP_MAX, 0.02,
  ],
  'heatmap-color': [
    'interpolate', ['linear'], ['heatmap-density'],
    0, 'rgba(0,0,0,0)',
    0.02, 'rgba(46,234,82,0.55)',
    0.12, SPOT_COLORS.HEATMAP_EDGE,
    0.26, SPOT_COLORS.HEATMAP_LOW,
    0.45, SPOT_COLORS.HEATMAP_MED,
    0.62, SPOT_COLORS.HEATMAP_HIGH,
    0.8, SPOT_COLORS.HEATMAP_EXTREME,
    0.94, '#FFF7AD',
    1.0, SPOT_COLORS.HEATMAP_PEAK,
  ],
  'heatmap-radius': [
    'interpolate', ['linear'], ['zoom'],
    8, 18,
    10, 28,
    12, 42,
    13, 52,
    13.75, 34,
    14.25, 18,
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
