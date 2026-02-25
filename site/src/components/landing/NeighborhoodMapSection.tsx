'use client'

import { useState, useCallback, useMemo, useRef, useEffect } from 'react'
import Map, { Source, Layer, Popup, NavigationControl, ScaleControl } from 'react-map-gl/maplibre'
import type { MapLayerMouseEvent, MapRef } from 'react-map-gl/maplibre'
import Link from 'next/link'
import 'maplibre-gl/dist/maplibre-gl.css'
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

// Free OpenStreetMap-based style for MapLibre
const MAP_STYLE = 'https://basemaps.cartocdn.com/gl/voyager-gl-style/style.json'

// Block status colors - designed for clear visibility
// Maps database status to display colors
const STATUS_COLORS = {
  unfunded: '#6B7280',    // gray-500 - needs sponsor (muted)
  available: '#16A34A',   // green-600 - ready to claim (vibrant)
  funded: '#16A34A',      // green-600 - funded = available for workers
  claimed: '#D97706',     // amber-600 - someone is working
  in_progress: '#2563EB', // blue-600 - being cleaned
  pending_review: '#7C3AED', // violet-600 - awaiting approval
  completed: '#059669',   // emerald-600 - done
} as const

const STATUS_LABELS = {
  unfunded: 'Needs Sponsor',
  available: 'Available Now',
  funded: 'Available Now',
  claimed: 'Claimed',
  in_progress: 'Being Cleaned',
  pending_review: 'Under Review',
  completed: 'Completed',
} as const

const STATUS_ICONS = {
  unfunded: '💰',
  available: '✅',
  funded: '✅',
  claimed: '👷',
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
    geo: block.geo,
  }
}

