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
  SPOT_ZOOM,
  BLOCK_STATUS_COLORS,
  CHICAGO_CENTER_LNG_LAT,
} from '@/lib/design-tokens'
import { MAP_BOUNDS } from '@/data/blocks'
import { BaseMap, MapLegend } from './BaseMap'
import type { Bounty, SpotReport } from './BountyMap'
import type { Route } from './RouteMap'

// ============================================================================
// TYPES
// ============================================================================

export interface BlockOnMap {
  id: string
  code: string
  name: string
  status: string
  boundary: { type: 'Polygon'; coordinates: number[][][] }
  center: [number, number] // [lng, lat]
  funding: { goal: number; current: number }
  displayImages: string[]
  spotCount: number
  streetName?: string | null
  fromCorner?: string | null
  toCorner?: string | null
  side?: string | null
}

interface CombinedMapProps {
  bounties: Bounty[]
  spots?: SpotReport[]
  routes?: Route[]
  blocks?: BlockOnMap[]
  height?: string
  showLegend?: boolean
  onBountySelect?: (bounty: Bounty) => void
  onRouteSelect?: (route: Route) => void
  onBlockSelect?: (block: BlockOnMap) => void
  selectedRouteId?: number
}

type PopupTarget =
  | { kind: 'bounty'; data: Bounty }
  | { kind: 'route'; data: Route }
  | { kind: 'spot'; data: SpotReport; lng: number; lat: number }
  | { kind: 'block'; data: BlockOnMap }

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
  published: 'Registered',
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
  blocks = [],
  height = '500px',
  showLegend = true,
  onBountySelect,
  onRouteSelect,
  onBlockSelect,
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
          needsVerification: (spot as any).needsVerification ?? false,
          tags: JSON.stringify((spot as any).tags ?? []),
        },
        geometry: {
          type: 'Point' as const,
          coordinates: spot.coordinates,
        },
      })),
    }),
    [spots]
  )

  // ----- block GeoJSON -----
  const blockGeoJson = useMemo(
    () => ({
      type: 'FeatureCollection' as const,
      features: blocks
        .filter((b) => b.boundary)
        .map((block) => ({
          type: 'Feature' as const,
          id: block.id,
          properties: {
            id: block.id,
            code: block.code,
            name: block.name,
            status: block.status,
            fundingGoal: block.funding.goal,
            fundingCurrent: block.funding.current,
            hasImages: block.displayImages.length > 0,
            spotCount: block.spotCount,
          },
          geometry: block.boundary,
        })),
    }),
    [blocks]
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

  // ----- handle map feature click (routes + spots + blocks) -----
  const handleMapClick = useCallback(
    (event: any) => {
      const feature = event.features?.[0]
      if (!feature || !feature.properties) return

      const layerId = feature.layer?.id || ''

      // Block click
      if (layerId.includes('block')) {
        const blockId = feature.properties.id
        const block = blocks.find((b) => String(b.id) === String(blockId))
        if (block) {
          setPopup({ kind: 'block', data: block })
          onBlockSelect?.(block)
        }
        return
      }

      // Spot click
      if (layerId.includes('spot')) {
        const spotId = feature.properties.id
        const spot = spots.find((s) => String(s.id) === String(spotId))
        if (spot) {
          setPopup({
            kind: 'spot',
            data: spot,
            lng: spot.coordinates[0],
            lat: spot.coordinates[1],
          })
        }
        return
      }

      // Route click
      const routeId = feature.properties.id
      const route = routes.find((r) => r.id === routeId)
      if (route) {
        setPopup({ kind: 'route', data: route })
        onRouteSelect?.(route)
      }
    },
    [routes, spots, blocks, onRouteSelect, onBlockSelect]
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

  // Blocks
  if (blocks.length > 0) {
    legendItems.push({ color: BLOCK_STATUS_COLORS.available.fill, label: 'Block (Available)' })
    legendItems.push({ color: BLOCK_STATUS_COLORS.in_progress.fill, label: 'Block (In Progress)' })
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

    // spot popup
    if (popup.kind === 'spot') {
      const s = popup.data
      const allPhotos = s.mediaUrls?.length ? s.mediaUrls : s.photoUrl ? [s.photoUrl] : []
      return (
        <Popup
          longitude={popup.lng}
          latitude={popup.lat}
          anchor="bottom"
          onClose={() => setPopup(null)}
          closeButton
          maxWidth="360px"
        >
          <div className="p-4 min-w-[300px] max-w-[360px]">
            <div className="flex items-center gap-2 mb-3">
              <span
                className="w-3.5 h-3.5 rounded-full flex-shrink-0"
                style={{ backgroundColor: SPOT_COLORS.MARKER }}
              />
              <span className="font-bold text-neutral-900 text-base">Spot Report</span>
              {s.status && (
                <span
                  className="text-xs px-2 py-0.5 rounded-full text-white font-medium ml-auto capitalize"
                  style={{ backgroundColor: s.status === 'verified' ? '#22C55E' : s.status === 'acknowledged' ? '#3B82F6' : '#F59E0B' }}
                >
                  {s.status}
                </span>
              )}
            </div>
            {allPhotos.length > 0 && (
              <div className={`mb-3 ${allPhotos.length > 1 ? 'flex gap-2 overflow-x-auto' : ''}`}>
                {allPhotos.map((url, i) => (
                  <img
                    key={i}
                    src={url}
                    alt={`Spot report photo ${i + 1}`}
                    className={`object-cover rounded-lg cursor-pointer hover:opacity-90 transition-opacity ${
                      allPhotos.length === 1 ? 'w-full h-44' : 'w-40 h-32 flex-shrink-0'
                    }`}
                    onClick={() => window.open(url, '_blank')}
                  />
                ))}
              </div>
            )}
            {s.label && (
              <p className="text-sm font-medium text-neutral-700 mb-1 capitalize">{s.label.replace(/_/g, ' ')}</p>
            )}
            {(s as any).tags?.length > 0 && (
              <div className="flex flex-wrap gap-1.5 mb-2">
                {((s as any).tags as string[]).map((tag: string) => (
                  <span key={tag} className="text-xs px-2 py-0.5 rounded-full bg-neutral-100 text-neutral-600 capitalize">{tag}</span>
                ))}
              </div>
            )}
            {s.description && (
              <p className="text-sm text-neutral-500 mb-2 line-clamp-3">{s.description}</p>
            )}
            <div className="flex items-center gap-3 text-xs text-neutral-500 pt-2 border-t border-neutral-100">
              <span>Radius: {s.radiusMeters}m</span>
              {s.reporterName && <span>By: {s.reporterName}</span>}
              {allPhotos.length > 1 && <span className="ml-auto">{allPhotos.length} photos</span>}
            </div>
          </div>
        </Popup>
      )
    }

    // block popup
    if (popup.kind === 'block') {
      const block = popup.data
      return (
        <Popup
          longitude={block.center[0]}
          latitude={block.center[1]}
          anchor="bottom"
          onClose={() => setPopup(null)}
          closeButton
          maxWidth="360px"
        >
          <div className="p-4 min-w-[300px] max-w-[360px]">
            {block.displayImages.length > 0 && (
              <div className={`mb-3 ${block.displayImages.length > 1 ? 'flex gap-2 overflow-x-auto' : ''}`}>
                {block.displayImages.slice(0, 3).map((url, i) => (
                  <img
                    key={i}
                    src={url}
                    alt={`${block.name} photo ${i + 1}`}
                    className={`object-cover rounded-lg ${
                      block.displayImages.length === 1 ? 'w-full h-44' : 'w-40 h-32 flex-shrink-0'
                    }`}
                  />
                ))}
              </div>
            )}
            <div className="flex items-center justify-between mb-2">
              <span className="font-bold text-neutral-900">{block.name}</span>
              <span
                className="text-xs px-2 py-0.5 rounded-full text-white font-medium capitalize"
                style={{ backgroundColor: (BLOCK_STATUS_COLORS as any)[block.status]?.fill || '#6B7280' }}
              >
                {block.status.replace(/_/g, ' ')}
              </span>
            </div>
            <p className="text-xs text-neutral-500 mb-1">{block.code}</p>
            {block.streetName && (
              <p className="text-xs text-neutral-500 mb-2">
                {block.streetName} &middot; {block.fromCorner} to {block.toCorner}
              </p>
            )}
            <div className="mt-2">
              <div className="flex justify-between text-xs text-neutral-500 mb-1">
                <span>${(block.funding.current / 100).toFixed(0)} raised</span>
                <span>${(block.funding.goal / 100).toFixed(0)} goal</span>
              </div>
              <div className="h-1.5 bg-neutral-200 rounded-full overflow-hidden">
                <div
                  className="h-full bg-green-500 rounded-full"
                  style={{ width: `${Math.min(100, block.funding.goal > 0 ? (block.funding.current / block.funding.goal) * 100 : 0)}%` }}
                />
              </div>
            </div>
            {block.spotCount > 0 && (
              <p className="text-xs text-neutral-500 mt-2">{block.spotCount} spot report{block.spotCount !== 1 ? 's' : ''}</p>
            )}
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
        <div className="p-3 min-w-[220px] max-w-[320px]">
          {r.imageUrl && (
            <img
              src={r.imageUrl}
              alt={r.displayName}
              className="w-full h-32 object-cover rounded-lg mb-2"
            />
          )}
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
  }, [popup, onRouteSelect, spots, blocks])

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
        interactiveLayerIds={[
          ...(blocks.length > 0 ? ['combined-block-fill'] : []),
          ...(routes.length > 0 ? ['combined-route-fill-layer'] : []),
          ...(spots.length > 0 ? ['combined-spot-center', 'combined-spot-radius-fill', 'combined-spot-verification-ring'] : []),
        ]}
        onClick={routes.length > 0 || spots.length > 0 || blocks.length > 0 ? handleMapClick : undefined}
        cursor="pointer"
        legend={legendNode}
        popup={popupNode}
        mapRef={(ref) => {
          mapRef.current = ref
          if (ref) setMapLoaded(true)
        }}
      >
        {/* ============================================================ */}
        {/* BLOCK POLYGONS                                              */}
        {/* ============================================================ */}

        {blocks.length > 0 && (
          <Source id="combined-blocks" type="geojson" data={blockGeoJson}>
            {/* Block polygon fill */}
            <Layer
              id="combined-block-fill"
              type="fill"
              paint={{
                'fill-color': [
                  'match', ['get', 'status'],
                  'available', BLOCK_STATUS_COLORS.available.fill,
                  'claimed', BLOCK_STATUS_COLORS.claimed.fill,
                  'in_progress', BLOCK_STATUS_COLORS.in_progress.fill,
                  'pending_review', BLOCK_STATUS_COLORS.pending_review.fill,
                  'completed', BLOCK_STATUS_COLORS.completed.fill,
                  BLOCK_STATUS_COLORS.unfunded.fill,
                ],
                'fill-opacity': [
                  'match', ['get', 'status'],
                  'available', BLOCK_STATUS_COLORS.available.opacity,
                  'claimed', BLOCK_STATUS_COLORS.claimed.opacity,
                  'in_progress', BLOCK_STATUS_COLORS.in_progress.opacity,
                  'pending_review', BLOCK_STATUS_COLORS.pending_review.opacity,
                  'completed', BLOCK_STATUS_COLORS.completed.opacity,
                  BLOCK_STATUS_COLORS.unfunded.opacity,
                ],
              }}
            />
            {/* Block polygon outline */}
            <Layer
              id="combined-block-outline"
              type="line"
              paint={{
                'line-color': [
                  'match', ['get', 'status'],
                  'available', BLOCK_STATUS_COLORS.available.fill,
                  'claimed', BLOCK_STATUS_COLORS.claimed.fill,
                  'in_progress', BLOCK_STATUS_COLORS.in_progress.fill,
                  'pending_review', BLOCK_STATUS_COLORS.pending_review.fill,
                  'completed', BLOCK_STATUS_COLORS.completed.fill,
                  BLOCK_STATUS_COLORS.unfunded.fill,
                ],
                'line-width': 2,
                'line-opacity': 0.8,
              }}
            />
            {/* Block code labels */}
            <Layer
              id="combined-block-label"
              type="symbol"
              layout={{
                'text-field': ['get', 'code'],
                'text-size': 11,
                'text-font': ['Open Sans Bold', 'Arial Unicode MS Bold'],
              }}
              paint={{
                'text-color': '#1F2937',
                'text-halo-color': '#FFFFFF',
                'text-halo-width': 1.5,
              }}
            />
          </Source>
        )}

        {/* ============================================================ */}
        {/* BLOCK IMAGE MARKERS (thumbnail at block center)             */}
        {/* ============================================================ */}

        {blocks
          .filter((b) => b.displayImages.length > 0)
          .map((block) => (
            <Marker
              key={`block-img-${block.id}`}
              longitude={block.center[0]}
              latitude={block.center[1]}
              anchor="bottom"
              onClick={(e) => {
                e.originalEvent.stopPropagation()
                setPopup({ kind: 'block', data: block })
                onBlockSelect?.(block)
              }}
            >
              <div className="flex flex-col items-center cursor-pointer group">
                <div className="w-16 h-12 rounded-lg overflow-hidden border-2 border-white shadow-md group-hover:shadow-lg transition-shadow">
                  <img
                    src={block.displayImages[0]}
                    alt={block.name}
                    className="w-full h-full object-cover"
                  />
                </div>
                <div className="bg-white/95 px-2 py-0.5 rounded-full text-[10px] font-bold shadow-sm -mt-1 text-neutral-800">
                  {block.code}
                </div>
              </div>
            </Marker>
          ))}

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
        {/* SPOT REPORTS — HEATMAP (zoomed out) + DOTS (zoomed in)      */}
        {/* ============================================================ */}

        {spots.length > 0 && (
          <Source id="combined-spots" type="geojson" data={spotGeoJson}>
            {/* Heatmap layer — visible at overview zoom, fades as you zoom in */}
            <Layer
              id="combined-spot-heatmap"
              type="heatmap"
              maxzoom={SPOT_ZOOM.HEATMAP_MAX}
              paint={{
                // Weight each point equally (could use radiusMeters for intensity)
                'heatmap-weight': 1,
                // Intensity increases with zoom to keep heat visible as radius shrinks
                'heatmap-intensity': [
                  'interpolate', ['linear'], ['zoom'],
                  SPOT_ZOOM.HEATMAP_MIN, 0.3,
                  SPOT_ZOOM.HEATMAP_MAX, 1,
                ],
                // Color ramp: transparent → green → yellow → orange → red
                'heatmap-color': [
                  'interpolate', ['linear'], ['heatmap-density'],
                  0,    'rgba(0,0,0,0)',
                  0.2,  SPOT_COLORS.HEATMAP_LOW,
                  0.4,  SPOT_COLORS.HEATMAP_MED,
                  0.7,  SPOT_COLORS.HEATMAP_HIGH,
                  1.0,  SPOT_COLORS.HEATMAP_PEAK,
                ],
                // Radius grows with zoom so density estimation covers appropriate area
                'heatmap-radius': [
                  'interpolate', ['linear'], ['zoom'],
                  SPOT_ZOOM.HEATMAP_MIN, 8,
                  11, 15,
                  SPOT_ZOOM.DOTS_MIN, 20,
                ],
                // Fade out as individual dots take over
                'heatmap-opacity': [
                  'interpolate', ['linear'], ['zoom'],
                  SPOT_ZOOM.HEATMAP_MIN, 0.6,
                  SPOT_ZOOM.DOTS_MIN, 0.3,
                  SPOT_ZOOM.HEATMAP_MAX, 0,
                ],
              }}
            />

            {/* Radius fill — visible at close zoom */}
            <Layer
              id="combined-spot-radius-fill"
              type="circle"
              minzoom={SPOT_ZOOM.DOTS_MIN}
              paint={{
                'circle-radius': [
                  'interpolate', ['linear'], ['zoom'],
                  SPOT_ZOOM.DOTS_MIN, 4,
                  16, ['get', 'radiusMeters'],
                  20, ['*', ['get', 'radiusMeters'], 4],
                ],
                'circle-color': SPOT_COLORS.RADIUS_FILL,
                'circle-stroke-color': SPOT_COLORS.RADIUS_STROKE,
                'circle-stroke-width': 1.5,
                'circle-opacity': [
                  'interpolate', ['linear'], ['zoom'],
                  SPOT_ZOOM.DOTS_MIN, 0,
                  14, 0.6,
                ],
              }}
            />

            {/* Individual spot dots — appear at close zoom */}
            <Layer
              id="combined-spot-center"
              type="circle"
              minzoom={SPOT_ZOOM.DOTS_MIN}
              paint={{
                'circle-radius': [
                  'interpolate', ['linear'], ['zoom'],
                  SPOT_ZOOM.DOTS_MIN, 3,
                  15, 5,
                  17, 7,
                ],
                'circle-color': SPOT_COLORS.MARKER,
                'circle-stroke-color': '#ffffff',
                'circle-stroke-width': 2,
                'circle-opacity': [
                  'interpolate', ['linear'], ['zoom'],
                  SPOT_ZOOM.DOTS_MIN, 0,
                  14, 1,
                ],
              }}
            />

            {/* Verification indicator — orange ring on spots needing verification */}
            <Layer
              id="combined-spot-verification-ring"
              type="circle"
              minzoom={SPOT_ZOOM.DOTS_MIN}
              filter={['==', ['get', 'needsVerification'], true]}
              paint={{
                'circle-radius': [
                  'interpolate', ['linear'], ['zoom'],
                  SPOT_ZOOM.DOTS_MIN, 5,
                  15, 9,
                  17, 12,
                ],
                'circle-color': 'transparent',
                'circle-stroke-color': '#FF8C00',
                'circle-stroke-width': [
                  'interpolate', ['linear'], ['zoom'],
                  SPOT_ZOOM.DOTS_MIN, 1.5,
                  15, 2.5,
                ],
                'circle-opacity': [
                  'interpolate', ['linear'], ['zoom'],
                  SPOT_ZOOM.DOTS_MIN, 0,
                  14, 1,
                ],
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
