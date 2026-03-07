'use client'

import { useState, useCallback, useMemo, useEffect } from 'react'
import { Source, Layer, Popup } from 'react-map-gl/maplibre'
import type { MapLayerMouseEvent, MapRef } from 'react-map-gl/maplibre'
import { BLOCK_STATUS_COLORS, COMMUNITY_AREA_COLORS } from '@/lib/design-tokens'
import { BaseMap, MapLegend } from './BaseMap'

/**
 * BlockMap Component
 *
 * Uses MapLibre GL JS with CDX Tile Server for vector tiles.
 *
 * Tile Server Layers:
 * - community_areas: North Lawndale boundary (area_number=29)
 * - gcc_blocks: Block cleaning areas with status-based styling
 * - streets: Street context layer
 *
 * @see https://github.com/maplibre/maplibre-gl-js
 */

// ============================================================================
// CDX TILE SERVER CONFIGURATION
// ============================================================================

const TILE_SERVER_URL = process.env.NEXT_PUBLIC_TILE_SERVER_URL || 'http://localhost:7400'

/** Build tile URL template for MapLibre */
function getTileUrlTemplate(layer: string): string {
  return `${TILE_SERVER_URL}/${layer}/{z}/{x}/{y}`
}

/** Tile layer configurations matching @cdx/native-maps */
const GCC_TILE_LAYERS = {
  communityAreas: {
    sourceId: 'community-areas-source',
    sourceLayer: 'community_areas',
    url: getTileUrlTemplate('community_areas'),
    minZoom: 8,
    maxZoom: 18,
  },
  gccBlocks: {
    sourceId: 'gcc-blocks-source',
    sourceLayer: 'gcc_blocks',
    url: getTileUrlTemplate('gcc_blocks'),
    minZoom: 14,
    maxZoom: 18,
  },
  streets: {
    sourceId: 'streets-source',
    sourceLayer: 'streets',
    url: getTileUrlTemplate('streets'),
    minZoom: 14,
    maxZoom: 18,
  },
}

// ============================================================================
// BLOCK STATUS COLORS & OPACITY
// ============================================================================

/** Block status colors - from shared design tokens */
const GCC_BLOCK_COLORS = {
  unfunded: BLOCK_STATUS_COLORS.unfunded.fill,
  available: BLOCK_STATUS_COLORS.available.fill,
  claimed: BLOCK_STATUS_COLORS.claimed.fill,
  in_progress: BLOCK_STATUS_COLORS.in_progress.fill,
  pending_review: BLOCK_STATUS_COLORS.pending_review.fill,
  completed: BLOCK_STATUS_COLORS.completed.fill,
}

const GCC_BLOCK_OPACITY = {
  unfunded: BLOCK_STATUS_COLORS.unfunded.opacity,
  available: BLOCK_STATUS_COLORS.available.opacity,
  claimed: BLOCK_STATUS_COLORS.claimed.opacity,
  in_progress: BLOCK_STATUS_COLORS.in_progress.opacity,
  pending_review: BLOCK_STATUS_COLORS.pending_review.opacity,
  completed: BLOCK_STATUS_COLORS.completed.opacity,
}

const STATUS_LABELS: Record<BlockStatus, string> = {
  unfunded: 'Needs Sponsor',
  available: 'Available',
  claimed: 'Claimed',
  in_progress: 'In Progress',
  pending_review: 'Under Review',
  completed: 'Completed',
}

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

// ============================================================================
// TYPES
// ============================================================================

type BlockStatus = 'unfunded' | 'available' | 'claimed' | 'in_progress' | 'pending_review' | 'completed'

interface Block {
  id: string
  code: string
  streetName: string
  fromCorner: string
  toCorner: string
  status: BlockStatus
  geo: {
    center: { coordinates: [number, number] }
    polygon?: { coordinates: [number, number][][] }
  }
}

interface BlockMapProps {
  blocks: Block[]
  onBlockSelect?: (block: Block) => void
  selectedBlockId?: string
  interactive?: boolean
  height?: string
  showTileLayers?: boolean
  showLegend?: boolean
}

