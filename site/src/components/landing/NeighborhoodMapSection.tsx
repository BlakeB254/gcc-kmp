'use client'

import { useState, useCallback, useMemo, useRef, useEffect } from 'react'
import Map, { Source, Layer, NavigationControl, ScaleControl } from 'react-map-gl/maplibre'
import type { MapLayerMouseEvent, MapRef } from 'react-map-gl/maplibre'
import Link from 'next/link'
import 'maplibre-gl/dist/maplibre-gl.css'
import { MAP_STYLE_URL, BLOCK_STATUS_COLORS, ROUTE_COLORS as DESIGN_ROUTE_COLORS, ROUTE_WIDTHS, ROUTE_STATUS_COLORS, BOUNTY_COLORS, SPOT_COLORS, SPOT_STATUS_COLORS, SPOT_ZOOM } from '@/lib/design-tokens'
import { NORTH_LAWNDALE_BLOCKS, MAP_BOUNDS, type BlockDefinition } from '@/data/blocks'

/**
 * NeighborhoodMapSection Component
 *
 * Uses MapLibre GL JS (open source) with free OpenStreetMap tiles.
 * No API key required - completely free and open source!
 *
 * Features:
 * - Fetches blocks from database API (falls back to hardcoded data)
 * - Auto-fits to show all 10 blocks
 * - Service area boundary outline
 * - Color-coded status system
 * - Interactive block selection
 */

// Map style from shared design tokens (OpenFreeMap Liberty)
const MAP_STYLE = MAP_STYLE_URL

// Block status colors - derived from shared design tokens
const STATUS_COLORS = {
  unfunded: BLOCK_STATUS_COLORS.unfunded.fill,
  available: BLOCK_STATUS_COLORS.available.fill,
  funded: BLOCK_STATUS_COLORS.available.fill,       // funded = available (alias)
  claimed: BLOCK_STATUS_COLORS.claimed.fill,
  in_progress: BLOCK_STATUS_COLORS.in_progress.fill,
  pending_review: BLOCK_STATUS_COLORS.pending_review.fill,
  completed: BLOCK_STATUS_COLORS.completed.fill,
} as const

// Simplified legend for the map key - colors from design tokens
const LEGEND_STATUSES = {
  unfunded: { label: 'Needs Sponsor', icon: '💰', color: BLOCK_STATUS_COLORS.unfunded.fill },
  available: { label: 'Available Now', icon: '✅', color: BLOCK_STATUS_COLORS.available.fill },
  not_available: { label: 'Not Available', icon: '🚫', color: BLOCK_STATUS_COLORS.claimed.fill },
  completed: { label: 'Completed', icon: '🏆', color: BLOCK_STATUS_COLORS.completed.fill },
} as const

// Full label map for popups and detail views
const STATUS_LABELS = {
  unfunded: 'Needs Sponsor',
  available: 'Available Now',
  funded: 'Available Now',
  claimed: 'Not Available',
  in_progress: 'Not Available',
  pending_review: 'Under Review',
  completed: 'Completed',
} as const

const STATUS_ICONS = {
  unfunded: '💰',
  available: '✅',
  funded: '✅',
  claimed: '🚫',
  in_progress: '🧹',
  pending_review: '⏳',
  completed: '🏆',
} as const

/**
 * Format street name to include block number (1500) for Roosevelt Rd blocks.
 * Handles both old database records and new ones.
 */
function formatStreetName(streetName: string | undefined | null): string {
  if (!streetName) return ''
  // If it already has 1500 or doesn't contain Roosevelt, return as-is
  if (streetName.includes('1500') || !streetName.toLowerCase().includes('roosevelt')) {
    return streetName
  }
  // Add 1500 to W Roosevelt Rd names
  return streetName.replace(/^W\s+Roosevelt/i, '1500 W Roosevelt')
}

type BlockStatus = keyof typeof STATUS_COLORS

// API response block type
interface APIBlock {
  id: string
  code: string
  name: string
  status: string
  location: {
    neighborhood: string
    boundary: { type: 'Polygon'; coordinates: [number, number][][] } | null
    center: { type: 'Point'; coordinates: [number, number] } | null
  }
  displayImage: string | null
  streetName: string
  fromCorner: string
  toCorner: string
  side: string
  funding: {
    goal: number
    current: number
  }
}

interface BlockWithStatus {
  id: string
  code: string
  streetName: string
  fromCorner: string
  toCorner: string
  side: 'north' | 'south'
  status: BlockStatus
  displayImage: string | null
  geo: {
    center: { type: 'Point'; coordinates: [number, number] }
    polygon: { type: 'Polygon'; coordinates: [number, number][][] }
  }
}

