'use client'

import type { GpsTrack } from '@/lib/block-completion-report'

// ─────────────────────────────────────────────────────────────────────────
// GPS track renderer — inline SVG, not a map component.
//
// Map-component constraint (Pillar 9 Task 3 brief): CombinedMap.tsx is
// P8-owned and off-limits. The other map components under
// src/components/map/ (RouteMap, BlockMap, BountyMap) are all tightly
// coupled to specific domain objects — RouteMap expects a `Route` with
// status/payoutAmount, BlockMap/BountyMap similarly — and all
// of them render through BaseMap, which pulls in maplibre-gl + a remote
// style/tile fetch. None of them accept a bare GeoJSON LineString, and
// forcing a report's gpsTrack into a fake Route/Block shape just to reuse
// the rendering pipe would be more complex than drawing the two polylines
// directly. So: a small standalone inline SVG polyline, scoped to this
// admin report page only — same normalize-into-viewbox approach as a
// sparkline. `scout` (lap 1, pre-clean) draws amber/dashed under `clean`
// (lap 2, post-clean) in solid green, on the same axes.
// ─────────────────────────────────────────────────────────────────────────

interface LineStringLike {
  type?: string
  coordinates?: unknown
}

function isLineString(value: unknown): value is { coordinates: [number, number][] } {
  if (!value || typeof value !== 'object') return false
  const candidate = value as LineStringLike
  if (!Array.isArray(candidate.coordinates)) return false
  return candidate.coordinates.every(
    (pt) => Array.isArray(pt) && pt.length >= 2 && typeof pt[0] === 'number' && typeof pt[1] === 'number'
  )
}

function extractCoords(value: unknown): [number, number][] {
  return isLineString(value) ? (value.coordinates as [number, number][]) : []
}

const WIDTH = 320
const HEIGHT = 240
const PADDING = 16

function projectPoints(
  coords: [number, number][],
  bounds: { minLng: number; maxLng: number; minLat: number; maxLat: number }
): string {
  const { minLng, maxLng, minLat, maxLat } = bounds
  const lngSpan = maxLng - minLng || 1
  const latSpan = maxLat - minLat || 1
  const innerW = WIDTH - PADDING * 2
  const innerH = HEIGHT - PADDING * 2

  return coords
    .map(([lng, lat]) => {
      const x = PADDING + ((lng - minLng) / lngSpan) * innerW
      // SVG y grows downward; latitude grows northward, so flip.
      const y = PADDING + (1 - (lat - minLat) / latSpan) * innerH
      return `${x.toFixed(1)},${y.toFixed(1)}`
    })
    .join(' ')
}

export function GpsTrackSvg({ gpsTrack }: { gpsTrack: GpsTrack | null | undefined }) {
  const scout = extractCoords(gpsTrack?.scout)
  const clean = extractCoords(gpsTrack?.clean)
  const all = [...scout, ...clean]

  if (all.length === 0) {
    return (
      <div className="flex items-center justify-center h-[240px] rounded-lg border border-neutral-800 bg-neutral-900/50 text-xs text-neutral-500">
        No GPS track recorded for this session.
      </div>
    )
  }

  const lngs = all.map((c) => c[0])
  const lats = all.map((c) => c[1])
  const bounds = {
    minLng: Math.min(...lngs),
    maxLng: Math.max(...lngs),
    minLat: Math.min(...lats),
    maxLat: Math.max(...lats),
  }

  return (
    <div className="rounded-lg border border-neutral-800 bg-neutral-900/50 p-2">
      <svg
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        width="100%"
        height={HEIGHT}
        role="img"
        aria-label="GPS track: scout lap and clean lap"
      >
        {scout.length > 1 && (
          <polyline
            points={projectPoints(scout, bounds)}
            fill="none"
            stroke="#f59e0b"
            strokeWidth={2}
            strokeDasharray="5 4"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        )}
        {clean.length > 1 && (
          <polyline
            points={projectPoints(clean, bounds)}
            fill="none"
            stroke="#22c55e"
            strokeWidth={2.5}
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        )}
        {scout.length === 0 && clean.length === 0 && null}
      </svg>
      <div className="flex gap-4 mt-2 text-[10px] text-neutral-400">
        <span className="flex items-center gap-1.5">
          <span className="inline-block w-3 border-t-2 border-dashed border-amber-500" />
          Scout lap {scout.length === 0 ? '(none)' : ''}
        </span>
        <span className="flex items-center gap-1.5">
          <span className="inline-block w-3 h-0.5 bg-green-500" />
          Clean lap {clean.length === 0 ? '(none)' : ''}
        </span>
      </div>
    </div>
  )
}
