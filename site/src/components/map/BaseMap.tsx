'use client'

import { ReactNode, useState, useCallback } from 'react'
import dynamic from 'next/dynamic'
import Map, { NavigationControl, ScaleControl } from 'react-map-gl/maplibre'
import { MAP_STYLE_URL, ZOOM } from '@/lib/design-tokens'
import 'maplibre-gl/dist/maplibre-gl.css'

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
  // Starts false — let MapLibre try. Flips to true the moment MapLibre fires
  // any error whose type is webglcontextcreationerror (GPU blacklisted/sandboxed).
  const [useLeaflet, setUseLeaflet] = useState(false)

  const handleMapError = useCallback((evt: any) => {
    if (evt?.error?.type === 'webglcontextcreationerror' || evt?.type === 'error') {
      setUseLeaflet(true)
    }
  }, [])

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

  if (useLeaflet) return leafletFallback

  return (
    <div style={{ height, width: '100%' }} className={`rounded-xl overflow-hidden relative ${className}`}>
      <Map
        ref={mapRef}
        initialViewState={initialViewState}
        mapStyle={MAP_STYLE_URL}
        interactiveLayerIds={interactiveLayerIds}
        onClick={onClick}
        onMouseEnter={onMouseEnter}
        onMouseLeave={onMouseLeave}
        cursor={cursor}
        onError={handleMapError}
      >
        <NavigationControl position="top-right" />
        <ScaleControl position="bottom-left" />
        {children}
        {popup}
      </Map>
      {legend}
    </div>
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
