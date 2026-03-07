'use client'

import { useState, useCallback, useMemo, useRef, useEffect } from 'react'
import { Source, Layer, Marker, Popup } from 'react-map-gl/maplibre'
import type { MapRef } from 'react-map-gl/maplibre'
import { Camera } from 'lucide-react'
import {
  ZOOM,
  ROUTE_WIDTHS,
  ROUTE_STATUS_COLORS,
  CHECKPOINT_COLORS,
  CHECKPOINT_BORDER_COLORS,
  BOUNTY_COLORS,
  SPOT_COLORS,
  CHICAGO_CENTER_LNG_LAT,
} from '@/lib/design-tokens'
import { MAP_BOUNDS } from '@/data/blocks'
import { BaseMap, MapLegend } from './BaseMap'
import type { Bounty, SpotReport } from './BountyMap'
import type { Route } from './RouteMap'

// ============================================================================
// TYPES
// ============================================================================

interface CombinedMapProps {
  bounties: Bounty[]
  spots?: SpotReport[]
  routes?: Route[]
  height?: string
  showLegend?: boolean
  onBountySelect?: (bounty: Bounty) => void
  onRouteSelect?: (route: Route) => void
  selectedRouteId?: number
}

type PopupTarget =
  | { kind: 'bounty'; data: Bounty }
  | { kind: 'route'; data: Route }

// ============================================================================
// COLOR MAPS
// ============================================================================

type BountyStatus = 'open' | 'in_progress' | 'completed'
type RouteDisplayStatus = 'published' | 'claimed' | 'active' | 'completed'

const BOUNTY_STATUS_COLOR: Record<BountyStatus, string> = {
  open: BOUNTY_COLORS.OPEN,
  in_progress: BOUNTY_COLORS.IN_PROGRESS,
  completed: BOUNTY_COLORS.COMPLETED,
}

const BOUNTY_STATUS_LABELS: Record<BountyStatus, string> = {
  open: 'Open',
  in_progress: 'In Progress',
  completed: 'Completed',
}

const ROUTE_COLORS = {
  published: { fill: ROUTE_STATUS_COLORS.published.line, case: ROUTE_STATUS_COLORS.published.casing },
  claimed: { fill: ROUTE_STATUS_COLORS.claimed.line, case: ROUTE_STATUS_COLORS.claimed.casing },
  active: { fill: ROUTE_STATUS_COLORS.active.line, case: ROUTE_STATUS_COLORS.active.casing },
  completed: { fill: ROUTE_STATUS_COLORS.completed.line, case: ROUTE_STATUS_COLORS.completed.casing },
} as const

const ROUTE_STATUS_LABELS: Record<RouteDisplayStatus, string> = {
  published: 'Route Available',
  claimed: 'Route Claimed',
  active: 'Route Active',
  completed: 'Route Done',
}

const ROUTE_LINE_WIDTHS = {
  case: ROUTE_WIDTHS.CASING,
  fill: ROUTE_WIDTHS.CORE,
  caseSelected: ROUTE_WIDTHS.GLOW,
  fillSelected: ROUTE_WIDTHS.CASING,
} as const

// ============================================================================
// PULSE ANIMATION
// ============================================================================

const pulseKeyframes = `
@keyframes bounty-pulse {
  0% {
    box-shadow: 0 0 0 0 rgba(239, 68, 68, 0.6);
  }
  70% {
    box-shadow: 0 0 0 12px rgba(239, 68, 68, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(239, 68, 68, 0);
  }
}
`

// ============================================================================
// COMPONENT
// ============================================================================

