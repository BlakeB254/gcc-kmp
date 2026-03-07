'use client'

import { useState, useCallback, useMemo } from 'react'
import { Source, Layer, Marker, Popup } from 'react-map-gl/maplibre'
import type { MapLayerMouseEvent } from 'react-map-gl/maplibre'
import { Camera } from 'lucide-react'
import { CHICAGO_CENTER_LNG_LAT, ZOOM, ROUTE_WIDTHS, ROUTE_STATUS_COLORS, CHECKPOINT_COLORS, CHECKPOINT_BORDER_COLORS } from '@/lib/design-tokens'
import { BaseMap } from './BaseMap'

/**
 * RouteMap Component
 *
 * Displays cleaning routes using "cased line" technique for high visibility.
 * Cased lines have a darker outline (case) with a brighter inner line (fill).
 *
 * Route Status Colors:
 * - published: Green - available for claiming
 * - claimed: Amber - someone is working on it
 * - active: Blue - currently in progress
 * - completed: Gray - finished
 */

// ============================================================================
// ROUTE COLORS - CASED LINE STYLING (from design tokens)
// ============================================================================

const ROUTE_COLORS = {
  published: { fill: ROUTE_STATUS_COLORS.published.line, case: ROUTE_STATUS_COLORS.published.casing },
  claimed: { fill: ROUTE_STATUS_COLORS.claimed.line, case: ROUTE_STATUS_COLORS.claimed.casing },
  active: { fill: ROUTE_STATUS_COLORS.active.line, case: ROUTE_STATUS_COLORS.active.casing },
  completed: { fill: ROUTE_STATUS_COLORS.completed.line, case: ROUTE_STATUS_COLORS.completed.casing },
} as const

const ROUTE_LINE_WIDTHS = {
  case: ROUTE_WIDTHS.CASING,
  fill: ROUTE_WIDTHS.CORE,
  caseSelected: ROUTE_WIDTHS.GLOW,
  fillSelected: ROUTE_WIDTHS.CASING,
} as const

const STATUS_LABELS: Record<RouteDisplayStatus, string> = {
  published: 'Available',
  claimed: 'Claimed',
  active: 'In Progress',
  completed: 'Completed',
}

// ============================================================================
// TYPES
// ============================================================================

type RouteDisplayStatus = 'published' | 'claimed' | 'active' | 'completed'

interface Checkpoint {
  name: string
  sequenceOrder: number
  coordinates: [number, number]
  photoRequired: boolean
  photoInstructions?: string
  geofenceRadius: number
  type: 'route_start' | 'photo_point' | 'alley_entrance' | 'route_end'
}

interface Route {
  id: number
  code: string
  displayName: string
  geometry: {
    type: 'LineString'
    coordinates: [number, number][]
  }
  lengthMeters?: number
  estimatedMinutes?: number
  payoutAmount: number
  status: RouteDisplayStatus
  checkpoints: Checkpoint[]
}

interface RouteMapProps {
  routes: Route[]
  onRouteSelect?: (route: Route) => void
  selectedRouteId?: number
  interactive?: boolean
  height?: string
  showCheckpoints?: boolean
  showLegend?: boolean
  center?: [number, number]
  zoom?: number
}

// ============================================================================
// MAP DEFAULTS
// ============================================================================

// North Lawndale default center (from design tokens)
const DEFAULT_CENTER: [number, number] = CHICAGO_CENTER_LNG_LAT
const DEFAULT_ZOOM = ZOOM.ROUTE

// ============================================================================
// COMPONENT
// ============================================================================