// Service area boundary polygon (encompasses all blocks)
const SERVICE_AREA_BOUNDARY = {
  type: 'Feature' as const,
  properties: { name: 'GCC Service Area' },
  geometry: {
    type: 'Polygon' as const,
    coordinates: [[
      [MAP_BOUNDS.east + 0.0005, MAP_BOUNDS.north + 0.0003],
      [MAP_BOUNDS.west - 0.0005, MAP_BOUNDS.north + 0.0003],
      [MAP_BOUNDS.west - 0.0005, MAP_BOUNDS.south - 0.0003],
      [MAP_BOUNDS.east + 0.0005, MAP_BOUNDS.south - 0.0003],
      [MAP_BOUNDS.east + 0.0005, MAP_BOUNDS.north + 0.0003],
    ]],
  },
}

// Convert API block to display format
function apiBlockToDisplayBlock(block: APIBlock): BlockWithStatus | null {
  if (!block.location.boundary || !block.location.center) return null

  // Map database status to display status
  let displayStatus: BlockStatus = 'unfunded'
  if (block.status === 'available') {
    // Check if funded (has funding)
    displayStatus = block.funding.current >= block.funding.goal ? 'available' : 'unfunded'
  } else if (block.status === 'funded') {
    displayStatus = 'available' // Funded blocks are available for workers
  } else if (block.status === 'in_progress') {
    displayStatus = 'in_progress'
  } else if (block.status === 'completed') {
    displayStatus = 'completed'
  }

  return {
    id: block.id,
    code: block.code,
    streetName: block.streetName || block.name,
    fromCorner: block.fromCorner || '',
    toCorner: block.toCorner || '',
    side: (block.side as 'north' | 'south') || 'north',
    status: displayStatus,
    displayImage: block.displayImage || null,
    geo: {
      center: block.location.center,
      polygon: block.location.boundary,
    },
  }
}

// Convert hardcoded block to display format (fallback)
function hardcodedBlockToDisplayBlock(block: BlockDefinition): BlockWithStatus {
  return {
    id: block.code,
    code: block.code,
    streetName: block.streetName,
    fromCorner: block.fromCorner,
    toCorner: block.toCorner,
    side: block.side,
    status: 'unfunded', // Default status for hardcoded blocks
    displayImage: null,
    geo: block.geo,
  }
}

