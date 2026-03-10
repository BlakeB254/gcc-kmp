'use client'

import { useState, useCallback, useMemo, useEffect, useRef } from 'react'
import Map, { Source, Layer, Marker, NavigationControl, ScaleControl } from 'react-map-gl/maplibre'
import type { MapLayerMouseEvent, MapRef } from 'react-map-gl/maplibre'
import {
  MAP_STYLE_URL,
  ROUTE_COLORS,
  ROUTE_WIDTHS,
  ROUTE_STATUS_COLORS,
} from '@/lib/design-tokens'
import { MAP_BOUNDS } from '@/data/blocks'
import 'maplibre-gl/dist/maplibre-gl.css'
import type { SponsorBlock } from './types'
import { Check } from 'lucide-react'

interface SponsorBlockMapProps {
  blocks: SponsorBlock[]
  selectedBlockIds: string[]
  onToggleBlock: (block: SponsorBlock) => void
  height?: string
}

/** Convert API block to GeoJSON feature with selection state */
function blockToFeature(block: SponsorBlock, isSelected: boolean) {
  const boundary = block.location?.boundary
  const center = block.location?.center

  if (!boundary || !center) return null

  return {
    type: 'Feature' as const,
    id: block.id,
    properties: {
      id: block.id,
      code: block.code,
      streetName: block.streetName,
      fromCorner: block.fromCorner,
      toCorner: block.toCorner,
      selected: isSelected,
    },
    geometry: boundary,
  }
}

/** Get polygon center for marker placement */
function getBlockCenter(block: SponsorBlock): [number, number] | null {
  if (block.location?.center) {
    return block.location.center.coordinates
  }
  return null
}

