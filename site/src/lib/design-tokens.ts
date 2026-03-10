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
  verified: '#22C55E',
  acknowledged: '#3B82F6',
  pending: '#F59E0B',
} as const

export const SPOT_COLORS = {
  MARKER: '#F97316',
  RADIUS_FILL: 'rgba(249, 115, 22, 0.15)',
  RADIUS_STROKE: '#F97316',
  // Heatmap color ramp (low density → high density): light pink → deep red
  HEATMAP_LOW: '#FEE2E2',     // light pink
  HEATMAP_MED: '#FCA5A5',     // medium pink
  HEATMAP_HIGH: '#EF4444',    // red
  HEATMAP_PEAK: '#991B1B',    // deep red
} as const

// Zoom thresholds for heatmap vs individual dot transition
export const SPOT_ZOOM = {
  HEATMAP_MIN: 8,      // heatmap appears
  HEATMAP_MAX: 14,     // heatmap fades out
  DOTS_MIN: 13,        // individual dots appear
} as const

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
