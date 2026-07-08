'use client'

import { useState, useCallback, useMemo, useRef, useEffect } from 'react'
import { Source, Layer, Marker, Popup } from 'react-map-gl/maplibre'
import type { MapRef } from 'react-map-gl/maplibre'
import {
  ZOOM,
  ROUTE_WIDTHS,
  ROUTE_STATUS_COLORS,
  BOUNTY_COLORS,
  SPOT_COLORS,
  SPOT_ZOOM,
  CHICAGO_CENTER,
  CHICAGO_CENTER_LNG_LAT,
  SPOT_SCRIM_PAINT,
  LITTER_FOOTPRINT_COLORS,
  LITTER_FOOTPRINT_ZOOM,
  getSpotHeatWeight,
} from '@/lib/design-tokens'
import { MAP_BOUNDS } from '@/data/blocks'
import { BaseMap, MapLegend } from './BaseMap'
import { PrecipitationLegend } from './PrecipitationLegend'
import { CoverageFreshnessOverlay } from './CoverageFreshnessOverlay'
import type { FallbackGeoJSONLayer } from './BaseMap'
import type { Bounty, SpotReport } from './BountyMap'
import type { Route } from './RouteMap'
import {
  buildObservationFootprintsGeoJson,
  humanizeItemType,
  formatEstimatedWeight,
  type MapObservation,
} from '@/lib/litter-footprints'
import {
  aggregateCellHealth,
  buildChoroplethGeoJson,
  choroplethTileMetersForZoom,
} from '@/lib/cell-health-choropleth'

// ============================================================================
// TYPES
// ============================================================================

interface CombinedMapProps {
  bounties: Bounty[]
  spots?: SpotReport[]
  routes?: Route[]
  /** World observations (GET /api/map/observations) driving the litter-
   *  footprint layer — the close-zoom, true-scale replacement for the
   *  removed spot pin/glyph/verification-ring layers (Pillar 8 Task 3).
   *  A prop rather than an internal fetch: today CombinedMap has exactly
   *  one consumer (bounties/page.tsx), which already fetches this feed for
   *  its own fold-in logic — an internal bbox-scoped fetch here would just
   *  double that request for zero benefit. Defaults to [] so pages that
   *  don't pass it keep working with no footprint layer rendered. */
  observations?: MapObservation[]
  height?: string
  showLegend?: boolean
  onBountySelect?: (bounty: Bounty) => void
  onRouteSelect?: (route: Route) => void
  selectedRouteId?: number
}

type PopupTarget =
  | { kind: 'bounty'; data: Bounty }
  | { kind: 'route'; data: Route }
  | { kind: 'footprint'; data: MapObservation }

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