export function NeighborhoodMapSection() {
  const mapRef = useRef<MapRef>(null)
  const [selectedBlock, setSelectedBlock] = useState<BlockWithStatus | null>(null)
  const [hoveredBlockId, setHoveredBlockId] = useState<string | null>(null)
  const [mapLoaded, setMapLoaded] = useState(false)
  const [blocksWithStatus, setBlocksWithStatus] = useState<BlockWithStatus[]>([])
  const [isLoading, setIsLoading] = useState(true)

  // Fetch blocks from database API, fall back to hardcoded data
  useEffect(() => {
    async function fetchBlocks() {
      try {
        const res = await fetch('/api/blocks?limit=50')
        if (res.ok) {
          const data = await res.json()
          if (data.blocks && data.blocks.length > 0) {
            // Use database blocks
            const dbBlocks = data.blocks
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

  const onClick = useCallback((event: MapLayerMouseEvent) => {
    const feature = event.features?.[0]
    if (feature && feature.properties) {
      const block = blocksWithStatus.find(b => b.id === feature.properties?.id)
      if (block) {
        setSelectedBlock(block)
      }
    }
  }, [blocksWithStatus])

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

        {/* Legend - Enhanced with icons */}
        <div className="flex flex-wrap justify-center gap-3 mb-8">
          {Object.entries(STATUS_LABELS).map(([status, label]) => (
            <div
              key={status}
              className={`
                flex items-center gap-2 px-3 py-1.5 rounded-full text-sm
                ${status === 'available' ? 'bg-green-100 ring-2 ring-green-500' : 'bg-white'}
                shadow-sm
              `}
            >
              <span>{STATUS_ICONS[status as BlockStatus]}</span>
              <span
                className="w-3 h-3 rounded-full"
                style={{ backgroundColor: STATUS_COLORS[status as BlockStatus] }}
              />
              <span className={`${status === 'available' ? 'font-semibold text-green-700' : 'text-neutral-600'}`}>
                {label}
              </span>
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
              interactiveLayerIds={['blocks-fill']}
              onClick={onClick}
              onMouseMove={onHover}
              onMouseLeave={() => setHoveredBlockId(null)}
              onLoad={() => setMapLoaded(true)}
              cursor={hoveredBlockId ? 'pointer' : 'grab'}
              maxBounds={[
                [MAP_BOUNDS.west - 0.01, MAP_BOUNDS.south - 0.01],
                [MAP_BOUNDS.east + 0.01, MAP_BOUNDS.north + 0.01],
              ]}
              minZoom={14}
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

              <Source id="blocks" type="geojson" data={geojsonData}>
                {/* Block fill - different opacity for different statuses */}
                <Layer
                  id="blocks-fill"
                  type="fill"
                  paint={{
                    'fill-color': ['get', 'color'],
                    'fill-opacity': [
                      'case',
                      // Hovered block
                      ['==', ['get', 'id'], hoveredBlockId || ''],
                      0.9,
                      // Selected block
                      ['==', ['get', 'id'], selectedBlock?.id || ''],
                      0.85,
                      // Available blocks (more visible)
                      ['get', 'isAvailable'],
                      0.75,
                      // Unfunded blocks (muted)
                      ['get', 'isUnfunded'],
                      0.4,
                      // Default
                      0.6,
                    ],
                  }}
                />

                {/* Block outline - thicker for available blocks */}
                <Layer
                  id="blocks-outline"
                  type="line"
                  paint={{
                    'line-color': [
                      'case',
                      ['get', 'isAvailable'],
                      '#15803D', // darker green for available
                      ['get', 'color'],
                    ],
                    'line-width': [
                      'case',
                      ['==', ['get', 'id'], hoveredBlockId || ''],
                      5,
                      ['==', ['get', 'id'], selectedBlock?.id || ''],
                      4,
                      ['get', 'isAvailable'],
                      3,
                      2,
                    ],
                  }}
                />

                {/* Block labels - zoom-adaptive with no overlap */}
                <Layer
                  id="blocks-label"
                  type="symbol"
                  layout={{
                    'text-field': [
                      'step',
                      ['zoom'],
                      // Zoom < 15.5: short code only
                      ['get', 'code'],
                      15.5,
                      // Zoom 15.5-16.5: corner names stacked
                      [
                        'case',
                        ['all', ['has', 'fromCorner'], ['has', 'toCorner'], ['!=', ['get', 'fromCorner'], ''], ['!=', ['get', 'toCorner'], '']],
                        ['concat', ['get', 'fromCorner'], '\n→ ', ['get', 'toCorner']],
                        ['get', 'streetName'],
                      ],
                      16.5,
                      // Zoom 16.5+: full detail with street name
                      [
                        'case',
                        ['all', ['has', 'fromCorner'], ['has', 'toCorner'], ['!=', ['get', 'fromCorner'], ''], ['!=', ['get', 'toCorner'], '']],
                        ['concat', ['get', 'fromCorner'], ' → ', ['get', 'toCorner'], '\n', ['get', 'streetName']],
                        ['get', 'streetName'],
                      ],
                    ],
                    'text-size': [
                      'interpolate', ['linear'], ['zoom'],
                      14, 8,
                      15, 10,
                      16, 12,
                      17, 14,
                      18, 16,
                    ],
                    'text-anchor': 'center',
                    'text-justify': 'center',
                    'text-allow-overlap': false,
                    'text-ignore-placement': false,
                    'text-padding': 4,
                    'text-font': ['Open Sans Bold', 'Arial Unicode MS Bold'],
                    'text-max-width': 8,
                    'text-line-height': 1.2,
                    'symbol-sort-key': [
                      'case',
                      ['get', 'isAvailable'], 0,
                      ['get', 'isUnfunded'], 2,
                      1,
                    ],
                  }}
                  paint={{
                    'text-color': '#ffffff',
                    'text-halo-color': [
                      'case',
                      ['get', 'isAvailable'],
                      '#15803D',
                      '#000000',
                    ],
                    'text-halo-width': [
                      'interpolate', ['linear'], ['zoom'],
                      14, 1.5,
                      16, 2,
                      18, 2.5,
                    ],
                    'text-halo-blur': 0.5,
                  }}
                />
              </Source>

              {/* Popup for selected block */}
              {selectedBlock && (
                <Popup
                  longitude={selectedBlock.geo.center.coordinates[0]}
                  latitude={selectedBlock.geo.center.coordinates[1]}
                  anchor="bottom"
                  onClose={() => setSelectedBlock(null)}
                  closeButton={true}
                  closeOnClick={false}
                  className="block-popup"
                  offset={15}
                >
                  <div className="p-4 min-w-[240px]">
                    <div className="flex items-center justify-between mb-3">
                      <span className="font-bold text-neutral-900 text-lg">
                        {selectedBlock.fromCorner && selectedBlock.toCorner
                          ? `${selectedBlock.fromCorner} to ${selectedBlock.toCorner}`
                          : formatStreetName(selectedBlock.streetName) || selectedBlock.code}
                      </span>
                      <span
                        className="text-xs px-3 py-1 rounded-full text-white font-semibold flex items-center gap-1"
                        style={{ backgroundColor: STATUS_COLORS[selectedBlock.status] }}
                      >
                        <span>{STATUS_ICONS[selectedBlock.status]}</span>
                        {STATUS_LABELS[selectedBlock.status]}
                      </span>
                    </div>
                    {selectedBlock.streetName && (
                      <p className="text-sm text-neutral-700 mb-4 font-medium">
                        📍 {formatStreetName(selectedBlock.streetName)}
                      </p>
                    )}

                    {selectedBlock.status === 'available' && (
                      <div className="space-y-2">
                        <Link
                          href="/worker/signup"
                          className="btn-primary w-full text-sm py-2.5 text-center block font-semibold"
                        >
                          🧹 Claim This Block - Earn $100
                        </Link>
                        <p className="text-xs text-center text-neutral-500">
                          Sign up as a worker to claim
                        </p>
                      </div>
                    )}
                    {selectedBlock.status === 'unfunded' && (
                      <div className="space-y-2">
                        <Link
                          href="/sponsor"
                          className="btn-secondary w-full text-sm py-2.5 text-center block font-semibold"
                        >
                          💰 Fund This Block - $100
                        </Link>
                        <p className="text-xs text-center text-neutral-500">
                          Sponsor a cleaner community
                        </p>
                      </div>
                    )}
                    {selectedBlock.status === 'completed' && (
                      <div className="text-center p-2 bg-emerald-50 rounded-lg">
                        <p className="text-sm text-emerald-700 font-medium">
                          🏆 Recently cleaned!
                        </p>
                      </div>
                    )}
                    {(selectedBlock.status === 'claimed' || selectedBlock.status === 'in_progress') && (
                      <div className="text-center p-2 bg-amber-50 rounded-lg">
                        <p className="text-sm text-amber-700 font-medium">
                          👷 A worker is handling this block
                        </p>
                      </div>
                    )}
                  </div>
                </Popup>
              )}
            </Map>
          </div>

          {/* Map attribution */}
          <div className="absolute bottom-0 left-0 right-0 bg-white/90 px-3 py-1.5 text-xs text-neutral-500 flex justify-between items-center">
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

        {/* Block stats - Enhanced cards */}
        <div className="mt-8 grid grid-cols-2 sm:grid-cols-4 gap-4">
          <div className="bg-white rounded-xl p-5 text-center shadow-md border-l-4 border-green-500">
            <p className="text-3xl font-bold text-green-600">{stats.available}</p>
            <p className="text-sm text-neutral-600 mt-1">Available Now</p>
            <p className="text-xs text-neutral-400">Ready to claim</p>
          </div>
          <div className="bg-white rounded-xl p-5 text-center shadow-md border-l-4 border-amber-500">
            <p className="text-3xl font-bold text-amber-600">{stats.inProgress}</p>
            <p className="text-sm text-neutral-600 mt-1">In Progress</p>
            <p className="text-xs text-neutral-400">Being cleaned</p>
          </div>
          <div className="bg-white rounded-xl p-5 text-center shadow-md border-l-4 border-emerald-500">
            <p className="text-3xl font-bold text-emerald-600">{stats.completed}</p>
            <p className="text-sm text-neutral-600 mt-1">Completed</p>
            <p className="text-xs text-neutral-400">Mission accomplished</p>
          </div>
          <div className="bg-white rounded-xl p-5 text-center shadow-md border-l-4 border-neutral-400">
            <p className="text-3xl font-bold text-neutral-500">{stats.needSponsors}</p>
            <p className="text-sm text-neutral-600 mt-1">Need Sponsors</p>
            <Link href="/sponsor" className="text-xs text-brand-gold-600 hover:underline">
              Fund a block →
            </Link>
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