export function NeighborhoodMapSection() {
  const mapRef = useRef<MapRef>(null)
  const [selectedBlock, setSelectedBlock] = useState<BlockWithStatus | null>(null)
  const [hoveredBlockId, setHoveredBlockId] = useState<string | null>(null)
  const [mapLoaded, setMapLoaded] = useState(false)
  const [blocksWithStatus, setBlocksWithStatus] = useState<BlockWithStatus[]>([])
  const [routeFeatures, setRouteFeatures] = useState<any[]>([])
  const [bountyFeatures, setBountyFeatures] = useState<any[]>([])
  const [spotFeatures, setSpotFeatures] = useState<any[]>([])
  const [spotRawData, setSpotRawData] = useState<any[]>([])
  const [selectedSpot, setSelectedSpot] = useState<any | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  // Fetch blocks from database API, fall back to hardcoded data
  useEffect(() => {
    async function fetchBlocks() {
      try {
        const res = await fetch('/api/blocks?limit=50')
        if (res.ok) {
          const data = await res.json()
          const apiBlocks = data.data || data.blocks || []
          if (apiBlocks.length > 0) {
            // Use database blocks
            const dbBlocks = apiBlocks
              .map(apiBlockToDisplayBlock)
              .filter((b: BlockWithStatus | null): b is BlockWithStatus => b !== null)

            if (dbBlocks.length > 0) {
              setBlocksWithStatus(dbBlocks)
              setIsLoading(false)
              return
            }
          }
        }
      } catch (error) {
        console.warn('Failed to fetch blocks from API, using fallback:', error)
      }

      // Fallback to hardcoded blocks
      const fallbackBlocks = NORTH_LAWNDALE_BLOCKS.map(hardcodedBlockToDisplayBlock)
      setBlocksWithStatus(fallbackBlocks)
      setIsLoading(false)
    }

    fetchBlocks()
  }, [])

  // Fetch routes from database API for green line overlay
  useEffect(() => {
    async function fetchRoutes() {
      try {
        const res = await fetch('/api/routes?status=published')
        if (!res.ok) return
        const data = await res.json()
        const apiRoutes = data.data || data.routes || []
        if (apiRoutes.length > 0) {
          // Convert to GeoJSON features for the map layer
          const features = apiRoutes
            .filter((r: any) => r.geometry && r.geometry.coordinates && r.geometry.coordinates.length > 0)
            .map((r: any) => ({
              type: 'Feature' as const,
              properties: {
                id: r.id,
                code: r.code,
                displayName: r.displayName,
                status: r.status || 'published',
              },
              geometry: r.geometry,
            }))
          setRouteFeatures(features)
        }
      } catch (error) {
        console.warn('Failed to fetch routes for map overlay:', error)
      }
    }
    fetchRoutes()
  }, [])

  // Fetch bounties (community reports mapped as bounties)
  useEffect(() => {
    async function fetchBounties() {
      try {
        const res = await fetch('/api/bounties')
        if (!res.ok) return
        const data = await res.json()
        const items = data.bounties || data.data || []
        if (items.length > 0) {
          const features = items
            .filter((b: any) => b.coordinates || (b.locationLat && b.locationLng))
            .map((b: any) => {
              const lat = b.locationLat ?? b.coordinates?.lat ?? 0
              const lng = b.locationLng ?? b.coordinates?.lng ?? 0
              return {
                type: 'Feature' as const,
                properties: {
                  id: b.id,
                  status: b.status || 'open',
                  amount: b.amountCents ? `$${Math.round(b.amountCents / 100)}` : b.amount ? `$${b.amount}` : '',
                },
                geometry: { type: 'Point' as const, coordinates: [lng, lat] },
              }
            })
          setBountyFeatures(features)
        }
      } catch (error) {
        console.warn('Failed to fetch bounties:', error)
      }
    }
    fetchBounties()
  }, [])

  // Fetch spot reports
  useEffect(() => {
    async function fetchSpots() {
      try {
        const res = await fetch('/api/spots')
        if (!res.ok) return
        const data = await res.json()
        const mapItems = data.spots || []
        const fullReports = data.data || []
        if (mapItems.length > 0) {
          const features = mapItems
            .filter((s: any) => s.coordinates)
            .map((s: any) => {
              const coords = s.coordinates
              const lat = Array.isArray(coords) ? coords[1] : coords?.lat ?? 0
              const lng = Array.isArray(coords) ? coords[0] : coords?.lng ?? 0
              // Find matching full report for status
              const fullReport = fullReports.find((r: any) => String(r.id) === String(s.id))
              const status = fullReport?.status || 'pending'
              return {
                type: 'Feature' as const,
                properties: {
                  id: s.id,
                  label: s.label || s.type || 'report',
                  radiusMeters: s.radiusMeters || s.affectedRadiusMeters || 15,
                  needsVerification: s.needsVerification ? 1 : 0,
                  status,
                },
                geometry: { type: 'Point' as const, coordinates: [lng, lat] },
              }
            })
          setSpotFeatures(features)
          setSpotRawData(fullReports)
        }
      } catch (error) {
        console.warn('Failed to fetch spots:', error)
      }
    }
    fetchSpots()
  }, [])

  // Route GeoJSON for green line overlay
  const routeGeoJson = useMemo(() => ({
    type: 'FeatureCollection' as const,
    features: routeFeatures,
  }), [routeFeatures])

  // Bounty GeoJSON
  const bountyGeoJson = useMemo(() => ({
    type: 'FeatureCollection' as const,
    features: bountyFeatures,
  }), [bountyFeatures])

  // Spot GeoJSON
  const spotGeoJson = useMemo(() => ({
    type: 'FeatureCollection' as const,
    features: spotFeatures,
  }), [spotFeatures])

  // Convert to GeoJSON for MapLibre
  const geojsonData = useMemo(() => ({
    type: 'FeatureCollection' as const,
    features: blocksWithStatus.map((block) => ({
      type: 'Feature' as const,
      id: block.id,
      properties: {
        id: block.id,
        code: block.code,
        streetName: block.streetName,
        fromCorner: block.fromCorner,
        toCorner: block.toCorner,
        side: block.side,
        status: block.status,
        color: STATUS_COLORS[block.status] || STATUS_COLORS.unfunded,
        isAvailable: block.status === 'available' || block.status === 'funded',
        isUnfunded: block.status === 'unfunded',
      },
      geometry: block.geo.polygon,
    })),
  }), [blocksWithStatus])

  // Fit map to bounds when loaded
  useEffect(() => {
    if (mapLoaded && mapRef.current) {
      mapRef.current.fitBounds(
        [
          [MAP_BOUNDS.west - 0.001, MAP_BOUNDS.south - 0.001],
          [MAP_BOUNDS.east + 0.001, MAP_BOUNDS.north + 0.001],
        ],
        {
          padding: { top: 50, bottom: 50, left: 50, right: 50 },
          duration: 1000,
        }
      )
    }
  }, [mapLoaded])

  // Zoom to fit a block's polygon
  const zoomToBlock = useCallback((block: BlockWithStatus) => {
    const map = mapRef.current
    if (!map) return
    const coords = block.geo.polygon.coordinates[0]
    const lngs = coords.map(c => c[0])
    const lats = coords.map(c => c[1])
    map.fitBounds(
      [
        [Math.min(...lngs) - 0.0003, Math.min(...lats) - 0.0003],
        [Math.max(...lngs) + 0.0003, Math.max(...lats) + 0.0003],
      ],
      { padding: { top: 80, bottom: 160, left: 50, right: 50 }, duration: 600 }
    )
  }, [])

  // Reset to the full 10-block overview
  const resetMapView = useCallback(() => {
    const map = mapRef.current
    if (!map) return
    map.fitBounds(
      [
        [MAP_BOUNDS.west - 0.001, MAP_BOUNDS.south - 0.001],
        [MAP_BOUNDS.east + 0.001, MAP_BOUNDS.north + 0.001],
      ],
      { padding: { top: 50, bottom: 50, left: 50, right: 50 }, duration: 600 }
    )
  }, [])

  const onClick = useCallback((event: MapLayerMouseEvent) => {
    const feature = event.features?.[0]
    if (!feature || !feature.properties) return

    const layerId = feature.layer?.id || ''

    // Spot click
    if (layerId.includes('spot')) {
      const spotId = feature.properties.id
      const fullReport = spotRawData.find((s: any) => String(s.id) === String(spotId))
      if (fullReport) {
        setSelectedSpot(fullReport)
        setSelectedBlock(null)
      }
      return
    }

    // Block click
    const block = blocksWithStatus.find(b => b.id === feature.properties?.id)
    if (block) {
      setSelectedBlock(block)
      setSelectedSpot(null)
      zoomToBlock(block)
    }
  }, [blocksWithStatus, spotRawData, zoomToBlock])

  const onHover = useCallback((event: MapLayerMouseEvent) => {
    const feature = event.features?.[0]
    setHoveredBlockId(feature?.properties?.id || null)
  }, [])

  // Stats for display
  const stats = useMemo(() => {
    const counts = blocksWithStatus.reduce((acc, block) => {
      acc[block.status] = (acc[block.status] || 0) + 1
      return acc
    }, {} as Record<string, number>)

    return {
      available: (counts.available || 0) + (counts.funded || 0),
      inProgress: (counts.claimed || 0) + (counts.in_progress || 0),
      completed: counts.completed || 0,
      needSponsors: counts.unfunded || 0,
    }
  }, [blocksWithStatus])

  // Show loading state
  if (isLoading) {
    return (
      <section className="py-24 bg-neutral-50">
        <div className="section-container">
          <div className="text-center mb-12">
            <h2 className="text-3xl sm:text-4xl font-bold text-neutral-900 mb-4">
              Our Launch Area: Roosevelt Road
            </h2>
            <p className="text-lg text-neutral-600">Loading blocks...</p>
          </div>
          <div className="h-[500px] bg-neutral-200 rounded-2xl animate-pulse" />
        </div>
      </section>
    )
  }

  return (
    <section className="py-24 bg-neutral-50">
      <div className="section-container">
        {/* Section header */}
        <div className="text-center mb-12">
          <h2 className="text-3xl sm:text-4xl font-bold text-neutral-900 mb-4">
            Our Launch Area: Roosevelt Road
          </h2>
          <p className="text-lg text-neutral-600 max-w-2xl mx-auto">
            10 blocks along Roosevelt Road from Kedzie Ave to Lawndale Ave.
            <span className="block mt-2 font-medium text-brand-green-600">
              Click any block to see details or take action.
            </span>
          </p>
        </div>

        {/* Legend — Blocks */}
        <div className="flex flex-wrap justify-center gap-3 mb-4">
          <span className="text-xs font-semibold text-neutral-400 uppercase tracking-wider self-center mr-1">Blocks:</span>
          {Object.entries(LEGEND_STATUSES).map(([status, { label, icon, color }]) => (
            <div
              key={status}
              className={`
                flex items-center gap-2 px-3 py-1.5 rounded-full text-sm
                ${status === 'available' ? 'bg-green-100 ring-2 ring-green-500' : 'bg-white'}
                shadow-sm
              `}
            >
              <span>{icon}</span>
              <span
                className="w-3 h-3 rounded-full"
                style={{ backgroundColor: color }}
              />
              <span className={`${status === 'available' ? 'font-semibold text-green-700' : 'text-neutral-600'}`}>
                {label}
              </span>
            </div>
          ))}
        </div>
        {/* Legend — Spots */}
        <div className="flex flex-wrap justify-center gap-3 mb-8">
          <span className="text-xs font-semibold text-neutral-400 uppercase tracking-wider self-center mr-1">Spots:</span>
          {[
            { color: '#F59E0B', label: 'Pending', icon: '⏳' },
            { color: '#EF4444', label: 'Confirmed Dirty', icon: '🔴' },
            { color: '#3B82F6', label: 'Being Cleaned', icon: '🧹' },
            { color: '#22C55E', label: 'Resolved', icon: '✅' },
          ].map(({ color, label, icon }) => (
            <div key={label} className="flex items-center gap-2 px-3 py-1.5 rounded-full text-sm bg-white shadow-sm">
              <span>{icon}</span>
              <span className="w-3 h-3 rounded-full" style={{ backgroundColor: color }} />
              <span className="text-neutral-600">{label}</span>
            </div>
          ))}
        </div>

        {/* Map container */}
        <div className="relative rounded-2xl overflow-hidden shadow-xl border-4 border-brand-gold-500">
          <div className="h-[500px] sm:h-[600px]">
            <Map
              ref={mapRef}
              initialViewState={{
                longitude: -87.7120,
                latitude: 41.8666,
                zoom: 15,
              }}
              style={{ width: '100%', height: '100%' }}
              mapStyle={MAP_STYLE}
              interactiveLayerIds={['blocks-fill', 'homepage-spot-dots', 'homepage-spot-labels']}
              onClick={onClick}
              onMouseMove={onHover}
              onMouseLeave={() => setHoveredBlockId(null)}
              onLoad={() => setMapLoaded(true)}
              cursor={hoveredBlockId ? 'pointer' : 'grab'}
              maxBounds={[
                [MAP_BOUNDS.west - 0.01, MAP_BOUNDS.south - 0.01],
                [MAP_BOUNDS.east + 0.01, MAP_BOUNDS.north + 0.01],
              ]}
              minZoom={11}
              maxZoom={18}
            >
              <NavigationControl position="top-right" />
              <ScaleControl position="bottom-left" />

              {/* Service Area Boundary */}
              <Source id="service-area" type="geojson" data={SERVICE_AREA_BOUNDARY}>
                <Layer
                  id="service-area-outline"
                  type="line"
                  paint={{
                    'line-color': '#B8860B',
                    'line-width': 3,
                    'line-dasharray': [4, 2],
                  }}
                />
              </Source>

              {/* Block polygons — status-colored fill with visible outline */}
              <Source id="blocks" type="geojson" data={geojsonData}>
                <Layer
                  id="blocks-fill"
                  type="fill"
                  paint={{
                    'fill-color': ['get', 'color'],
                    'fill-opacity': [
                      'case',
                      ['==', ['get', 'id'], hoveredBlockId || ''],
                      0.35,
                      ['==', ['get', 'id'], selectedBlock?.id || ''],
                      0.3,
                      0.18,
                    ],
                  }}
                />
                {/* Block outlines — status-colored borders */}
                <Layer
                  id="blocks-outline"
                  type="line"
                  paint={{
                    'line-color': ['get', 'color'],
                    'line-width': [
                      'case',
                      ['==', ['get', 'id'], hoveredBlockId || ''],
                      4,
                      ['==', ['get', 'id'], selectedBlock?.id || ''],
                      3.5,
                      2.5,
                    ],
                    'line-opacity': 0.7,
                  }}
                />
              </Source>

              {/* Route Lines — 3-layer stack (glow → casing → core), more pronounced */}
              <Source id="homepage-routes" type="geojson" data={routeGeoJson}>
                {/* Route glow — wide semi-transparent halo */}
                <Layer
                  id="homepage-route-glow"
                  type="line"
                  layout={{ 'line-cap': 'round', 'line-join': 'round' }}
                  paint={{
                    'line-color': DESIGN_ROUTE_COLORS.GLOW,
                    'line-width': 18,
                    'line-opacity': 0.3,
                  }}
                />
                {/* Route casing — dark border for depth */}
                <Layer
                  id="homepage-route-case"
                  type="line"
                  layout={{ 'line-cap': 'round', 'line-join': 'round' }}
                  paint={{
                    'line-color': ROUTE_STATUS_COLORS.published.casing,
                    'line-width': 10,
                    'line-opacity': 1,
                  }}
                />
                {/* Route core — bright green main line */}
                <Layer
                  id="homepage-route-fill"
                  type="line"
                  layout={{ 'line-cap': 'round', 'line-join': 'round' }}
                  paint={{
                    'line-color': DESIGN_ROUTE_COLORS.LINE,
                    'line-width': 6,
                    'line-opacity': 1,
                  }}
                />
                {/* Route labels — street names along the lines */}
                <Layer
                  id="homepage-route-labels"
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

              {/* Bounty markers — red dots (renders when bounty data exists) */}
              <Source id="homepage-bounties" type="geojson" data={bountyGeoJson}>
                <Layer
                  id="homepage-bounty-dots"
                  type="circle"
                  paint={{
                    'circle-color': BOUNTY_COLORS.OPEN,
                    'circle-radius': 7,
                    'circle-stroke-color': '#ffffff',
                    'circle-stroke-width': 2,
                    'circle-opacity': 0.9,
                  }}
                />
                <Layer
                  id="homepage-bounty-labels"
                  type="symbol"
                  layout={{
                    'text-field': ['get', 'amount'],
                    'text-size': 11,
                    'text-font': ['Open Sans Bold', 'Arial Unicode MS Bold'],
                    'text-offset': [0, -1.5],
                    'text-allow-overlap': true,
                  }}
                  paint={{
                    'text-color': BOUNTY_COLORS.OPEN,
                    'text-halo-color': '#ffffff',
                    'text-halo-width': 1.5,
                  }}
                />
              </Source>

              {/* Spot reports — heatmap (zoomed out) + dots (zoomed in) */}
              <Source id="homepage-spots" type="geojson" data={spotGeoJson}>
                {/* Heatmap — density visualization at overview zoom */}
                <Layer
                  id="homepage-spot-heatmap"
                  type="heatmap"
                  maxzoom={SPOT_ZOOM.HEATMAP_MAX}
                  paint={{
                    'heatmap-weight': 1,
                    'heatmap-intensity': [
                      'interpolate', ['linear'], ['zoom'],
                      11, 0.6,
                      SPOT_ZOOM.HEATMAP_MAX, 1.2,
                    ],
                    'heatmap-color': [
                      'interpolate', ['linear'], ['heatmap-density'],
                      0,    'rgba(0,0,0,0)',
                      0.15, SPOT_COLORS.HEATMAP_LOW,
                      0.4,  SPOT_COLORS.HEATMAP_MED,
                      0.7,  SPOT_COLORS.HEATMAP_HIGH,
                      1.0,  SPOT_COLORS.HEATMAP_PEAK,
                    ],
                    'heatmap-radius': [
                      'interpolate', ['linear'], ['zoom'],
                      11, 8,
                      13, 14,
                      SPOT_ZOOM.HEATMAP_MAX, 20,
                    ],
                    'heatmap-opacity': [
                      'interpolate', ['linear'], ['zoom'],
                      11, 0.65,
                      SPOT_ZOOM.DOTS_MIN, 0.45,
                      SPOT_ZOOM.HEATMAP_MAX, 0,
                    ],
                  }}
                />
                {/* Individual dots — color by spot status */}
                <Layer
                  id="homepage-spot-dots"
                  type="circle"
                  minzoom={SPOT_ZOOM.DOTS_MIN}
                  paint={{
                    'circle-color': [
                      'match', ['get', 'status'],
                      'pending',      '#F59E0B',  // amber — awaiting verification
                      'verified',     '#EF4444',  // red — confirmed dirty
                      'acknowledged', '#3B82F6',  // blue — worker claimed
                      'resolved',     '#22C55E',  // green — cleaned
                      '#F97316',                   // orange fallback
                    ],
                    'circle-radius': [
                      'interpolate', ['linear'], ['zoom'],
                      SPOT_ZOOM.DOTS_MIN, 4,
                      15, 6,
                      17, 8,
                    ],
                    'circle-stroke-color': '#ffffff',
                    'circle-stroke-width': 1.5,
                    'circle-opacity': [
                      'interpolate', ['linear'], ['zoom'],
                      SPOT_ZOOM.DOTS_MIN, 0,
                      14, 0.9,
                    ],
                  }}
                />
                {/* "Needs Verification" labels — visible at close zoom */}
                <Layer
                  id="homepage-spot-labels"
                  type="symbol"
                  minzoom={15}
                  filter={['==', ['get', 'needsVerification'], 1]}
                  layout={{
                    'text-field': 'Needs Verification',
                    'text-size': 10,
                    'text-font': ['Open Sans Bold', 'Arial Unicode MS Bold'],
                    'text-offset': [0, -1.5],
                    'text-allow-overlap': false,
                  }}
                  paint={{
                    'text-color': '#B45309',
                    'text-halo-color': '#ffffff',
                    'text-halo-width': 1.5,
                  }}
                />
              </Source>

              {/* No in-map popup — detail card is rendered outside the map below */}
            </Map>
          </div>

          {/* Block detail card — overlaid inside the map canvas */}
          {selectedBlock && (
            <div className="absolute bottom-8 left-3 right-3 sm:left-4 sm:right-auto sm:max-w-sm z-20">
              <div className="bg-white/95 backdrop-blur-sm rounded-xl shadow-2xl border border-neutral-200 overflow-hidden">
                {/* Block display image */}
                {selectedBlock.displayImage && (
                  <img
                    src={selectedBlock.displayImage}
                    alt={selectedBlock.code}
                    className="w-full h-40 object-cover"
                  />
                )}
                <div className="p-4">
                <div className="flex items-start justify-between gap-3 mb-2">
                  <div className="min-w-0">
                    <h3 className="font-bold text-neutral-900 text-base leading-tight">
                      {selectedBlock.fromCorner && selectedBlock.toCorner
                        ? `${selectedBlock.fromCorner} to ${selectedBlock.toCorner}`
                        : formatStreetName(selectedBlock.streetName) || selectedBlock.code}
                    </h3>
                    {selectedBlock.streetName && (
                      <p className="text-xs text-neutral-500 mt-0.5">
                        📍 {formatStreetName(selectedBlock.streetName)} • {selectedBlock.side === 'north' ? 'North' : 'South'} side
                      </p>
                    )}
                  </div>
                  <div className="flex items-center gap-1.5 flex-shrink-0">
                    <span
                      className="text-xs px-2.5 py-1 rounded-full text-white font-semibold flex items-center gap-1"
                      style={{ backgroundColor: STATUS_COLORS[selectedBlock.status] }}
                    >
                      {STATUS_ICONS[selectedBlock.status]} {STATUS_LABELS[selectedBlock.status]}
                    </span>
                    <button
                      onClick={() => { setSelectedBlock(null); resetMapView() }}
                      className="p-1 text-neutral-400 hover:text-neutral-700 transition-colors rounded-full hover:bg-neutral-100"
                      aria-label="Close"
                    >
                      <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2.5}>
                        <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
                      </svg>
                    </button>
                  </div>
                </div>

                {selectedBlock.status === 'available' && (
                  <Link href="/worker/signup" className="btn-primary w-full text-sm py-2 text-center block font-semibold mt-2">
                    🧹 Claim This Block — Earn $100
                  </Link>
                )}
                {selectedBlock.status === 'unfunded' && (
                  <Link href={`/sponsor?block=${selectedBlock.id}`} className="btn-secondary w-full text-sm py-2 text-center block font-semibold mt-2">
                    💰 Fund This Block — $100
                  </Link>
                )}
                {selectedBlock.status === 'completed' && (
                  <p className="text-sm text-blue-700 font-medium bg-blue-50 rounded-lg px-3 py-1.5 mt-2 text-center">
                    🏆 Recently cleaned!
                  </p>
                )}
                {(selectedBlock.status === 'claimed' || selectedBlock.status === 'in_progress' || selectedBlock.status === 'pending_review') && (
                  <p className="text-sm text-red-700 font-medium bg-red-50 rounded-lg px-3 py-1.5 mt-2 text-center">
                    🚫 Currently being handled
                  </p>
                )}
                </div>{/* end p-4 */}
              </div>
            </div>
          )}

          {/* Spot detail card */}
          {selectedSpot && (
            <div className="absolute bottom-8 left-3 right-3 sm:left-4 sm:right-auto sm:max-w-sm z-20">
              <div className="bg-white/95 backdrop-blur-sm rounded-xl shadow-2xl border border-neutral-200 p-4">
                <div className="flex items-start justify-between gap-3 mb-2">
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <span
                        className="w-3 h-3 rounded-full flex-shrink-0"
                        style={{ backgroundColor: SPOT_COLORS.MARKER }}
                      />
                      <h3 className="font-bold text-neutral-900 text-base leading-tight">
                        Spot Report
                      </h3>
                    </div>
                    <p className="text-xs text-neutral-500 mt-0.5 capitalize">
                      {(selectedSpot.type || 'report').replace(/_/g, ' ')}
                    </p>
                  </div>
                  <div className="flex items-center gap-1.5 flex-shrink-0">
                    <span
                      className="text-xs px-2.5 py-1 rounded-full text-white font-semibold"
                      style={{ backgroundColor: SPOT_STATUS_COLORS[selectedSpot.status || 'pending'] || SPOT_STATUS_COLORS.pending }}
                    >
                      {selectedSpot.status || 'pending'}
                    </span>
                    <button
                      onClick={() => { setSelectedSpot(null); resetMapView() }}
                      className="p-1 text-neutral-400 hover:text-neutral-700 transition-colors rounded-full hover:bg-neutral-100"
                      aria-label="Close"
                    >
                      <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2.5}>
                        <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
                      </svg>
                    </button>
                  </div>
                </div>
                {(selectedSpot.photoUrl || selectedSpot.mediaUrls?.length > 0) && (() => {
                  const photos = selectedSpot.mediaUrls?.length ? selectedSpot.mediaUrls : selectedSpot.photoUrl ? [selectedSpot.photoUrl] : []
                  return (
                    <div className={`mb-2 ${photos.length > 1 ? 'flex gap-2 overflow-x-auto' : ''}`}>
                      {photos.map((url: string, i: number) => (
                        <img
                          key={i}
                          src={url}
                          alt={`Spot report photo ${i + 1}`}
                          className={`object-cover rounded-lg cursor-pointer hover:opacity-90 transition-opacity ${
                            photos.length === 1 ? 'w-full h-44' : 'w-36 h-28 flex-shrink-0'
                          }`}
                          onClick={() => window.open(url, '_blank')}
                        />
                      ))}
                    </div>
                  )
                })()}
                {/* Tags */}
                {selectedSpot.tags?.length > 0 && (
                  <div className="flex flex-wrap gap-1.5 mb-2">
                    {(selectedSpot.tags as string[]).map((tag: string) => (
                      <span key={tag} className="text-xs px-2 py-0.5 rounded-full bg-neutral-100 text-neutral-600 capitalize">{tag}</span>
                    ))}
                  </div>
                )}
                {selectedSpot.description && (
                  <p className="text-sm text-neutral-600 mb-2 line-clamp-3">{selectedSpot.description}</p>
                )}
                <div className="flex items-center flex-wrap gap-3 text-xs text-neutral-500">
                  {selectedSpot.affectedRadiusMeters && (
                    <span>~{Math.round(selectedSpot.affectedRadiusMeters)}m radius</span>
                  )}
                  {selectedSpot.reporterName && (
                    <span>By: {selectedSpot.reporterName}</span>
                  )}
                  {selectedSpot.createdAt && (
                    <span>{new Date(selectedSpot.createdAt).toLocaleDateString()}</span>
                  )}
                </div>
                {/* Verification status — prominent display */}
                {selectedSpot.needsVerification ? (
                  <div className="mt-2 flex items-center gap-2 text-sm font-semibold text-amber-700 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
                    <span className="w-2.5 h-2.5 rounded-full bg-amber-500 animate-pulse flex-shrink-0" />
                    Needs Verification
                    {selectedSpot.nextVerificationDue && (
                      <span className="text-xs font-normal text-amber-600 ml-auto">
                        since {new Date(selectedSpot.nextVerificationDue).toLocaleDateString()}
                      </span>
                    )}
                  </div>
                ) : selectedSpot.lastVerifiedAt ? (
                  <div className="mt-2 flex items-center gap-2 text-xs font-medium text-green-700 bg-green-50 rounded-lg px-2.5 py-1.5">
                    <span className="w-2 h-2 rounded-full bg-green-500 flex-shrink-0" />
                    Verified {new Date(selectedSpot.lastVerifiedAt).toLocaleDateString()}
                    {selectedSpot.verificationCount > 0 && (
                      <span className="text-green-600">({selectedSpot.verificationCount}×)</span>
                    )}
                  </div>
                ) : (
                  <div className="mt-2 flex items-center gap-2 text-xs font-medium text-amber-700 bg-amber-50 rounded-lg px-2.5 py-1.5">
                    <span className="w-2 h-2 rounded-full bg-amber-400 flex-shrink-0" />
                    Pending first verification
                  </div>
                )}
              </div>
            </div>
          )}

          {/* Stats bar — overlaid at top-left of map */}
          <div className="absolute top-3 left-3 z-20 flex gap-2">
            {[
              { value: stats.available, label: 'Available', color: 'bg-green-500' },
              { value: stats.needSponsors, label: 'Need Sponsors', color: 'bg-neutral-400' },
              ...(stats.completed > 0 ? [{ value: stats.completed, label: 'Completed', color: 'bg-blue-500' }] : []),
              ...(stats.inProgress > 0 ? [{ value: stats.inProgress, label: 'In Progress', color: 'bg-red-500' }] : []),
            ].map((stat) => (
              <div
                key={stat.label}
                className="bg-white/90 backdrop-blur-sm rounded-lg px-2.5 py-1.5 shadow-md text-center"
              >
                <div className="flex items-center gap-1.5">
                  <span className={`w-2 h-2 rounded-full ${stat.color}`} />
                  <span className="text-sm font-bold text-neutral-900">{stat.value}</span>
                </div>
                <p className="text-[10px] text-neutral-500 leading-tight">{stat.label}</p>
              </div>
            ))}
          </div>

          {/* Map attribution */}
          <div className="absolute bottom-0 left-0 right-0 bg-white/90 px-3 py-1.5 text-xs text-neutral-500 flex justify-between items-center z-10">
            <span>
              © <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer" className="underline">OpenStreetMap</a>
              {' • '}
              © <a href="https://carto.com/attributions" target="_blank" rel="noopener noreferrer" className="underline">CARTO</a>
            </span>
            <span className="font-medium text-brand-gold-600">
              GCC Service Area
            </span>
          </div>
        </div>

        {/* Quick action CTA */}
        <div className="mt-8 text-center">
          <p className="text-neutral-600 mb-4">
            Ready to make a difference in North Lawndale?
          </p>
          <div className="flex flex-col sm:flex-row gap-4 justify-center">
            <Link
              href="/worker/signup"
              className="btn-primary px-8 py-3 text-lg font-semibold"
            >
              🧹 Become a Worker
            </Link>
            <Link
              href="/sponsor"
              className="btn-secondary px-8 py-3 text-lg font-semibold"
            >
              💰 Sponsor a Block
            </Link>
          </div>
        </div>
      </div>
    </section>
  )
}