// Per-status spot dot colors for the Leaflet (no-WebGL) fallback path only.
// The MapLibre `combined-spot-center` circle layer this used to parallel was
// removed in Pillar 8 Task 3 (replaced by the litter-footprint layer, which
// has no Leaflet-fallback equivalent yet); this table lives on as the
// fallback's own status-color source of truth.
const STATUS_DOT_COLOR: Record<string, string> = {
  pending: '#F59E0B',       // amber — awaiting verification
  verified: '#EF4444',      // red — confirmed dirty
  acknowledged: '#3B82F6',  // blue — worker claimed
  resolved: '#22C55E',      // green — cleaned
}

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
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
  observations = [],
  height = '500px',
  showLegend = true,
  onBountySelect,
  onRouteSelect,
  selectedRouteId,
}: CombinedMapProps) {
  const mapRef = useRef<MapRef | null>(null)
  const [mapLoaded, setMapLoaded] = useState(false)
  const [popup, setPopup] = useState<PopupTarget | null>(null)
  const [currentZoom, setCurrentZoom] = useState(ZOOM.NEIGHBORHOOD)
  const [heatmapVisible, setHeatmapVisible] = useState(true)
  const [coverageOverlayVisible, setCoverageOverlayVisible] = useState(false) // off by default, per spec §4.2
  const [mapBounds, setMapBounds] = useState<{ west: number; south: number; east: number; north: number } | null>(null)
  const detailZoomOpacity = Math.max(
    0,
    Math.min(
      1,
      (currentZoom - SPOT_ZOOM.DOTS_MIN) / (SPOT_ZOOM.HEATMAP_FADE_OUT - SPOT_ZOOM.DOTS_MIN)
    )
  )

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

  // ----- spot GeoJSON (heatmap input only — the pin/glyph/verification-ring
  // layers that used to consume itemType/severity/domain/hasBounty/
  // needsVerification/tags were removed in Pillar 8 Task 3; combined-spot-
  // heatmap only ever reads `heatWeight`, so those properties are dead here
  // now and were dropped) -----
  const spotGeoJson = useMemo(
    () => ({
      type: 'FeatureCollection' as const,
      features: spots.map((spot) => ({
        type: 'Feature' as const,
        properties: {
          id: spot.id,
          radiusMeters: spot.radiusMeters,
          label: spot.label || '',
          heatWeight: getSpotHeatWeight(spot),
        },
        geometry: {
          type: 'Point' as const,
          coordinates: spot.coordinates,
        },
      })),
    }),
    [spots]
  )

  // ----- litter-footprint GeoJSON (Pillar 8 Task 3) -----
  // centerLat anchors the oriented-rectangle lng/lat meter-scale conversion
  // (matches the Kotlin reference's per-collection lngScale). Mobile
  // recomputes this from the live camera position every frame; the web
  // builder intentionally uses a fixed representative Chicago latitude
  // instead (same reasoning the Kotlin visibility-floor constant already
  // relies on — "in the right ballpark everywhere the app operates today")
  // rather than re-deriving the whole FeatureCollection on every `onMove`
  // pan/zoom tick, which would rebuild every footprint's geometry on each
  // pointer-move for zero visible benefit (aspect-ratio error from using a
  // fixed Chicago lat instead of the live one is well under 1% at this
  // city's latitude span).
  const footprintGeoJson = useMemo(
    () => buildObservationFootprintsGeoJson(observations, CHICAGO_CENTER.lat),
    [observations]
  )

  // ----- cell-health choropleth GeoJSON (far-zoom, v2 Pillar 8 amendment) --
  // Replaces the point-density spot heatmap: instead of a blurred kernel over
  // spot points, each far-zoom tile is colored by its OWN litter load
  // (green -> amber -> red, grey where never scanned). Fed by the SAME
  // `observations` feed already loaded for the footprint layer — no new
  // fetch (mirrors the Global Constraint the mobile side follows; web
  // coverage cells are only fetched lazily at close zoom inside
  // CoverageFreshnessOverlay, so the far-zoom choropleth is litter-driven).
  // tileMeters is bucketed off the zoom, so this only rebuilds when the zoom
  // crosses a bucket boundary or the observations change, not every pan tick.
  const choroplethTileMeters = choroplethTileMetersForZoom(currentZoom)
  const choroplethGeoJson = useMemo(
    () =>
      buildChoroplethGeoJson(
        aggregateCellHealth([], observations, choroplethTileMeters),
        choroplethTileMeters
      ),
    [observations, choroplethTileMeters]
  )
  const hasChoroplethTiles = choroplethGeoJson.features.length > 0

  // ----- handle map feature click (routes + litter footprints) -----
  const handleMapClick = useCallback(
    (event: any) => {
      const feature = event.features?.[0]
      if (!feature || !feature.properties) return

      const layerId = feature.layer?.id || ''

      // Litter-footprint click — the close-zoom litter-truth layer that
      // replaced the spot pin/glyph/verification-ring tap target (Pillar 8
      // Task 3). Same "pan so the popup has room" plumbing the old spot-pin
      // click handler used.
      if (layerId.includes('litter-footprint')) {
        const obsId = feature.properties.id
        const obs = observations.find((o) => o.id === obsId)
        if (obs) {
          setPopup({ kind: 'footprint', data: obs })

          const map = mapRef.current
          if (map) {
            const currentMapZoom = map.getZoom()
            map.flyTo({
              center: [obs.lng, obs.lat],
              zoom: Math.max(currentMapZoom, ZOOM.ROUTE),
              offset: [0, 140],
              duration: 600,
            })
          }
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
    [routes, observations, onRouteSelect]
  )

  // ----- initial view (will be overridden by fitBounds) -----
  const initialViewState = {
    longitude: CHICAGO_CENTER_LNG_LAT[0],
    latitude: CHICAGO_CENTER_LNG_LAT[1],
    zoom: ZOOM.ROUTE,
  }

  // ----- legend items -----
  const legendItems = useMemo(() => {
    const items: { color: string; label: string; shape?: 'line' | 'circle' }[] = []

    const routeStatuses = new Set(routes.map((r) => r.status))
    for (const [status, label] of Object.entries(ROUTE_STATUS_LABELS) as [RouteDisplayStatus, string][]) {
      if (routeStatuses.has(status)) {
        items.push({ color: ROUTE_COLORS[status].fill, label, shape: 'line' })
      }
    }

    const bountyStatuses = new Set(bounties.map((b) => b.status))
    for (const [status, label] of Object.entries(BOUNTY_STATUS_LABELS) as [BountyStatus, string][]) {
      if (bountyStatuses.has(status)) {
        items.push({ color: BOUNTY_STATUS_COLOR[status], label: `Bounty: ${label}`, shape: 'circle' })
      }
    }

    if (spots.length > 0) {
      items.push({ color: SPOT_COLORS.MARKER, label: 'Spot Report', shape: 'circle' })
    }

    return items
  }, [routes, bounties, spots.length])

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
            {(b.mediaUrls?.length ?? 0) > 0 && (
              <img
                src={b.mediaUrls![0]}
                alt=""
                className="mb-2 h-32 w-full rounded-lg object-cover"
              />
            )}
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

    // litter-footprint popup (Pillar 8 Task 3 — replaces the removed spot-
    // pin popup). Shows: photo (mediaUrl, when present — "best photo" per
    // observation is just its mediaUrl today), humanized itemType, estimated
    // weight when present, capturedAt date. No materials field exists on
    // WorldObservation, so it's omitted (not silently faked).
    if (popup.kind === 'footprint') {
      const o = popup.data
      const weightLabel = formatEstimatedWeight(o.estimatedWeightGrams)
      const domainColor =
        o.domain === 'vegetation'
          ? LITTER_FOOTPRINT_COLORS.vegetation
          : o.domain === 'construction'
            ? LITTER_FOOTPRINT_COLORS.construction
            : LITTER_FOOTPRINT_COLORS.other
      return (
        <Popup
          longitude={o.lng}
          latitude={o.lat}
          anchor="bottom"
          onClose={() => setPopup(null)}
          closeButton
          maxWidth="360px"
          offset={[0, -8] as [number, number]}
        >
          <div className="min-w-[280px] max-w-[340px] max-h-[60vh] overflow-y-auto">
            {o.mediaUrl && (
              <img
                src={o.mediaUrl}
                alt={`Litter: ${humanizeItemType(o.itemType)}`}
                className="w-full h-48 object-cover cursor-pointer hover:opacity-95 transition-opacity"
                onClick={() => window.open(o.mediaUrl!, '_blank')}
              />
            )}
            <div className="p-3">
              <div className="flex items-center gap-2 mb-2">
                <span
                  className="w-3 h-3 rounded-full flex-shrink-0"
                  style={{ backgroundColor: domainColor }}
                />
                <span className="font-bold text-neutral-900 text-sm">{humanizeItemType(o.itemType)}</span>
              </div>
              <div className="flex items-center gap-3 text-xs text-neutral-500 pt-2 border-t border-neutral-100">
                {weightLabel && <span>~{weightLabel}</span>}
                {o.capturedAt && (
                  <span className="ml-auto">{new Date(o.capturedAt).toLocaleDateString()}</span>
                )}
              </div>
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

  // Leaflet canvas fallback layers when WebGL is unavailable.
  // Spots get status-colored circle markers + a popup with label/status;
  // routes get colored polylines + a popup with displayName/status.
  // This makes the Leaflet path visually + functionally close to the
  // MapLibre path so users who fall back still get the real experience.
  const fallbackLayers = useMemo<FallbackGeoJSONLayer[]>(() => {
    const layers: FallbackGeoJSONLayer[] = []
    if (routes.length > 0) {
      layers.push({
        geojson: routeGeoJson,
        style: (feature) => {
          const status = (feature?.properties?.status as RouteDisplayStatus) ?? 'published'
          return { color: ROUTE_COLORS[status]?.fill ?? ROUTE_COLORS.published.fill, weight: 4, opacity: 0.9 }
        },
        onEachFeature: (feature, layer) => {
          const p = feature.properties || {}
          const status = (p.status as string) || 'published'
          const name = (p.displayName as string) || (p.code as string) || 'Route'
          layer.bindPopup(
            `<div style="font-family:system-ui;font-size:13px;min-width:160px">
               <div style="font-weight:600;margin-bottom:4px">${escapeHtml(name)}</div>
               <div style="color:#666;text-transform:capitalize">${escapeHtml(status)}</div>
             </div>`,
          )
        },
      })
    }
    if (spots.length > 0) {
      layers.push({
        geojson: spotGeoJson,
        style: (feature) => {
          const status = (feature?.properties?.status as string) || 'pending'
          const color = STATUS_DOT_COLOR[status] || SPOT_COLORS.MARKER
          return { color, fillColor: color, fillOpacity: 0.9, weight: 2 }
        },
        onEachFeature: (feature, layer) => {
          const p = feature.properties || {}
          const label = (p.label as string) || 'Spot Report'
          const status = (p.status as string) || 'pending'
          layer.bindPopup(
            `<div style="font-family:system-ui;font-size:13px;min-width:180px">
               <div style="font-weight:600;margin-bottom:4px;text-transform:capitalize">${escapeHtml(label.replace(/_/g, ' '))}</div>
               <div style="color:#666;text-transform:capitalize">Status: ${escapeHtml(status)}</div>
             </div>`,
          )
        },
      })
    }
    return layers
  }, [routeGeoJson, spotGeoJson, routes.length, spots.length])

  return (
    <>
      <style dangerouslySetInnerHTML={{ __html: pulseKeyframes }} />

      <BaseMap
        height={height}
        initialViewState={initialViewState}
        fallbackLayers={fallbackLayers}
        interactiveLayerIds={[
          ...(routes.length > 0 ? ['combined-route-fill-layer'] : []),
          ...(observations.length > 0 ? ['litter-footprint-fill'] : []),
        ]}
        onClick={routes.length > 0 || observations.length > 0 ? handleMapClick : undefined}
        onMove={(event) => {
          setCurrentZoom(event.viewState.zoom)
          const b = event.target.getBounds()
          setMapBounds({ west: b.getWest(), south: b.getSouth(), east: b.getEast(), north: b.getNorth() })
        }}
        cursor="pointer"
        legend={
          <>
            {spots.length > 0 ? (
              <>
                {heatmapVisible
                  ? <PrecipitationLegend onDone={() => setHeatmapVisible(false)} />
                  : legendNode}
                <button
                  data-testid="precip-toggle"
                  onClick={() => setHeatmapVisible((v) => !v)}
                  aria-pressed={heatmapVisible}
                  className="absolute top-28 right-3 z-10 bg-white/95 backdrop-blur-sm rounded-xl p-2 shadow-md"
                  title="Toggle report density"
                >
                  {/* stacked-layers glyph */}
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <polygon points="12 2 22 8.5 12 15 2 8.5 12 2" />
                    <polyline points="2 15.5 12 22 22 15.5" />
                  </svg>
                </button>
              </>
            ) : legendNode}
            <button
              data-testid="coverage-freshness-toggle"
              onClick={() => setCoverageOverlayVisible((v) => !v)}
              aria-pressed={coverageOverlayVisible}
              className="absolute top-44 right-3 z-10 bg-white/95 backdrop-blur-sm rounded-xl p-2 shadow-md"
              title="Show scan coverage"
            >
              {/* checkerboard/grid glyph */}
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <rect x="3" y="3" width="8" height="8" />
                <rect x="13" y="13" width="8" height="8" />
                <rect x="13" y="3" width="8" height="8" opacity="0.35" />
                <rect x="3" y="13" width="8" height="8" opacity="0.35" />
              </svg>
            </button>
          </>
        }
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

        {/* Checkpoint markers removed — admin-level detail not shown on public map */}

        {/* ============================================================ */}
        {/* SPOT REPORTS — HEATMAP (zoomed out) + DOTS (zoomed in)      */}
        {/* ============================================================ */}

        {/* Neutral scrim — mutes the basemap beneath the precipitation heatmap */}
        {spots.length > 0 && (
          <Source
            id="spot-scrim"
            type="geojson"
            data={{
              type: 'FeatureCollection',
              features: [{
                type: 'Feature',
                properties: {},
                geometry: {
                  type: 'Polygon',
                  coordinates: [[[-180, -85], [180, -85], [180, 85], [-180, 85], [-180, -85]]],
                },
              }],
            }}
          >
            <Layer
              id="spot-scrim-fill"
              type="fill"
              paint={SPOT_SCRIM_PAINT}
              layout={{ visibility: heatmapVisible ? 'visible' : 'none' }}
            />
          </Source>
        )}

        {/* Cell-health choropleth — far-zoom "how healthy is this ground"
            language (v2 Pillar 8 amendment). REPLACES the point-density spot
            heatmap (`combined-spot-heatmap`, keyed on spot-report density):
            each tile is now colored by ITS OWN litter load rather than a
            blurred kernel over report points. One data-driven fill layer,
            colored by `dominant` (grey where never scanned) falling back to
            an interpolate over `health` (green -> amber -> red) — the SAME
            visual vocabulary as the coverage-freshness tiles. Fades OUT at
            SPOT_ZOOM.HEATMAP_FADE_OUT, the exact zoom the litter-footprint
            layer below fades IN from, so the two never both show. */}
        {hasChoroplethTiles && (
          <Source id="cell-health-choropleth" type="geojson" data={choroplethGeoJson}>
            <Layer
              id="choropleth-cell-health-fill"
              type="fill"
              minzoom={SPOT_ZOOM.HEATMAP_MIN}
              maxzoom={SPOT_ZOOM.HEATMAP_FADE_OUT}
              paint={{
                'fill-color': [
                  'match',
                  ['get', 'dominant'],
                  'unscanned',
                  '#4B5563',
                  [
                    'interpolate',
                    ['linear'],
                    ['to-number', ['get', 'health']],
                    0.0,
                    '#EF4444',
                    0.5,
                    '#F59E0B',
                    1.0,
                    '#22C55E',
                  ],
                ],
                'fill-opacity': [
                  'interpolate',
                  ['exponential', 1.5],
                  ['zoom'],
                  SPOT_ZOOM.HEATMAP_MIN,
                  0.5,
                  12.5,
                  0.6,
                  13.5,
                  0.48,
                  SPOT_ZOOM.HEATMAP_FADE_OUT,
                  0,
                ],
              }}
              layout={{ visibility: heatmapVisible ? 'visible' : 'none' }}
            />
          </Source>
        )}

        {/* ============================================================ */}
        {/* LITTER FOOTPRINTS — close-zoom, true-scale, per-observation   */}
        {/* shapes (Pillar 8 Task 3). Web mirror of the mobile            */}
        {/* observation-footprint layer in GccMap.ios.kt: replaces the    */}
        {/* removed combined-spot-center/icons/verification-ring pin      */}
        {/* family. Geometry (rectangle rotation, vegetation circle,      */}
        {/* visibility floor) is computed once in JS via                  */}
        {/* buildObservationFootprintsGeoJson — see src/lib/litter-       */}
        {/* footprints.ts. Fade-in starts at SPOT_ZOOM.HEATMAP_FADE_OUT   */}
        {/* (the heatmap's own fade-OUT zoom) so the two layers hand off  */}
        {/* with no dead band, reaching full opacity at z15.5 (matches    */}
        {/* mobile's footprintFadeInFullZoom). No clustering — every      */}
        {/* observation is its own feature.                               */}
        {/* ============================================================ */}
        {observations.length > 0 && (
          <Source id="litter-footprints" type="geojson" data={footprintGeoJson}>
            <Layer
              id="litter-footprint-fill"
              type="fill"
              paint={{
                'fill-color': [
                  'match', ['get', 'domain'],
                  'vegetation', LITTER_FOOTPRINT_COLORS.vegetation,
                  'construction', LITTER_FOOTPRINT_COLORS.construction,
                  LITTER_FOOTPRINT_COLORS.other,
                ],
                // Zoom fade-in (0 -> full) times a diagnosed/observed target
                // (Pillar 8 Task 4 — mirrors GccMap.ios.kt's diagnosed/
                // observed split): diagnosed (solid, true OR the property
                // missing/legacy — see litter-footprints.ts's `diagnosed`
                // KDoc) uses the same per-domain full-opacity target as
                // before (vegetation/construction 0.18, other 0.20);
                // observed-only (hollow) drops to a flat 0.05 — faint fill,
                // full-strength outline below carries the "ring" signal.
                'fill-opacity': [
                  '*',
                  [
                    'interpolate', ['exponential', 1.5], ['zoom'],
                    LITTER_FOOTPRINT_ZOOM.FADE_IN_START, 0,
                    LITTER_FOOTPRINT_ZOOM.FADE_IN_FULL, 1,
                  ],
                  [
                    'case',
                    ['==', ['get', 'diagnosed'], false],
                    0.05,
                    [
                      'match', ['get', 'domain'],
                      'vegetation', 0.18,
                      'construction', 0.18,
                      0.20,
                    ],
                  ],
                ],
              }}
            />
            <Layer
              id="litter-footprint-outline"
              type="line"
              paint={{
                'line-color': [
                  'match', ['get', 'domain'],
                  'vegetation', LITTER_FOOTPRINT_COLORS.vegetation,
                  'construction', LITTER_FOOTPRINT_COLORS.construction,
                  LITTER_FOOTPRINT_COLORS.other,
                ],
                // Observed-only (hollow) gets a heavier outline than
                // diagnosed (solid) — same intent as GccMap.ios.kt's
                // 0.5dp/1.2dp split, so the ring reads as deliberate.
                'line-width': [
                  'case',
                  ['==', ['get', 'diagnosed'], false],
                  1.75,
                  1,
                ],
                'line-opacity': [
                  'interpolate', ['exponential', 1.5], ['zoom'],
                  LITTER_FOOTPRINT_ZOOM.FADE_IN_START, 0,
                  LITTER_FOOTPRINT_ZOOM.FADE_IN_FULL, 1,
                ],
              }}
            />
          </Source>
        )}

        {/* Coverage freshness overlay — grey never-scanned / amber stale cells,
            close-zoom-only and off by default (toggled via legend control above) */}
        {mapBounds && (
          <CoverageFreshnessOverlay bounds={mapBounds} zoom={currentZoom} visible={coverageOverlayVisible} />
        )}

        {/* ============================================================ */}
        {/* BOUNTY MARKERS                                              */}
        {/* ============================================================ */}

        {detailZoomOpacity > 0.01 && bounties.map((bounty) => (
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
                opacity: detailZoomOpacity,
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
