'use client'

import { ReactNode } from 'react'
import Map, { NavigationControl, ScaleControl } from 'react-map-gl/maplibre'
import { MAP_STYLE_URL, ZOOM } from '@/lib/design-tokens'
import 'maplibre-gl/dist/maplibre-gl.css'

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
}: BaseMapProps) {
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

// Reusable Legend component
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