export function SponsorBlockMap({
  blocks,
  selectedBlockIds,
  onToggleBlock,
  height = '400px',
}: SponsorBlockMapProps) {
  const mapRef = useRef<MapRef>(null)
  const [mapLoaded, setMapLoaded] = useState(false)
  const [routeFeatures, setRouteFeatures] = useState<any[]>([])
  const [hoveredBlockId, setHoveredBlockId] = useState<string | null>(null)

  const selectedSet = useMemo(() => new Set(selectedBlockIds), [selectedBlockIds])

  // Blocks with valid geometry only
  const validBlocks = useMemo(
    () => blocks.filter(b => b.location?.boundary && b.location?.center),
    [blocks],
  )

  // Fetch routes for green line overlay
  useEffect(() => {
    async function fetchRoutes() {
      try {
        const res = await fetch('/api/routes?status=published')
        if (!res.ok) return
        const data = await res.json()
        const apiRoutes = data.data || data.routes || []
        setRouteFeatures(
          apiRoutes
            .filter((r: any) => r.geometry?.coordinates?.length > 0)
            .map((r: any) => ({
              type: 'Feature' as const,
              properties: {
                id: r.id,
                displayName: r.displayName,
                status: r.status || 'published',
              },
              geometry: r.geometry,
            })),
        )
      } catch {
        // Routes are optional visual context
      }
    }
    fetchRoutes()
  }, [])

  // Fit map to block bounds when map loads
  useEffect(() => {
    if (!mapLoaded || !mapRef.current || validBlocks.length === 0) return

    const timer = setTimeout(() => {
      mapRef.current?.fitBounds(
        [
          [MAP_BOUNDS.west - 0.001, MAP_BOUNDS.south - 0.001],
          [MAP_BOUNDS.east + 0.001, MAP_BOUNDS.north + 0.001],
        ],
        {
          padding: { top: 50, bottom: 50, left: 50, right: 50 },
          duration: 800,
        },
      )
    }, 100)
    return () => clearTimeout(timer)
  }, [mapLoaded, validBlocks.length])

  // Block polygon GeoJSON
  const blockGeoJson = useMemo(
    () => ({
      type: 'FeatureCollection' as const,
      features: validBlocks
        .map(b => blockToFeature(b, selectedSet.has(b.id)))
        .filter((f): f is NonNullable<typeof f> => f !== null),
    }),
    [validBlocks, selectedSet],
  )

  // Route line GeoJSON
  const routeGeoJson = useMemo(
    () => ({ type: 'FeatureCollection' as const, features: routeFeatures }),
    [routeFeatures],
  )

  // Handle polygon click
  const onClick = useCallback(
    (event: MapLayerMouseEvent) => {
      const feature = event.features?.[0]
      if (!feature?.properties?.id) return
      const block = blocks.find(b => String(b.id) === String(feature.properties!.id))
      if (block) onToggleBlock(block)
    },
    [blocks, onToggleBlock],
  )

  const onHover = useCallback((event: MapLayerMouseEvent) => {
    setHoveredBlockId(event.features?.[0]?.properties?.id || null)
  }, [])

  return (
    <div style={{ height, width: '100%' }} className="rounded-xl overflow-hidden relative">
      <Map
        ref={mapRef}
        initialViewState={{
          longitude: -87.7120,
          latitude: 41.8666,
          zoom: 15,
        }}
        style={{ width: '100%', height: '100%' }}
        mapStyle={MAP_STYLE_URL}
        interactiveLayerIds={['sponsor-blocks-fill']}
        onClick={onClick}
        onMouseMove={onHover}
        onMouseLeave={() => setHoveredBlockId(null)}
        onLoad={() => setMapLoaded(true)}
        cursor={hoveredBlockId ? 'pointer' : 'grab'}
        maxBounds={[
          [MAP_BOUNDS.west - 0.01, MAP_BOUNDS.south - 0.01],
          [MAP_BOUNDS.east + 0.01, MAP_BOUNDS.north + 0.01],
        ]}
        minZoom={13}
        maxZoom={18}
      >
        <NavigationControl position="top-right" />
        <ScaleControl position="bottom-left" />

        {/* ─── Route Lines (glow → casing → core → labels) ─── */}
        {routeFeatures.length > 0 && (
          <Source id="sponsor-routes" type="geojson" data={routeGeoJson}>
            <Layer
              id="sponsor-route-glow"
              type="line"
              layout={{ 'line-cap': 'round', 'line-join': 'round' }}
              paint={{
                'line-color': ROUTE_COLORS.GLOW,
                'line-width': 18,
                'line-opacity': 0.3,
              }}
            />
            <Layer
              id="sponsor-route-case"
              type="line"
              layout={{ 'line-cap': 'round', 'line-join': 'round' }}
              paint={{
                'line-color': ROUTE_STATUS_COLORS.published.casing,
                'line-width': 10,
                'line-opacity': 1,
              }}
            />
            <Layer
              id="sponsor-route-core"
              type="line"
              layout={{ 'line-cap': 'round', 'line-join': 'round' }}
              paint={{
                'line-color': ROUTE_COLORS.LINE,
                'line-width': 6,
                'line-opacity': 1,
              }}
            />
            <Layer
              id="sponsor-route-labels"
              type="symbol"
              layout={{
                'symbol-placement': 'line-center',
                'text-field': ['get', 'displayName'],
                'text-size': 11,
                'text-font': ['Open Sans Bold', 'Arial Unicode MS Bold'],
                'text-max-width': 14,
              }}
              paint={{
                'text-color': '#ffffff',
                'text-halo-color': ROUTE_STATUS_COLORS.published.casing,
                'text-halo-width': 2,
              }}
            />
          </Source>
        )}

        {/* ─── Block Polygons ─── */}
        <Source id="sponsor-blocks" type="geojson" data={blockGeoJson}>
          {/* Fill — green default, gold when selected */}
          <Layer
            id="sponsor-blocks-fill"
            type="fill"
            paint={{
              'fill-color': [
                'case',
                ['==', ['get', 'selected'], true],
                '#D4A017',
                '#22C55E',
              ],
              'fill-opacity': [
                'case',
                ['==', ['get', 'id'], hoveredBlockId || ''],
                0.4,
                ['==', ['get', 'selected'], true],
                0.45,
                0.15,
              ],
            }}
          />
          {/* Outline */}
          <Layer
            id="sponsor-blocks-outline"
            type="line"
            paint={{
              'line-color': [
                'case',
                ['==', ['get', 'selected'], true],
                '#B8860B',
                '#14532D',
              ],
              'line-width': [
                'case',
                ['==', ['get', 'id'], hoveredBlockId || ''],
                4,
                ['==', ['get', 'selected'], true],
                3.5,
                2,
              ],
              'line-opacity': [
                'case',
                ['==', ['get', 'selected'], true],
                1,
                0.5,
              ],
            }}
          />
        </Source>

        {/* ─── Checkbox Markers at block centers ─── */}
        {validBlocks.map(block => {
          const center = getBlockCenter(block)
          if (!center) return null
          const isSelected = selectedSet.has(block.id)

          return (
            <Marker
              key={block.id}
              longitude={center[0]}
              latitude={center[1]}
              anchor="center"
              onClick={(e) => {
                e.originalEvent.stopPropagation()
                onToggleBlock(block)
              }}
            >
              <button
                className={`w-7 h-7 rounded-full border-2 flex items-center justify-center transition-all duration-200 shadow-md ${
                  isSelected
                    ? 'bg-brand-gold-500 border-brand-gold-700 scale-110'
                    : 'bg-white/90 border-neutral-400 hover:border-brand-gold-500 hover:scale-105'
                }`}
                aria-label={`${isSelected ? 'Deselect' : 'Select'} block ${block.fromCorner} to ${block.toCorner}`}
              >
                {isSelected && <Check className="w-4 h-4 text-white" strokeWidth={3} />}
              </button>
            </Marker>
          )
        })}
      </Map>

      {/* Selection count overlay */}
      {selectedBlockIds.length > 0 && (
        <div className="absolute top-3 left-3 z-10 bg-brand-gold-500 text-white px-3 py-1.5 rounded-lg shadow-md text-sm font-semibold">
          {selectedBlockIds.length} block{selectedBlockIds.length !== 1 ? 's' : ''} selected
        </div>
      )}
    </div>
  )
}