export function CombinedMap({
  bounties,
  spots = [],
  routes = [],
  height = '500px',
  showLegend = true,
  onBountySelect,
  onRouteSelect,
  selectedRouteId,
}: CombinedMapProps) {
  const mapRef = useRef<MapRef | null>(null)
  const [mapLoaded, setMapLoaded] = useState(false)
  const [popup, setPopup] = useState<PopupTarget | null>(null)

  // ----- fit bounds on load -----
  useEffect(() => {
    if (mapLoaded && mapRef.current) {
      mapRef.current.fitBounds(
        [
          [MAP_BOUNDS.west - 0.001, MAP_BOUNDS.south - 0.001],
          [MAP_BOUNDS.east + 0.001, MAP_BOUNDS.north + 0.001],
        ],
        {
          padding: { top: 40, bottom: 40, left: 40, right: 40 },
          duration: 1000,
        }
      )
    }
  }, [mapLoaded])

  // ----- bounty click -----
  const handleBountyClick = useCallback(
    (bounty: Bounty) => {
      setPopup({ kind: 'bounty', data: bounty })
      onBountySelect?.(bounty)
    },
    [onBountySelect]
  )

  // ----- route GeoJSON -----
  const routeGeoJson = useMemo(
    () => ({
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
    }),
    [routes, selectedRouteId]
  )

  // ----- spot GeoJSON -----
  const spotGeoJson = useMemo(
    () => ({
      type: 'FeatureCollection' as const,
      features: spots.map((spot) => ({
        type: 'Feature' as const,
        properties: {
          id: spot.id,
          radiusMeters: spot.radiusMeters,
          label: spot.label || '',
        },
        geometry: {
          type: 'Point' as const,
          coordinates: spot.coordinates,
        },
      })),
    }),
    [spots]
  )

  // ----- all checkpoints -----
  const allCheckpoints = useMemo(
    () =>
      routes.flatMap((route) =>
        route.checkpoints.map((cp) => ({
          ...cp,
          routeId: route.id,
          routeStatus: route.status,
        }))
      ),
    [routes]
  )

  // ----- handle route line click -----
  const handleMapClick = useCallback(
    (event: any) => {
      const feature = event.features?.[0]
      if (feature && feature.properties) {
        const routeId = feature.properties.id
        const route = routes.find((r) => r.id === routeId)
        if (route) {
          setPopup({ kind: 'route', data: route })
          onRouteSelect?.(route)
        }
      }
    },
    [routes, onRouteSelect]
  )

  // ----- initial view (will be overridden by fitBounds) -----
  const initialViewState = {
    longitude: CHICAGO_CENTER_LNG_LAT[0],
    latitude: CHICAGO_CENTER_LNG_LAT[1],
    zoom: ZOOM.ROUTE,
  }

  // ----- legend items -----
  const legendItems: { color: string; label: string; shape?: 'line' | 'circle' }[] = []

  // Routes
  const routeStatuses = new Set(routes.map((r) => r.status))
  for (const [status, label] of Object.entries(ROUTE_STATUS_LABELS) as [RouteDisplayStatus, string][]) {
    if (routeStatuses.has(status)) {
      legendItems.push({ color: ROUTE_COLORS[status].fill, label, shape: 'line' })
    }
  }

  // Bounties
  const bountyStatuses = new Set(bounties.map((b) => b.status))
  for (const [status, label] of Object.entries(BOUNTY_STATUS_LABELS) as [BountyStatus, string][]) {
    if (bountyStatuses.has(status)) {
      legendItems.push({ color: BOUNTY_STATUS_COLOR[status], label: `Bounty: ${label}`, shape: 'circle' })
    }
  }

  // Spots
  if (spots.length > 0) {
    legendItems.push({ color: SPOT_COLORS.MARKER, label: 'Spot Report', shape: 'circle' })
  }

  // ----- build popup -----
  const popupNode = useMemo(() => {
    if (!popup) return undefined

    if (popup.kind === 'bounty') {
      const b = popup.data
      return (
        <Popup
          longitude={b.coordinates[0]}
          latitude={b.coordinates[1]}
          anchor="bottom"
          onClose={() => setPopup(null)}
          closeButton
        >
          <div className="p-3 min-w-[240px]">
            <div className="flex items-center justify-between mb-2">
              <span className="font-bold text-neutral-900">{b.title}</span>
              <span
                className="text-xs px-2 py-0.5 rounded-full text-white font-medium"
                style={{ backgroundColor: BOUNTY_STATUS_COLOR[b.status] }}
              >
                {BOUNTY_STATUS_LABELS[b.status]}
              </span>
            </div>
            <p className="text-sm text-neutral-600 mb-2 line-clamp-2">{b.description}</p>
            <div className="flex items-center justify-between mt-3 pt-3 border-t">
              <span className="font-semibold text-green-600 text-lg">
                ${(b.amountCents / 100).toFixed(0)}
              </span>
              <span className="text-xs text-neutral-500">
                {b.pledgeCount} {b.pledgeCount === 1 ? 'pledge' : 'pledges'}
              </span>
            </div>
          </div>
        </Popup>
      )
    }

    // route popup
    const r = popup.data
    return (
      <Popup
        longitude={r.geometry.coordinates[0][0]}
        latitude={r.geometry.coordinates[0][1]}
        anchor="bottom"
        onClose={() => setPopup(null)}
        closeButton
      >
        <div className="p-3 min-w-[220px]">
          <div className="flex items-center justify-between mb-2">
            <span className="font-bold text-neutral-900">{r.displayName}</span>
            <span
              className="text-xs px-2 py-0.5 rounded-full text-white font-medium"
              style={{ backgroundColor: ROUTE_COLORS[r.status].fill }}
            >
              {ROUTE_STATUS_LABELS[r.status]}
            </span>
          </div>
          <p className="text-sm text-neutral-600 mb-1">{r.code}</p>
          <div className="flex items-center gap-4 text-xs text-neutral-500 mb-2">
            {r.lengthMeters && <span>{(r.lengthMeters / 1000).toFixed(1)} km</span>}
            {r.estimatedMinutes && <span>{r.estimatedMinutes} min</span>}
            <span>{r.checkpoints.length} checkpoints</span>
          </div>
          <div className="flex items-center justify-between mt-3 pt-3 border-t">
            <span className="font-semibold text-green-600">
              ${(r.payoutAmount / 100).toFixed(0)}
            </span>
            {r.status === 'published' && onRouteSelect && (
              <button
                onClick={() => onRouteSelect(r)}
                className="bg-green-500 hover:bg-green-600 text-white text-sm py-1.5 px-4 rounded-lg font-medium transition-colors"
              >
                Claim Route
              </button>
            )}
          </div>
        </div>
      </Popup>
    )
  }, [popup, onRouteSelect])

  // ----- combined legend -----
  const legendNode = showLegend ? (
    <div className="absolute bottom-8 left-3 bg-white/95 backdrop-blur-sm rounded-lg p-3 text-xs shadow-md z-10">
      <div className="font-semibold mb-1.5">Map Legend</div>
      <div className="space-y-1">
        {legendItems.map((item) => (
          <div key={item.label} className="flex items-center gap-2">
            {item.shape === 'line' ? (
              <span
                className="w-5 h-1 rounded-full inline-block flex-shrink-0"
                style={{ backgroundColor: item.color }}
              />
            ) : (
              <span
                className="w-3 h-3 rounded-full inline-block flex-shrink-0"
                style={{ backgroundColor: item.color }}
              />
            )}
            <span className="text-neutral-600">{item.label}</span>
          </div>
        ))}
      </div>
    </div>
  ) : undefined

  return (
    <>
      <style dangerouslySetInnerHTML={{ __html: pulseKeyframes }} />

      <BaseMap
        height={height}
        initialViewState={initialViewState}
        interactiveLayerIds={routes.length > 0 ? ['combined-route-fill-layer'] : []}
        onClick={routes.length > 0 ? handleMapClick : undefined}
        cursor="pointer"
        legend={legendNode}
        popup={popupNode}
        mapRef={(ref) => {
          mapRef.current = ref
          if (ref) setMapLoaded(true)
        }}
      >
        {/* ============================================================ */}
        {/* ROUTE LINES - Cased Line Technique                          */}
        {/* ============================================================ */}

        {routes.length > 0 && (
          <Source id="combined-routes" type="geojson" data={routeGeoJson}>
            {/* Route case (shadow/outline) */}
            <Layer
              id="combined-route-case-layer"
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

            {/* Route fill (bright inner line) */}
            <Layer
              id="combined-route-fill-layer"
              type="line"
              layout={{ 'line-cap': 'round', 'line-join': 'round' }}
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

            {/* Route labels */}
            <Layer
              id="combined-route-label-layer"
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
        )}

        {/* ============================================================ */}
        {/* CHECKPOINT MARKERS                                          */}
        {/* ============================================================ */}

        {allCheckpoints.map((cp, idx) => (
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

        {/* ============================================================ */}
        {/* SPOT REPORT RADIUS CIRCLES                                  */}
        {/* ============================================================ */}

        {spots.length > 0 && (
          <Source id="combined-spots" type="geojson" data={spotGeoJson}>
            <Layer
              id="combined-spot-radius-fill"
              type="circle"
              paint={{
                'circle-radius': [
                  'interpolate',
                  ['linear'],
                  ['zoom'],
                  12, 4,
                  16, ['get', 'radiusMeters'],
                  20, ['*', ['get', 'radiusMeters'], 4],
                ],
                'circle-color': SPOT_COLORS.RADIUS_FILL,
                'circle-stroke-color': SPOT_COLORS.RADIUS_STROKE,
                'circle-stroke-width': 1.5,
                'circle-opacity': 0.6,
              }}
            />
            <Layer
              id="combined-spot-center"
              type="circle"
              paint={{
                'circle-radius': 5,
                'circle-color': SPOT_COLORS.MARKER,
                'circle-stroke-color': '#ffffff',
                'circle-stroke-width': 2,
              }}
            />
          </Source>
        )}

        {/* ============================================================ */}
        {/* BOUNTY MARKERS                                              */}
        {/* ============================================================ */}

        {bounties.map((bounty) => (
          <Marker
            key={`bounty-${bounty.id}`}
            longitude={bounty.coordinates[0]}
            latitude={bounty.coordinates[1]}
            anchor="center"
            onClick={(e) => {
              e.originalEvent.stopPropagation()
              handleBountyClick(bounty)
            }}
          >
            <div
              className="flex items-center justify-center rounded-full border-2 border-white cursor-pointer select-none"
              style={{
                width: 36,
                height: 36,
                backgroundColor: BOUNTY_STATUS_COLOR[bounty.status],
                animation:
                  bounty.status === 'open'
                    ? 'bounty-pulse 2s infinite'
                    : undefined,
              }}
            >
              <span className="text-white text-xs font-bold leading-none">
                ${(bounty.amountCents / 100).toFixed(0)}
              </span>
            </div>
          </Marker>
        ))}
      </BaseMap>
    </>
  )
}

export type { CombinedMapProps }
