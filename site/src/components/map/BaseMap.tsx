'use client'

import { ReactNode, Component, useState, useEffect } from 'react'
import dynamic from 'next/dynamic'
import Map, { NavigationControl, ScaleControl } from 'react-map-gl/maplibre'
import { MAP_STYLE_URL, ZOOM } from '@/lib/design-tokens'
import 'maplibre-gl/dist/maplibre-gl.css'

// ─── WebGL detection ──────────────────────────────────────────────────────────
//
// Permissive check: succeeds whenever ANY WebGL context can be created. We
// deliberately do NOT pass `failIfMajorPerformanceCaveat: true` — that flag
// returned null on browsers/GPUs that COULD render MapLibre just fine, which
// was sending users on the bounties page to the Leaflet fallback even though
// the basemap would have worked. Real runtime failures are caught by
// MapErrorBoundary + the Map's onError callback below.
function canUseWebGL(): boolean {
  if (typeof window === 'undefined') return true
  try {
    const canvas = document.createElement('canvas')
    const gl = canvas.getContext('webgl2') || canvas.getContext('webgl')
    return gl !== null
  } catch {
    return false
  }
}

// ─── Leaflet fallback (lazy-loaded, SSR disabled) ────────────────────────────

export interface FallbackGeoJSONLayer {
  geojson: GeoJSON.FeatureCollection
  style: (feature: GeoJSON.Feature) => {
    color?: string
    fillColor?: string
    fillOpacity?: number
    weight?: number
    opacity?: number
  }
  onEachFeature?: (feature: GeoJSON.Feature, layer: any) => void
}

const LeafletFallback = dynamic(() => import('./LeafletFallback'), { ssr: false })

// ─── MapLibre error boundary ─────────────────────────────────────────────────

class MapErrorBoundary extends Component<
  { children: ReactNode; fallback: ReactNode },
  { hasError: boolean }
> {
  constructor(props: { children: ReactNode; fallback: ReactNode }) {
    super(props)
    this.state = { hasError: false }
  }
  static getDerivedStateFromError() { return { hasError: true } }
  render() {
    return this.state.hasError ? this.props.fallback : this.props.children
  }
}

// ─── BaseMap ──────────────────────────────────────────────────────────────────

interface BaseMapProps {
  height?: string
  initialViewState?: {
    longitude: number
    latitude: number
    zoom: number
  }
  interactiveLayerIds?: string[]
  onClick?: (e: any) => void
  onMove?: (e: any) => void
  onMouseEnter?: (e: any) => void
  onMouseLeave?: (e: any) => void
  cursor?: string
  children?: ReactNode  // Sources, Layers, Markers go here
  popup?: ReactNode     // Popup content
  legend?: ReactNode    // Legend content
  className?: string
  mapRef?: (ref: any) => void
  /** GeoJSON layers passed to the Leaflet fallback when WebGL is unavailable */
  fallbackLayers?: FallbackGeoJSONLayer[]
}

export function BaseMap({
  height = '500px',
  initialViewState = {
    longitude: -87.72,
    latitude: 41.86,
    zoom: ZOOM.NEIGHBORHOOD,
  },
  interactiveLayerIds,
  onClick,
  onMove,
  onMouseEnter,
  onMouseLeave,
  cursor,
  children,
  popup,
  legend,
  className = '',
  mapRef,
  fallbackLayers,
}: BaseMapProps) {
  const [webGLAvailable, setWebGLAvailable] = useState<boolean | null>(null)

  useEffect(() => {
    setWebGLAvailable(canUseWebGL())
  }, [])

  // Skeleton while client-side check runs (avoids SSR hydration mismatch)
  if (webGLAvailable === null) {
    return (
      <div style={{ height, width: '100%' }} className={`rounded-xl bg-neutral-100 animate-pulse ${className}`} />
    )
  }

  const leafletFallback = (
    <LeafletFallback
      height={height}
      center={[initialViewState.latitude, initialViewState.longitude]}
      zoom={initialViewState.zoom}
      layers={fallbackLayers}
      className={className}
      legend={legend}
    />
  )

  if (!webGLAvailable) return leafletFallback

  return (
    <MapErrorBoundary fallback={leafletFallback}>
      <div style={{ height, width: '100%' }} className={`rounded-xl overflow-hidden relative ${className}`}>
        <Map
          ref={mapRef}
          initialViewState={initialViewState}
          mapStyle={MAP_STYLE_URL}
          interactiveLayerIds={interactiveLayerIds}
          onClick={onClick}
          onMove={onMove}
          onMouseEnter={onMouseEnter}
          onMouseLeave={onMouseLeave}
          onError={(e) => {
            // Log for visibility, then fall back to Leaflet on real errors.
            // The working pre-session design relied on this fallback so
            // browsers where MapLibre genuinely can't render still get a
            // usable map (Leaflet's OSM tiles). The earlier "log only"
            // version of this handler left those users with an empty
            // canvas (basemap tiles never paint) — verifiably worse than
            // the fallback behavior even though that fallback loses the
            // MapLibre Source/Layer overlays. Net: real errors are rare
            // enough that the fallback for those few users beats silently
            // showing them broken canvas.
            console.warn('[BaseMap] MapLibre runtime error — falling back to Leaflet', e)
            setWebGLAvailable(false)
          }}
          cursor={cursor}
        >
          <NavigationControl position="top-right" />
          <ScaleControl position="bottom-left" />
          {children}
          {popup}
        </Map>
        {legend}
      </div>
    </MapErrorBoundary>
  )
}

// ─── MapLegend ────────────────────────────────────────────────────────────────

interface LegendItem {
  color: string
  label: string
}

interface MapLegendProps {
  items: LegendItem[]
  title?: string
}

export function MapLegend({ items, title }: MapLegendProps) {
  return (
    <div className="absolute bottom-8 left-3 bg-white/95 backdrop-blur-sm rounded-lg p-3 text-xs shadow-md z-10">
      {title && <div className="font-semibold mb-1.5">{title}</div>}
      <div className="space-y-1">
        {items.map(item => (
          <div key={item.label} className="flex items-center gap-2">
            <span
              className="w-3 h-3 rounded-full inline-block flex-shrink-0"
              style={{ backgroundColor: item.color }}
            />
            <span className="text-neutral-600">{item.label}</span>
          </div>
        ))}
      </div>
    </div>
  )
}
