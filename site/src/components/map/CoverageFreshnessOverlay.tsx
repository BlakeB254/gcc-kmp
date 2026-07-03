'use client'

import { useEffect, useRef, useState } from 'react'
import { Source, Layer } from 'react-map-gl/maplibre'
import { enumerateCellsInBounds, cellCenter } from '@/lib/grid-cell-client'
import { METERS_PER_DEG_LAT, METERS_PER_DEG_LNG } from '@/lib/geo-utils'
import { SPOT_ZOOM } from '@/lib/design-tokens'

const STALE_COLOR = '#d97706' // amber-600
const NEVER_SCANNED_COLOR = '#9ca3af' // grey-400
const FETCH_DEBOUNCE_MS = 400

interface Bounds {
  west: number
  south: number
  east: number
  north: number
}

interface CoverageFreshnessOverlayProps {
  bounds: Bounds
  zoom: number
  visible: boolean
}

interface CoverageCell {
  locationCellId: string
  lat: number
  lng: number
  state: 'fresh' | 'stale'
}

/**
 * Renders never-scanned (grey) and stale (amber) cells as small filled
 * squares over the map, restricted to close zoom (>= SPOT_ZOOM.DOTS_MIN) and
 * gated by the `visible` toggle — deliberately never a wide-zoom wash, per
 * design spec §4.2's explicit lesson from this team's reverted full-earth
 * tint regression. Fresh cells and areas outside any coverage data render
 * nothing, letting the existing heatmap/dot layers show through unchanged.
 */
export function CoverageFreshnessOverlay({ bounds, zoom, visible }: CoverageFreshnessOverlayProps) {
  const [scannedCells, setScannedCells] = useState<CoverageCell[]>([])
  const shouldRender = visible && zoom >= SPOT_ZOOM.DOTS_MIN
  const debounceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    if (!shouldRender) return
    let cancelled = false

    // Debounce: bounds changes on every onMove frame during drag/pan, so
    // only fetch once bounds have been stable for FETCH_DEBOUNCE_MS —
    // otherwise a single pan gesture floods /api/map/coverage (and the
    // Neon query behind it) with dozens of requests per second.
    debounceTimerRef.current = setTimeout(() => {
      const bboxParam = `${bounds.west},${bounds.south},${bounds.east},${bounds.north}`
      fetch(`/api/map/coverage?bbox=${bboxParam}`)
        .then((res) => res.json())
        .then((data) => {
          if (!cancelled && data.success) setScannedCells(data.cells)
        })
        .catch(() => {
          if (!cancelled) setScannedCells([])
        })
    }, FETCH_DEBOUNCE_MS)

    return () => {
      cancelled = true
      if (debounceTimerRef.current !== null) {
        clearTimeout(debounceTimerRef.current)
        debounceTimerRef.current = null
      }
    }
  }, [shouldRender, bounds.west, bounds.south, bounds.east, bounds.north])

  if (!shouldRender) return null

  const scannedCellIds = new Set(scannedCells.map((c) => c.locationCellId))
  const allCellIds = enumerateCellsInBounds(bounds)

  const features = [
    // Never-scanned cells: anything enumerated but not returned by the API
    ...allCellIds
      .filter((id) => !scannedCellIds.has(id))
      .map((id) => cellToFeature(id, 'never')),
    // Stale cells: returned by the API with state === 'stale'
    ...scannedCells
      .filter((c) => c.state === 'stale')
      .map((c) => cellToFeature(c.locationCellId, 'stale')),
  ]

  const geojson = {
    type: 'FeatureCollection' as const,
    features,
  }

  return (
    <Source id="coverage-freshness" type="geojson" data={geojson}>
      <Layer
        id="coverage-freshness-fill"
        type="fill"
        paint={{
          'fill-color': ['match', ['get', 'freshness'], 'never', NEVER_SCANNED_COLOR, 'stale', STALE_COLOR, 'transparent'],
          'fill-opacity': ['match', ['get', 'freshness'], 'never', 0.35, 'stale', 0.3, 0],
        }}
      />
    </Source>
  )
}

function cellToFeature(cellId: string, freshness: 'never' | 'stale') {
  const { lat, lng } = cellCenter(cellId)
  const cellSizeMeters = 10
  const halfLatDeg = (cellSizeMeters / METERS_PER_DEG_LAT) / 2
  const halfLngDeg = (cellSizeMeters / METERS_PER_DEG_LNG) / 2
  return {
    type: 'Feature' as const,
    properties: { freshness },
    geometry: {
      type: 'Polygon' as const,
      coordinates: [[
        [lng - halfLngDeg, lat - halfLatDeg],
        [lng + halfLngDeg, lat - halfLatDeg],
        [lng + halfLngDeg, lat + halfLatDeg],
        [lng - halfLngDeg, lat + halfLatDeg],
        [lng - halfLngDeg, lat - halfLatDeg],
      ]],
    },
  }
}
