'use client'

import { useState, useCallback, useMemo } from 'react'
import Map, { Source, Layer, Marker, Popup, NavigationControl, ScaleControl } from 'react-map-gl/maplibre'
import type { MapLayerMouseEvent } from 'react-map-gl/maplibre'
import 'maplibre-gl/dist/maplibre-gl.css'
import { Camera } from 'lucide-react'

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
// ROUTE COLORS - CASED LINE STYLING
// ============================================================================

const ROUTE_COLORS = {
  published: { fill: '#22c55e', case: '#14532d' },  // Green
  claimed: { fill: '#f59e0b', case: '#78350f' },    // Amber
  active: { fill: '#3b82f6', case: '#1e3a8a' },     // Blue
  completed: { fill: '#6b7280', case: '#1f2937' },  // Gray
} as const

const ROUTE_LINE_WIDTHS = {
  case: 6,
  fill: 4,
  caseSelected: 8,
  fillSelected: 5,
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
// MAP STYLE
// ============================================================================

const MAP_STYLE = 'https://basemaps.cartocdn.com/gl/positron-gl-style/style.json'

// North Lawndale default center
const DEFAULT_CENTER: [number, number] = [-87.7158, 41.8666]
const DEFAULT_ZOOM = 15

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
    <div style={{ height, width: '100%' }} className="rounded-xl overflow-hidden relative">
      <Map
        initialViewState={initialViewState}
        mapStyle={MAP_STYLE}
        interactiveLayerIds={interactive ? ['route-fill-layer'] : []}
        onClick={interactive ? onClick : undefined}
        cursor={interactive ? 'pointer' : 'default'}
      >
        <NavigationControl position="top-right" />
        <ScaleControl position="bottom-left" />

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
                className={`
                  flex items-center justify-center rounded-full border-2 shadow-sm
                  ${
                    cp.type === 'route_start'
                      ? 'w-7 h-7 bg-green-500 border-green-700'
                      : cp.type === 'route_end'
                        ? 'w-7 h-7 bg-red-500 border-red-700'
                        : 'w-6 h-6 bg-white border-slate-600'
                  }
                `}
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

        {/* ============================================================ */}
        {/* POPUP                                                       */}
        {/* ============================================================ */}

        {popupInfo && (
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
        )}
      </Map>

      {/* Legend */}
      {showLegend && (
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
      )}
    </div>
  )
}

// Re-export types
export type { Route, Checkpoint, RouteDisplayStatus, RouteMapProps }
