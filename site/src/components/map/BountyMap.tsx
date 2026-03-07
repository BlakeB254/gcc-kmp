'use client'

import { useState, useCallback, useMemo } from 'react'
import Map, { Source, Layer, Marker, Popup, NavigationControl, ScaleControl } from 'react-map-gl/maplibre'
import 'maplibre-gl/dist/maplibre-gl.css'
import {
  MAP_STYLE_URL,
  CHICAGO_CENTER_LNG_LAT,
  ZOOM,
  BOUNTY_COLORS,
  SPOT_COLORS,
} from '@/lib/design-tokens'

// ============================================================================
// TYPES
// ============================================================================

type BountyStatus = 'open' | 'in_progress' | 'completed'

interface Bounty {
  id: string
  title: string
  description: string
  amountCents: number
  status: BountyStatus
  pledgeCount: number
  coordinates: [number, number] // [lng, lat]
}

interface SpotReport {
  id: string
  coordinates: [number, number] // [lng, lat]
  radiusMeters: number
  label?: string
}

interface BountyMapProps {
  bounties: Bounty[]
  spots?: SpotReport[]
  onBountySelect?: (bounty: Bounty) => void
  height?: string
  showLegend?: boolean
}

// ============================================================================
// STATUS COLOR MAPPING
// ============================================================================

const BOUNTY_STATUS_COLOR: Record<BountyStatus, string> = {
  open: BOUNTY_COLORS.OPEN,
  in_progress: BOUNTY_COLORS.IN_PROGRESS,
  completed: BOUNTY_COLORS.COMPLETED,
}

const STATUS_LABELS: Record<BountyStatus, string> = {
  open: 'Open',
  in_progress: 'In Progress',
  completed: 'Completed',
}

// ============================================================================
// PULSE ANIMATION STYLES
// ============================================================================

const pulseKeyframes = `
@keyframes bounty-pulse {
  0% {
    box-shadow: 0 0 0 0 rgba(239, 68, 68, 0.6);
  }
  70% {
    box-shadow: 0 0 0 12px rgba(239, 68, 68, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(239, 68, 68, 0);
  }
}
`

// ============================================================================
// COMPONENT
// ============================================================================

