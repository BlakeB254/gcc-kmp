'use client'

// Leaflet is canvas-based (no WebGL) — this renders when WebGL is unavailable.
// Loaded via next/dynamic with ssr:false because Leaflet reads window at import time.

import { useEffect, useRef } from 'react'
import type { FallbackGeoJSONLayer } from './BaseMap'
import 'leaflet/dist/leaflet.css'

interface LeafletFallbackProps {
  height: string
  center: [number, number]
  zoom: number
  layers?: FallbackGeoJSONLayer[]
  className?: string
  legend?: React.ReactNode
}

export default function LeafletFallback({
  height,
  center,
  zoom,
  layers,
  className,
  legend,
}: LeafletFallbackProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<any>(null)

  useEffect(() => {
    if (!containerRef.current || mapRef.current) return

    // Dynamic import so Leaflet only loads client-side
    import('leaflet').then((L) => {
      // Fix default marker icon paths broken by webpack/turbopack module resolution
      delete (L.Icon.Default.prototype as any)._getIconUrl
      L.Icon.Default.mergeOptions({
        iconRetinaUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon-2x.png',
        iconUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon.png',
        shadowUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-shadow.png',
      })

      const map = L.map(containerRef.current!, {
        center,
        zoom,
        zoomControl: true,
      })

      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
        maxZoom: 19,
      }).addTo(map)

      // Render caller-supplied GeoJSON layers with per-feature styling.
      //
      // Points get rendered as circle markers (using the caller's style.color
      // for fill) instead of Leaflet's default blue pin icon. Lines and
      // polygons use Leaflet's regular path styling. onEachFeature still
      // fires so callers can bind popups regardless of geometry type.
      if (layers) {
        for (const { geojson, style, onEachFeature } of layers) {
          L.geoJSON(geojson as any, {
            style: (feature) => ({
              weight: 2,
              opacity: 0.9,
              ...style(feature!),
            }),
            pointToLayer: (feature, latlng) => {
              const s = style(feature)
              return L.circleMarker(latlng, {
                radius: 7,
                color: '#ffffff',
                weight: 1.5,
                fillColor: s.fillColor ?? s.color ?? '#F97316',
                fillOpacity: s.fillOpacity ?? 0.9,
              })
            },
            onEachFeature,
          }).addTo(map)
        }
      }

      mapRef.current = map
    })

    return () => {
      mapRef.current?.remove()
      mapRef.current = null
    }
  }, []) // run once on mount; center/zoom/layers are stable

  return (
    <div style={{ height, width: '100%' }} className={`rounded-xl overflow-hidden relative ${className ?? ''}`}>
      <div ref={containerRef} style={{ height: '100%', width: '100%' }} />
      {legend}
    </div>
  )
}