export function RouteMap({
  routes,
  onRouteSelect,
  selectedRouteId,
  interactive = true,
  height = '400px',
  showCheckpoints = true,
  showLegend = true,
  center = DEFAULT_CENTER,
  zoom = DEFAULT_ZOOM,
}: RouteMapProps) {
  const [popupInfo, setPopupInfo] = useState<Route | null>(null)

  // Convert routes to GeoJSON FeatureCollection
  const routeGeoJson = useMemo(() => ({
    type: 'FeatureCollection' as const,
    features: routes.map((route) => ({
      type: 'Feature' as const,
      id: route.id,
      properties: {
        id: route.id,
        code: route.code,
        displayName: route.displayName,
        status: route.status,
        payoutAmount: route.payoutAmount,
        isSelected: route.id === selectedRouteId,
      },
      geometry: route.geometry,
    })),
  }), [routes, selectedRouteId])

  // Get all checkpoints from routes
  const allCheckpoints = useMemo(() => {
    return routes.flatMap((route) =>
      route.checkpoints.map((cp) => ({
        ...cp,
        routeId: route.id,
        routeStatus: route.status,
      }))
    )
  }, [routes])

  // Handle route click
  const onClick = useCallback((event: MapLayerMouseEvent) => {
    const feature = event.features?.[0]
    if (feature && feature.properties) {
      const routeId = feature.properties.id
      const route = routes.find((r) => r.id === routeId)
      if (route) {
        setPopupInfo(route)
        onRouteSelect?.(route)
      }
    }
  }, [routes, onRouteSelect])

  const initialViewState = {
    longitude: center[0],
    latitude: center[1],
    zoom,
  }

  return (
    <BaseMap
      height={height}
      initialViewState={initialViewState}
      interactiveLayerIds={interactive ? ['route-fill-layer'] : []}
      onClick={interactive ? onClick : undefined}
      cursor={interactive ? 'pointer' : 'default'}
      legend={
        showLegend ? (
          <div className="mt-4 flex flex-wrap gap-4 justify-center text-xs">
            {(Object.entries(STATUS_LABELS) as [RouteDisplayStatus, string][]).map(
              ([status, label]) => (
                <div key={status} className="flex items-center gap-1.5">
                  <span
                    className="w-6 h-1 rounded-full"
                    style={{ backgroundColor: ROUTE_COLORS[status].fill }}
                  />
                  <span className="text-neutral-600">{label}</span>
                </div>
              )
            )}
          </div>
        ) : undefined
      }
      popup={
        popupInfo ? (
          <Popup
            longitude={popupInfo.geometry.coordinates[0][0]}
            latitude={popupInfo.geometry.coordinates[0][1]}
            anchor="bottom"
            onClose={() => setPopupInfo(null)}
            closeButton={true}
          >
            <div className="p-3 min-w-[220px]">
              <div className="flex items-center justify-between mb-2">
                <span className="font-bold text-neutral-900">
                  {popupInfo.displayName}
                </span>
                <span
                  className="text-xs px-2 py-0.5 rounded-full text-white font-medium"
                  style={{
                    backgroundColor: ROUTE_COLORS[popupInfo.status].fill,
                  }}
                >
                  {STATUS_LABELS[popupInfo.status]}
                </span>
              </div>
              <p className="text-sm text-neutral-600 mb-1">{popupInfo.code}</p>
              <div className="flex items-center gap-4 text-xs text-neutral-500 mb-2">
                {popupInfo.lengthMeters && (
                  <span>
                    {(popupInfo.lengthMeters / 1000).toFixed(1)} km
                  </span>
                )}
                {popupInfo.estimatedMinutes && (
                  <span>{popupInfo.estimatedMinutes} min</span>
                )}
                <span>{popupInfo.checkpoints.length} checkpoints</span>
              </div>
              <div className="flex items-center justify-between mt-3 pt-3 border-t">
                <span className="font-semibold text-green-600">
                  ${(popupInfo.payoutAmount / 100).toFixed(0)}
                </span>
                {popupInfo.status === 'published' && onRouteSelect && (
                  <button
                    onClick={() => onRouteSelect(popupInfo)}
                    className="bg-green-500 hover:bg-green-600 text-white text-sm py-1.5 px-4 rounded-lg font-medium transition-colors"
                  >
                    Claim Route
                  </button>
                )}
              </div>
            </div>
          </Popup>
        ) : undefined
      }
    >
      {/* ============================================================ */}
      {/* ROUTE LINES - Cased Line Technique                          */}
      {/* ============================================================ */}

      <Source id="routes" type="geojson" data={routeGeoJson}>
        {/* Route case (shadow/outline) - rendered first, below fill */}
        <Layer
          id="route-case-layer"
          type="line"
          paint={{
            'line-color': [
              'match',
              ['get', 'status'],
              'published', ROUTE_COLORS.published.case,
              'claimed', ROUTE_COLORS.claimed.case,
              'active', ROUTE_COLORS.active.case,
              'completed', ROUTE_COLORS.completed.case,
              ROUTE_COLORS.published.case,
            ],
            'line-width': [
              'case',
              ['==', ['get', 'isSelected'], true],
              ROUTE_LINE_WIDTHS.caseSelected,
              ROUTE_LINE_WIDTHS.case,
            ],
            'line-opacity': 0.8,
          }}
        />

        {/* Route fill (bright inner line) - rendered on top */}
        <Layer
          id="route-fill-layer"
          type="line"
          layout={{
            'line-cap': 'round',
            'line-join': 'round',
          }}
          paint={{
            'line-color': [
              'match',
              ['get', 'status'],
              'published', ROUTE_COLORS.published.fill,
              'claimed', ROUTE_COLORS.claimed.fill,
              'active', ROUTE_COLORS.active.fill,
              'completed', ROUTE_COLORS.completed.fill,
              ROUTE_COLORS.published.fill,
            ],
            'line-width': [
              'case',
              ['==', ['get', 'isSelected'], true],
              ROUTE_LINE_WIDTHS.fillSelected,
              ROUTE_LINE_WIDTHS.fill,
            ],
          }}
        />

        {/* Route labels - show display name instead of code */}
        <Layer
          id="route-label-layer"
          type="symbol"
          layout={{
            'symbol-placement': 'line-center',
            'text-field': ['get', 'displayName'],
            'text-size': 11,
            'text-font': ['Open Sans Bold', 'Arial Unicode MS Bold'],
            'text-max-width': 12,
          }}
          paint={{
            'text-color': '#ffffff',
            'text-halo-color': [
              'match',
              ['get', 'status'],
              'published', ROUTE_COLORS.published.case,
              'claimed', ROUTE_COLORS.claimed.case,
              'active', ROUTE_COLORS.active.case,
              'completed', ROUTE_COLORS.completed.case,
              ROUTE_COLORS.published.case,
            ],
            'text-halo-width': 2,
          }}
        />
      </Source>

      {/* ============================================================ */}
      {/* CHECKPOINT MARKERS                                          */}
      {/* ============================================================ */}

      {showCheckpoints &&
        allCheckpoints.map((cp, idx) => (
          <Marker
            key={`cp-${cp.routeId}-${idx}`}
            longitude={cp.coordinates[0]}
            latitude={cp.coordinates[1]}
            anchor="center"
          >
            <div
              className="flex items-center justify-center rounded-full border-2 shadow-sm"
              style={
                cp.type === 'route_start'
                  ? { width: 28, height: 28, backgroundColor: CHECKPOINT_COLORS.START, borderColor: CHECKPOINT_BORDER_COLORS.start }
                  : cp.type === 'route_end'
                    ? { width: 28, height: 28, backgroundColor: CHECKPOINT_COLORS.END, borderColor: CHECKPOINT_BORDER_COLORS.end }
                    : { width: 24, height: 24, backgroundColor: CHECKPOINT_COLORS.DEFAULT, borderColor: CHECKPOINT_BORDER_COLORS.default }
              }
            >
              {cp.photoRequired ? (
                <Camera
                  className={`w-3 h-3 ${
                    cp.type === 'route_start' || cp.type === 'route_end'
                      ? 'text-white'
                      : 'text-slate-600'
                  }`}
                />
              ) : (
                <span className="text-xs font-bold text-slate-600">
                  {cp.sequenceOrder + 1}
                </span>
              )}
            </div>
          </Marker>
        ))}
    </BaseMap>
  )
}

// Re-export types
export type { Route, Checkpoint, RouteDisplayStatus, RouteMapProps }