export function BountyMap({
  bounties,
  spots = [],
  onBountySelect,
  height = '400px',
  showLegend = true,
}: BountyMapProps) {
  const [popupInfo, setPopupInfo] = useState<Bounty | null>(null)

  const handleMarkerClick = useCallback(
    (bounty: Bounty) => {
      setPopupInfo(bounty)
      onBountySelect?.(bounty)
    },
    [onBountySelect]
  )

  // Convert spot reports to GeoJSON for radius circles
  const spotGeoJson = useMemo(
    () => ({
      type: 'FeatureCollection' as const,
      features: spots.map((spot) => ({
        type: 'Feature' as const,
        properties: {
          id: spot.id,
          radiusMeters: spot.radiusMeters,
          label: spot.label || '',
        },
        geometry: {
          type: 'Point' as const,
          coordinates: spot.coordinates,
        },
      })),
    }),
    [spots]
  )

  const initialViewState = {
    longitude: CHICAGO_CENTER_LNG_LAT[0],
    latitude: CHICAGO_CENTER_LNG_LAT[1],
    zoom: ZOOM.NEIGHBORHOOD,
  }

  return (
    <div
      style={{ height, width: '100%' }}
      className="rounded-xl overflow-hidden relative"
    >
      {/* Inject pulse animation */}
      <style dangerouslySetInnerHTML={{ __html: pulseKeyframes }} />

      <Map
        initialViewState={initialViewState}
        mapStyle={MAP_STYLE_URL}
        cursor="pointer"
      >
        <NavigationControl position="top-right" />
        <ScaleControl position="bottom-left" />

        {/* ============================================================ */}
        {/* SPOT REPORT RADIUS CIRCLES                                  */}
        {/* ============================================================ */}

        {spots.length > 0 && (
          <Source id="spots" type="geojson" data={spotGeoJson}>
            {/* Radius fill circle */}
            <Layer
              id="spot-radius-fill"
              type="circle"
              paint={{
                'circle-radius': [
                  'interpolate',
                  ['linear'],
                  ['zoom'],
                  12, 4,
                  16, ['get', 'radiusMeters'],
                  20, ['*', ['get', 'radiusMeters'], 4],
                ],
                'circle-color': SPOT_COLORS.RADIUS_FILL,
                'circle-stroke-color': SPOT_COLORS.RADIUS_STROKE,
                'circle-stroke-width': 1.5,
                'circle-opacity': 0.6,
              }}
            />
            {/* Spot center marker */}
            <Layer
              id="spot-center"
              type="circle"
              paint={{
                'circle-radius': 5,
                'circle-color': SPOT_COLORS.MARKER,
                'circle-stroke-color': '#ffffff',
                'circle-stroke-width': 2,
              }}
            />
          </Source>
        )}

        {/* ============================================================ */}
        {/* BOUNTY MARKERS                                              */}
        {/* ============================================================ */}

        {bounties.map((bounty) => (
          <Marker
            key={bounty.id}
            longitude={bounty.coordinates[0]}
            latitude={bounty.coordinates[1]}
            anchor="center"
            onClick={(e) => {
              e.originalEvent.stopPropagation()
              handleMarkerClick(bounty)
            }}
          >
            <div
              className="flex items-center justify-center rounded-full border-2 border-white cursor-pointer select-none"
              style={{
                width: 36,
                height: 36,
                backgroundColor: BOUNTY_STATUS_COLOR[bounty.status],
                animation:
                  bounty.status === 'open'
                    ? 'bounty-pulse 2s infinite'
                    : undefined,
              }}
            >
              <span className="text-white text-xs font-bold leading-none">
                ${(bounty.amountCents / 100).toFixed(0)}
              </span>
            </div>
          </Marker>
        ))}

        {/* ============================================================ */}
        {/* POPUP                                                       */}
        {/* ============================================================ */}

        {popupInfo && (
          <Popup
            longitude={popupInfo.coordinates[0]}
            latitude={popupInfo.coordinates[1]}
            anchor="bottom"
            onClose={() => setPopupInfo(null)}
            closeButton={true}
          >
            <div className="p-3 min-w-[240px]">
              <div className="flex items-center justify-between mb-2">
                <span className="font-bold text-neutral-900">
                  {popupInfo.title}
                </span>
                <span
                  className="text-xs px-2 py-0.5 rounded-full text-white font-medium"
                  style={{
                    backgroundColor: BOUNTY_STATUS_COLOR[popupInfo.status],
                  }}
                >
                  {STATUS_LABELS[popupInfo.status]}
                </span>
              </div>
              <p className="text-sm text-neutral-600 mb-2 line-clamp-2">
                {popupInfo.description}
              </p>
              <div className="flex items-center justify-between mt-3 pt-3 border-t">
                <span className="font-semibold text-green-600 text-lg">
                  ${(popupInfo.amountCents / 100).toFixed(0)}
                </span>
                <span className="text-xs text-neutral-500">
                  {popupInfo.pledgeCount}{' '}
                  {popupInfo.pledgeCount === 1 ? 'pledge' : 'pledges'}
                </span>
              </div>
            </div>
          </Popup>
        )}
      </Map>

      {/* Legend */}
      {showLegend && (
        <div className="absolute bottom-4 left-4 bg-white/90 backdrop-blur-sm rounded-lg px-3 py-2 shadow-md">
          <p className="text-xs font-semibold text-neutral-700 mb-1.5">
            Bounties
          </p>
          <div className="flex flex-col gap-1">
            {(
              Object.entries(STATUS_LABELS) as [BountyStatus, string][]
            ).map(([status, label]) => (
              <div key={status} className="flex items-center gap-1.5">
                <span
                  className="w-3 h-3 rounded-full"
                  style={{ backgroundColor: BOUNTY_STATUS_COLOR[status] }}
                />
                <span className="text-xs text-neutral-600">{label}</span>
              </div>
            ))}
            {spots.length > 0 && (
              <>
                <div className="border-t border-neutral-200 my-1" />
                <div className="flex items-center gap-1.5">
                  <span
                    className="w-3 h-3 rounded-full"
                    style={{ backgroundColor: SPOT_COLORS.MARKER }}
                  />
                  <span className="text-xs text-neutral-600">Spot Report</span>
                </div>
              </>
            )}
          </div>
        </div>
      )}
    </div>
  )
}

// Re-export types
export type { Bounty, BountyStatus, SpotReport, BountyMapProps }
