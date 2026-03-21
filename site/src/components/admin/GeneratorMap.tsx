'use client'

import { useMemo } from 'react'
import { Source, Layer } from 'react-map-gl/maplibre'
import { BaseMap } from '@/components/map/BaseMap'
import { ZOOM } from '@/lib/design-tokens'
import { PROPOSAL_ID_PREFIX, type RouteProposal } from '@/lib/route-grid-generator'

interface Props {
  /** Community area polygon in [lat, lng] format (from chicago-geo-data.ts) */
  areaPolygon: [number, number][]
  areaBounds: { north: number; south: number; east: number; west: number }
  proposals: RouteProposal[]
  /** Set of proposal IDs that are accepted */
  accepted: Set<string>
  /** Existing routes geometry for gray overlay */
  existingRoutes: { type: string; coordinates: [number, number][] }[]
  onToggle: (id: string) => void
}

export function GeneratorMap({
  areaPolygon,
  areaBounds,
  proposals,
  accepted,
  existingRoutes,
  onToggle,
}: Props) {
  // Convert [lat, lng] polygon to GeoJSON [lng, lat]
  const boundaryGeoJSON = useMemo(() => {
    const ring = areaPolygon.map(([lat, lng]) => [lng, lat] as [number, number])
    // Close ring if needed
    if (ring.length > 0 && (ring[0][0] !== ring[ring.length - 1][0] || ring[0][1] !== ring[ring.length - 1][1])) {
      ring.push(ring[0])
    }
    return {
      type: 'Feature' as const,
      properties: {},
      geometry: { type: 'Polygon' as const, coordinates: [ring] },
    }
  }, [areaPolygon])

  // Existing routes as gray lines
  const existingGeoJSON = useMemo(() => ({
    type: 'FeatureCollection' as const,
    features: existingRoutes.map((geom, i) => ({
      type: 'Feature' as const,
      properties: { id: `existing-${i}` },
      geometry: geom,
    })),
  }), [existingRoutes])

  // Proposals — blocks as polygons, colored by acceptance state
  const proposalBlocksGeoJSON = useMemo(() => ({
    type: 'FeatureCollection' as const,
    features: proposals.map((p) => ({
      type: 'Feature' as const,
      properties: {
        id: p.id,
        status: accepted.has(p.id) ? 'accepted' : 'pending',
        code: p.routeCode,
      },
      geometry: {
        type: 'Polygon' as const,
        coordinates: p.blockPolygon,
      },
    })),
  }), [proposals, accepted])

  // Proposal route lines (dashed)
  const proposalLinesGeoJSON = useMemo(() => ({
    type: 'FeatureCollection' as const,
    features: proposals.map((p) => ({
      type: 'Feature' as const,
      properties: {
        id: p.id,
        status: accepted.has(p.id) ? 'accepted' : 'pending',
      },
      geometry: p.geometry,
    })),
  }), [proposals, accepted])

  // Center the map on the community area
  const center = useMemo(() => ({
    longitude: (areaBounds.east + areaBounds.west) / 2,
    latitude: (areaBounds.north + areaBounds.south) / 2,
  }), [areaBounds])

  function handleClick(e: any) {
    const features = e.features
    if (features?.length > 0) {
      const id = features[0].properties?.id
      if (id && id.startsWith(PROPOSAL_ID_PREFIX)) {
        onToggle(id)
      }
    }
  }

  return (
    <BaseMap
      height="600px"
      initialViewState={{ ...center, zoom: ZOOM.NEIGHBORHOOD }}
      interactiveLayerIds={['proposal-blocks-fill']}
      onClick={handleClick}
      cursor="pointer"
    >
      {/* Community area boundary */}
      <Source id="area-boundary" type="geojson" data={boundaryGeoJSON}>
        <Layer
          id="area-boundary-fill"
          type="fill"
          paint={{ 'fill-color': '#EF4444', 'fill-opacity': 0.05 }}
        />
        <Layer
          id="area-boundary-line"
          type="line"
          paint={{ 'line-color': '#EF4444', 'line-width': 2, 'line-dasharray': [4, 2] }}
        />
      </Source>

      {/* Existing routes */}
      {existingRoutes.length > 0 && (
        <Source id="existing-routes" type="geojson" data={existingGeoJSON}>
          <Layer
            id="existing-routes-layer"
            type="line"
            paint={{ 'line-color': '#6B7280', 'line-width': 3, 'line-opacity': 0.6 }}
          />
        </Source>
      )}

      {/* Proposal blocks */}
      <Source id="proposal-blocks" type="geojson" data={proposalBlocksGeoJSON}>
        <Layer
          id="proposal-blocks-fill"
          type="fill"
          paint={{
            'fill-color': [
              'match',
              ['get', 'status'],
              'accepted', '#22C55E',
              '#F59E0B', // pending = amber
            ],
            'fill-opacity': 0.25,
          }}
        />
        <Layer
          id="proposal-blocks-outline"
          type="line"
          paint={{
            'line-color': [
              'match',
              ['get', 'status'],
              'accepted', '#22C55E',
              '#F59E0B',
            ],
            'line-width': 1.5,
          }}
        />
        <Layer
          id="proposal-blocks-label"
          type="symbol"
          layout={{
            'text-field': ['get', 'code'],
            'text-size': 10,
            'text-allow-overlap': true,
          }}
          paint={{
            'text-color': '#ffffff',
            'text-halo-color': '#000000',
            'text-halo-width': 1,
          }}
        />
      </Source>

      {/* Proposal route lines */}
      <Source id="proposal-lines" type="geojson" data={proposalLinesGeoJSON}>
        <Layer
          id="proposal-lines-layer"
          type="line"
          paint={{
            'line-color': [
              'match',
              ['get', 'status'],
              'accepted', '#16A34A',
              '#D97706',
            ],
            'line-width': 2,
            'line-dasharray': [3, 2],
          }}
        />
      </Source>
    </BaseMap>
  )
}