// ============================================================================
// COMPONENT
// ============================================================================

export function BlockMap({
  blocks,
  onBlockSelect,
  selectedBlockId,
  interactive = true,
  height = '400px',
  showTileLayers = true,
  showLegend = true,
}: BlockMapProps) {
  const [popupInfo, setPopupInfo] = useState<Block | null>(null)
  const [mapRef, setMapRef] = useState<MapRef | null>(null)
  const [tileServerAvailable, setTileServerAvailable] = useState<boolean | null>(null)

  // Calculate bounds from all blocks
  const blockBounds = useMemo(() => {
    if (blocks.length === 0) return null

    let minLng = Infinity
    let maxLng = -Infinity
    let minLat = Infinity
    let maxLat = -Infinity

    blocks.forEach((block) => {
      // Process polygon coordinates if available
      if (block.geo.polygon?.coordinates) {
        block.geo.polygon.coordinates[0].forEach(([lng, lat]) => {
          minLng = Math.min(minLng, lng)
          maxLng = Math.max(maxLng, lng)
          minLat = Math.min(minLat, lat)
          maxLat = Math.max(maxLat, lat)
        })
      } else {
        // Fallback to center point
        const [lng, lat] = block.geo.center.coordinates
        minLng = Math.min(minLng, lng)
        maxLng = Math.max(maxLng, lng)
        minLat = Math.min(minLat, lat)
        maxLat = Math.max(maxLat, lat)
      }
    })

    // Return bounds as [sw, ne] format for fitBounds
    return [[minLng, minLat], [maxLng, maxLat]] as [[number, number], [number, number]]
  }, [blocks])

  // Fit map to block bounds when map loads
  useEffect(() => {
    if (!mapRef || !blockBounds) return

    // Add a small delay to ensure map is fully loaded
    const timer = setTimeout(() => {
      mapRef.fitBounds(blockBounds, {
        padding: { top: 50, bottom: 50, left: 50, right: 50 },
        maxZoom: 17, // Don't zoom in too far
        duration: 500, // Smooth animation
      })
    }, 100)

    return () => clearTimeout(timer)
  }, [mapRef, blockBounds])

  // Check tile server health on mount
  useEffect(() => {
    if (!showTileLayers) return

    const checkTileServer = async () => {
      try {
        const response = await fetch(`${TILE_SERVER_URL}/health`, {
          method: 'GET',
          signal: AbortSignal.timeout(3000),
        })
        setTileServerAvailable(response.ok)
      } catch {
        setTileServerAvailable(false)
        console.warn('CDX Tile Server not available, falling back to GeoJSON rendering')
      }
    }
    checkTileServer()
  }, [showTileLayers])

  // Convert local blocks to GeoJSON (fallback + overlay)
  const geojsonData = useMemo(() => ({
    type: 'FeatureCollection' as const,
    features: blocks.map((block) => ({
      type: 'Feature' as const,
      id: block.id,
      properties: {
        id: block.id,
        code: block.code,
        streetName: block.streetName,
        fromCorner: block.fromCorner,
        toCorner: block.toCorner,
        status: block.status,
      },
      geometry: block.geo.polygon
        ? {
            type: 'Polygon' as const,
            coordinates: block.geo.polygon.coordinates,
          }
        : {
            type: 'Point' as const,
            coordinates: block.geo.center.coordinates,
          },
    })),
  }), [blocks])

  // Handle block click (both GeoJSON and vector tile layers)
  const onClick = useCallback((event: MapLayerMouseEvent) => {
    const feature = event.features?.[0]
    if (feature && feature.properties) {
      // Try to match with local blocks data first
      const blockId = feature.properties?.id || feature.properties?.block_id
      const block = blocks.find(b => b.id === blockId || b.code === feature.properties?.code)

      if (block) {
        setPopupInfo(block)
        onBlockSelect?.(block)
      } else if (feature.properties?.block_id) {
        // Vector tile block without local match - create minimal info
        setPopupInfo({
          id: feature.properties.block_id,
          code: feature.properties.code || feature.properties.block_id,
          streetName: feature.properties.street_name || 'Unknown',
          fromCorner: '',
          toCorner: '',
          status: (feature.properties.status as BlockStatus) || 'unfunded',
          geo: {
            center: { coordinates: [event.lngLat.lng, event.lngLat.lat] },
          },
        })
      }
    }
  }, [blocks, onBlockSelect])

  // North Lawndale center coordinates
  const initialViewState = {
    longitude: -87.7158,
    latitude: 41.8666,
    zoom: 15,
  }

  // Interactive layer IDs
  const interactiveLayerIds = interactive
    ? ['blocks-fill', 'blocks-circle', 'gcc-blocks-fill']
    : []

  // Build legend items
  const legendItems = Object.entries(STATUS_LABELS).map(([status, label]) => ({
    color: GCC_BLOCK_COLORS[status as BlockStatus],
    label,
  }))

  return (
    <BaseMap
      height={height}
      initialViewState={initialViewState}
      interactiveLayerIds={interactiveLayerIds}
      onClick={interactive ? onClick : undefined}
      cursor={interactive ? 'pointer' : 'default'}
      mapRef={(ref) => setMapRef(ref)}
      legend={
        showLegend ? (
          <div className="mt-4 flex flex-wrap gap-4 justify-center text-xs">
            {legendItems.map(item => (
              <div key={item.label} className="flex items-center gap-1.5">
                <span
                  className="w-3 h-3 rounded-full"
                  style={{ backgroundColor: item.color }}
                />
                <span className="text-neutral-600">{item.label}</span>
              </div>
            ))}
          </div>
        ) : undefined
      }
      popup={
        popupInfo ? (
          <Popup
            longitude={popupInfo.geo.center.coordinates[0]}
            latitude={popupInfo.geo.center.coordinates[1]}
            anchor="bottom"
            onClose={() => setPopupInfo(null)}
            closeButton={true}
            className="block-popup"
          >
            <div className="p-3 min-w-[220px]">
              <div className="flex items-center justify-between mb-2">
                <span className="font-bold text-neutral-900">
                  {popupInfo.fromCorner && popupInfo.toCorner
                    ? `${popupInfo.fromCorner} to ${popupInfo.toCorner}`
                    : formatStreetName(popupInfo.streetName) || popupInfo.code}
                </span>
                <span
                  className="text-xs px-2 py-0.5 rounded-full text-white font-medium"
                  style={{ backgroundColor: GCC_BLOCK_COLORS[popupInfo.status] }}
                >
                  {STATUS_LABELS[popupInfo.status]}
                </span>
              </div>
              {popupInfo.streetName && (
                <p className="text-sm text-neutral-600 mb-1">{formatStreetName(popupInfo.streetName)}</p>
              )}
              {popupInfo.status === 'available' && onBlockSelect && (
                <button
                  onClick={() => onBlockSelect(popupInfo)}
                  className="mt-3 w-full bg-brand-gold-600 hover:bg-brand-gold-700 text-white text-sm py-2 px-4 rounded-lg font-medium transition-colors"
                >
                  Claim This Block
                </button>
              )}
              {popupInfo.status === 'unfunded' && (
                <button
                  onClick={() => onBlockSelect?.(popupInfo)}
                  className="mt-3 w-full bg-emerald-600 hover:bg-emerald-700 text-white text-sm py-2 px-4 rounded-lg font-medium transition-colors"
                >
                  Sponsor This Block
                </button>
              )}
            </div>
          </Popup>
        ) : undefined
      }
    >
      {/* ============================================================ */}
      {/* VECTOR TILE LAYERS (from CDX Tile Server)                   */}
      {/* ============================================================ */}

      {showTileLayers && tileServerAvailable && (
        <>
          {/* Community Areas - North Lawndale Boundary */}
          <Source
            id={GCC_TILE_LAYERS.communityAreas.sourceId}
            type="vector"
            tiles={[GCC_TILE_LAYERS.communityAreas.url]}
            minzoom={GCC_TILE_LAYERS.communityAreas.minZoom}
            maxzoom={GCC_TILE_LAYERS.communityAreas.maxZoom}
          >
            <Layer
              id="community-area-boundary"
              type="line"
              source-layer={GCC_TILE_LAYERS.communityAreas.sourceLayer}
              filter={['==', ['get', 'area_number'], 29]}
              minzoom={8}
              paint={{
                'line-color': COMMUNITY_AREA_COLORS.STROKE,
                'line-width': COMMUNITY_AREA_COLORS.STROKE_WIDTH,
                'line-opacity': 0.8,
                'line-dasharray': [2, 1],
              }}
            />
            <Layer
              id="community-area-fill"
              type="fill"
              source-layer={GCC_TILE_LAYERS.communityAreas.sourceLayer}
              filter={['==', ['get', 'area_number'], 29]}
              minzoom={8}
              paint={{
                'fill-color': COMMUNITY_AREA_COLORS.FILL,
                'fill-opacity': 0.08,
              }}
            />
          </Source>

          {/* Streets Layer - Context */}
          <Source
            id={GCC_TILE_LAYERS.streets.sourceId}
            type="vector"
            tiles={[GCC_TILE_LAYERS.streets.url]}
            minzoom={GCC_TILE_LAYERS.streets.minZoom}
            maxzoom={GCC_TILE_LAYERS.streets.maxZoom}
          >
            <Layer
              id="streets-network"
              type="line"
              source-layer={GCC_TILE_LAYERS.streets.sourceLayer}
              minzoom={14}
              paint={{
                'line-color': '#9ca3af',
                'line-width': 1,
                'line-opacity': 0.4,
              }}
            />
          </Source>

          {/* GCC Blocks - Status-based styling */}
          <Source
            id={GCC_TILE_LAYERS.gccBlocks.sourceId}
            type="vector"
            tiles={[GCC_TILE_LAYERS.gccBlocks.url]}
            minzoom={GCC_TILE_LAYERS.gccBlocks.minZoom}
            maxzoom={GCC_TILE_LAYERS.gccBlocks.maxZoom}
          >
            {/* Block fill with status-based coloring */}
            <Layer
              id="gcc-blocks-fill"
              type="fill"
              source-layer={GCC_TILE_LAYERS.gccBlocks.sourceLayer}
              minzoom={14}
              paint={{
                'fill-color': [
                  'match',
                  ['get', 'status'],
                  'available', GCC_BLOCK_COLORS.available,
                  'claimed', GCC_BLOCK_COLORS.claimed,
                  'in_progress', GCC_BLOCK_COLORS.in_progress,
                  'pending_review', GCC_BLOCK_COLORS.pending_review,
                  'completed', GCC_BLOCK_COLORS.completed,
                  GCC_BLOCK_COLORS.unfunded,
                ],
                'fill-opacity': [
                  'match',
                  ['get', 'status'],
                  'available', GCC_BLOCK_OPACITY.available,
                  'claimed', GCC_BLOCK_OPACITY.claimed,
                  'in_progress', GCC_BLOCK_OPACITY.in_progress,
                  'pending_review', GCC_BLOCK_OPACITY.pending_review,
                  'completed', GCC_BLOCK_OPACITY.completed,
                  GCC_BLOCK_OPACITY.unfunded,
                ],
              }}
            />
            {/* Block outline */}
            <Layer
              id="gcc-blocks-outline"
              type="line"
              source-layer={GCC_TILE_LAYERS.gccBlocks.sourceLayer}
              minzoom={14}
              paint={{
                'line-color': [
                  'match',
                  ['get', 'status'],
                  'available', GCC_BLOCK_COLORS.available,
                  'claimed', GCC_BLOCK_COLORS.claimed,
                  'in_progress', GCC_BLOCK_COLORS.in_progress,
                  'pending_review', GCC_BLOCK_COLORS.pending_review,
                  'completed', GCC_BLOCK_COLORS.completed,
                  GCC_BLOCK_COLORS.unfunded,
                ],
                'line-width': 2,
                'line-opacity': 0.9,
              }}
            />
          </Source>
        </>
      )}

      {/* ============================================================ */}
      {/* GEOJSON LAYERS (local blocks data - always shown)           */}
      {/* ============================================================ */}

      <Source id="blocks" type="geojson" data={geojsonData}>
        {/* Polygon fill layer */}
        <Layer
          id="blocks-fill"
          type="fill"
          filter={['==', ['geometry-type'], 'Polygon']}
          paint={{
            'fill-color': [
              'match',
              ['get', 'status'],
              'available', GCC_BLOCK_COLORS.available,
              'claimed', GCC_BLOCK_COLORS.claimed,
              'in_progress', GCC_BLOCK_COLORS.in_progress,
              'pending_review', GCC_BLOCK_COLORS.pending_review,
              'completed', GCC_BLOCK_COLORS.completed,
              GCC_BLOCK_COLORS.unfunded,
            ],
            'fill-opacity': [
              'case',
              ['==', ['get', 'id'], selectedBlockId || ''],
              0.8,
              [
                'match',
                ['get', 'status'],
                'available', GCC_BLOCK_OPACITY.available,
                'claimed', GCC_BLOCK_OPACITY.claimed,
                'in_progress', GCC_BLOCK_OPACITY.in_progress,
                'pending_review', GCC_BLOCK_OPACITY.pending_review,
                'completed', GCC_BLOCK_OPACITY.completed,
                GCC_BLOCK_OPACITY.unfunded,
              ],
            ],
          }}
        />

        {/* Polygon outline */}
        <Layer
          id="blocks-outline"
          type="line"
          filter={['==', ['geometry-type'], 'Polygon']}
          paint={{
            'line-color': [
              'match',
              ['get', 'status'],
              'available', GCC_BLOCK_COLORS.available,
              'claimed', GCC_BLOCK_COLORS.claimed,
              'in_progress', GCC_BLOCK_COLORS.in_progress,
              'pending_review', GCC_BLOCK_COLORS.pending_review,
              'completed', GCC_BLOCK_COLORS.completed,
              GCC_BLOCK_COLORS.unfunded,
            ],
            'line-width': [
              'case',
              ['==', ['get', 'id'], selectedBlockId || ''],
              4,
              2,
            ],
          }}
        />

        {/* Point circles for blocks without polygon data */}
        <Layer
          id="blocks-circle"
          type="circle"
          filter={['==', ['geometry-type'], 'Point']}
          paint={{
            'circle-radius': [
              'case',
              ['==', ['get', 'id'], selectedBlockId || ''],
              16,
              12,
            ],
            'circle-color': [
              'match',
              ['get', 'status'],
              'available', GCC_BLOCK_COLORS.available,
              'claimed', GCC_BLOCK_COLORS.claimed,
              'in_progress', GCC_BLOCK_COLORS.in_progress,
              'pending_review', GCC_BLOCK_COLORS.pending_review,
              'completed', GCC_BLOCK_COLORS.completed,
              GCC_BLOCK_COLORS.unfunded,
            ],
            'circle-stroke-color': '#ffffff',
            'circle-stroke-width': 2,
          }}
        />

        {/* Labels - show street corners instead of block codes */}
        <Layer
          id="blocks-label"
          type="symbol"
          layout={{
            'text-field': [
              'case',
              ['all', ['has', 'fromCorner'], ['has', 'toCorner']],
              ['concat', ['get', 'fromCorner'], ' → ', ['get', 'toCorner']],
              ['get', 'streetName'],
            ],
            'text-size': 10,
            'text-anchor': 'center',
            'text-font': ['Open Sans Bold', 'Arial Unicode MS Bold'],
            'text-max-width': 12,
          }}
          paint={{
            'text-color': '#ffffff',
            'text-halo-color': '#000000',
            'text-halo-width': 1.5,
          }}
        />
      </Source>

      {/* Tile Server Status Indicator */}
      {showTileLayers && tileServerAvailable === false && (
        <div className="absolute top-2 left-2 bg-amber-100 text-amber-800 text-xs px-2 py-1 rounded-md">
          Tile server offline - showing local data
        </div>
      )}
    </BaseMap>
  )
}

// Re-export types for use in other components
export type { Block, BlockStatus, BlockMapProps }
