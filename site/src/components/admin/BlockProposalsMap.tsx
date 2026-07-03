'use client'

import { useMemo } from 'react'
import { Source, Layer } from 'react-map-gl/maplibre'
import { BaseMap } from '@/components/map/BaseMap'
import { ZOOM } from '@/lib/design-tokens'

export interface BlockProposalMapEntry {
  id: number
  status: 'pending' | 'approved' | 'rejected'
  codeHint: string | null
  boundary: { type: 'Polygon'; coordinates: [number, number][][] }
  center: { type: 'Point'; coordinates: [number, number] }
  exclusionZones: { polygon: { type: 'Polygon'; coordinates: [number, number][][] } }[] | null
}

interface Props {
  proposals: BlockProposalMapEntry[]
  existingBlocks: { code: string | null; boundary: { type: 'Polygon'; coordinates: [number, number][][] } | null }[]
  selectedId: number | null
  onSelect: (id: number) => void
}

const STATUS_COLOR: Record<string, string> = {
  pending: '#F59E0B',
  approved: '#22C55E',
  rejected: '#EF4444',
}

export function BlockProposalsMap({ proposals, existingBlocks, selectedId, onSelect }: Props) {
  const existingGeoJSON = useMemo(
    () => ({
      type: 'FeatureCollection' as const,
      features: existingBlocks
        .filter((b) => b.boundary)
        .map((b) => ({
          type: 'Feature' as const,
          properties: { code: b.code },
          geometry: b.boundary!,
        })),
    }),
    [existingBlocks]
  )

  const proposalsGeoJSON = useMemo(
    () => ({
      type: 'FeatureCollection' as const,
      features: proposals.map((p) => ({
        type: 'Feature' as const,
        properties: {
          id: p.id,
          status: p.status,
          selected: p.id === selectedId,
          code: p.codeHint,
        },
        geometry: p.boundary,
      })),
    }),
    [proposals, selectedId]
  )

  const exclusionGeoJSON = useMemo(
    () => ({
      type: 'FeatureCollection' as const,
      features: proposals.flatMap((p) =>
        (p.exclusionZones || []).map((z, i) => ({
          type: 'Feature' as const,
          properties: { proposalId: p.id },
          geometry: z.polygon,
          id: `${p.id}-${i}`,
        }))
      ),
    }),
    [proposals]
  )

  const center = useMemo(() => {
    if (proposals.length === 0) return { longitude: -87.72, latitude: 41.858 }
    const mid = proposals[Math.floor(proposals.length / 2)]
    return { longitude: mid.center.coordinates[0], latitude: mid.center.coordinates[1] }
  }, [proposals])

  function handleClick(e: any) {
    const features = e.features
    if (features?.length > 0) {
      const id = features[0].properties?.id
      if (typeof id === 'number') onSelect(id)
    }
  }

  return (
    <BaseMap
      height="480px"
      initialViewState={{ ...center, zoom: ZOOM.NEIGHBORHOOD - 1 }}
      interactiveLayerIds={['proposal-blocks-fill']}
      onClick={handleClick}
      cursor="pointer"
    >
      {/* Existing live blocks — reference only, never selectable */}
      <Source id="existing-blocks" type="geojson" data={existingGeoJSON}>
        <Layer id="existing-blocks-fill" type="fill" paint={{ 'fill-color': '#4B5563', 'fill-opacity': 0.08 }} />
        <Layer
          id="existing-blocks-line"
          type="line"
          paint={{ 'line-color': '#4B5563', 'line-width': 1.5, 'line-dasharray': [3, 2] }}
        />
      </Source>

      {/* Exclusion zones (buildings) inside proposals */}
      <Source id="exclusion-zones" type="geojson" data={exclusionGeoJSON}>
        <Layer id="exclusion-zones-fill" type="fill" paint={{ 'fill-color': '#C2410C', 'fill-opacity': 0.35 }} />
      </Source>

      {/* Proposals — colored by review status, selected one highlighted */}
      <Source id="proposal-blocks" type="geojson" data={proposalsGeoJSON}>
        <Layer
          id="proposal-blocks-fill"
          type="fill"
          paint={{
            'fill-color': ['match', ['get', 'status'], 'approved', STATUS_COLOR.approved, 'rejected', STATUS_COLOR.rejected, STATUS_COLOR.pending],
            'fill-opacity': ['case', ['get', 'selected'], 0.4, 0.15],
          }}
        />
        <Layer
          id="proposal-blocks-outline"
          type="line"
          paint={{
            'line-color': ['match', ['get', 'status'], 'approved', STATUS_COLOR.approved, 'rejected', STATUS_COLOR.rejected, STATUS_COLOR.pending],
            'line-width': ['case', ['get', 'selected'], 3, 1.5],
          }}
        />
        <Layer
          id="proposal-blocks-label"
          type="symbol"
          layout={{ 'text-field': ['get', 'code'], 'text-size': 10, 'text-allow-overlap': true }}
          paint={{ 'text-color': '#ffffff', 'text-halo-color': '#000000', 'text-halo-width': 1 }}
        />
      </Source>
    </BaseMap>
  )
}
