'use client'

import { useState, useCallback, useMemo } from 'react'
import { Source, Layer } from 'react-map-gl/maplibre'
import { BaseMap } from '@/components/map/BaseMap'
import { ZOOM } from '@/lib/design-tokens'
import { haversineDistance, estimateWalkingMinutes, lineStringToBlockPolygon } from '@/lib/geo-utils'

interface Props {
  onCreated: () => void
}

export function RouteDrawer({ onCreated }: Props) {
  const [coords, setCoords] = useState<[number, number][]>([])
  const [displayName, setDisplayName] = useState('')
  const [serviceAreaId, setServiceAreaId] = useState(1)
  const [payoutAmount, setPayoutAmount] = useState(10000)
  const [side, setSide] = useState<'north' | 'south'>('north')
  const [streetName, setStreetName] = useState('')
  const [fromCorner, setFromCorner] = useState('')
  const [toCorner, setToCorner] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

  const handleMapClick = useCallback((e: any) => {
    const { lng, lat } = e.lngLat
    setCoords((prev) => [...prev, [lng, lat]])
  }, [])

  const undoLast = useCallback(() => {
    setCoords((prev) => prev.slice(0, -1))
  }, [])

  const clearAll = useCallback(() => {
    setCoords([])
  }, [])

  const distance = useMemo(() => {
    if (coords.length < 2) return 0
    return haversineDistance(coords)
  }, [coords])

  const walkTime = useMemo(() => estimateWalkingMinutes(distance), [distance])

  const lineGeoJSON = useMemo(() => ({
    type: 'Feature' as const,
    properties: {},
    geometry: {
      type: 'LineString' as const,
      coordinates: coords,
    },
  }), [coords])

  const blockPolygonGeoJSON = useMemo(() => {
    if (coords.length < 2) return null
    const poly = lineStringToBlockPolygon(coords)
    return {
      type: 'Feature' as const,
      properties: {},
      geometry: {
        type: 'Polygon' as const,
        coordinates: poly,
      },
    }
  }, [coords])

  const pointsGeoJSON = useMemo(() => ({
    type: 'FeatureCollection' as const,
    features: coords.map((c, i) => ({
      type: 'Feature' as const,
      properties: { index: i },
      geometry: { type: 'Point' as const, coordinates: c },
    })),
  }), [coords])

  async function handleSave() {
    if (coords.length < 2) {
      setError('Draw at least 2 points on the map')
      return
    }
    if (!displayName.trim()) {
      setError('Name is required')
      return
    }

    setSaving(true)
    setError('')

    try {
      const token = localStorage.getItem('adminToken')
      const res = await fetch('/api/admin/routes', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({
          displayName: displayName.trim(),
          serviceAreaId,
          geometry: { type: 'LineString', coordinates: coords },
          payoutAmount,
          side,
          streetName: streetName || undefined,
          fromCorner: fromCorner || undefined,
          toCorner: toCorner || undefined,
        }),
      })

      if (!res.ok) {
        const data = await res.json()
        setError(data.error || 'Failed to create route')
        return
      }

      onCreated()
    } catch {
      setError('Network error')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="grid grid-cols-[1fr_320px] gap-4 h-[calc(100vh-120px)]">
      {/* Map */}
      <div className="rounded-lg overflow-hidden border border-neutral-800">
        <BaseMap
          height="100%"
          initialViewState={{ longitude: -87.72, latitude: 41.86, zoom: ZOOM.ROUTE }}
          onClick={handleMapClick}
          cursor="crosshair"
        >
          {/* Route line */}
          {coords.length >= 2 && (
            <Source id="route-line" type="geojson" data={lineGeoJSON}>
              <Layer
                id="route-line-layer"
                type="line"
                paint={{
                  'line-color': '#22C55E',
                  'line-width': 4,
                  'line-dasharray': [2, 1],
                }}
              />
            </Source>
          )}

          {/* Block polygon preview */}
          {blockPolygonGeoJSON && (
            <Source id="block-preview" type="geojson" data={blockPolygonGeoJSON}>
              <Layer
                id="block-fill"
                type="fill"
                paint={{
                  'fill-color': '#22C55E',
                  'fill-opacity': 0.1,
                }}
              />
              <Layer
                id="block-outline"
                type="line"
                paint={{
                  'line-color': '#22C55E',
                  'line-width': 1,
                  'line-opacity': 0.5,
                }}
              />
            </Source>
          )}

          {/* Vertices */}
          <Source id="route-points" type="geojson" data={pointsGeoJSON}>
            <Layer
              id="route-points-layer"
              type="circle"
              paint={{
                'circle-radius': 6,
                'circle-color': '#ffffff',
                'circle-stroke-color': '#22C55E',
                'circle-stroke-width': 2,
              }}
            />
          </Source>
        </BaseMap>
      </div>

      {/* Side panel */}
      <div className="space-y-4 overflow-auto">
        <h2 className="text-lg font-semibold text-white">Draw Route</h2>
        <p className="text-xs text-neutral-500">Click on the map to place route points. The block polygon is auto-generated.</p>

        {/* Stats */}
        <div className="grid grid-cols-2 gap-2 text-xs">
          <div className="bg-neutral-900 rounded-md p-2">
            <div className="text-neutral-500">Distance</div>
            <div className="text-white font-mono">{Math.round(distance)}m</div>
          </div>
          <div className="bg-neutral-900 rounded-md p-2">
            <div className="text-neutral-500">Walk time</div>
            <div className="text-white font-mono">{walkTime} min</div>
          </div>
          <div className="bg-neutral-900 rounded-md p-2 col-span-2">
            <div className="text-neutral-500">Points</div>
            <div className="text-white font-mono">{coords.length}</div>
          </div>
        </div>

        <div className="flex gap-2">
          <button
            onClick={undoLast}
            disabled={coords.length === 0}
            className="flex-1 py-1.5 text-xs rounded-md bg-neutral-800 text-neutral-300 hover:bg-neutral-700 disabled:opacity-30 transition-colors"
          >
            Undo
          </button>
          <button
            onClick={clearAll}
            disabled={coords.length === 0}
            className="flex-1 py-1.5 text-xs rounded-md bg-neutral-800 text-neutral-300 hover:bg-neutral-700 disabled:opacity-30 transition-colors"
          >
            Clear
          </button>
        </div>

        <hr className="border-neutral-800" />

        {/* Form */}
        <label className="block">
          <span className="text-xs text-neutral-400">Route name *</span>
          <input
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            placeholder="e.g. Roosevelt Rd Block 6"
            className="mt-1 w-full px-3 py-2 rounded-md bg-neutral-900 border border-neutral-700 text-white text-sm focus:outline-none focus:border-neutral-500"
          />
        </label>

        <div className="grid grid-cols-2 gap-2">
          <label className="block">
            <span className="text-xs text-neutral-400">Side</span>
            <select
              value={side}
              onChange={(e) => setSide(e.target.value as 'north' | 'south')}
              className="mt-1 w-full px-3 py-2 rounded-md bg-neutral-900 border border-neutral-700 text-white text-sm focus:outline-none"
            >
              <option value="north">North</option>
              <option value="south">South</option>
            </select>
          </label>
          <label className="block">
            <span className="text-xs text-neutral-400">Payout ($)</span>
            <input
              type="number"
              value={payoutAmount / 100}
              onChange={(e) => setPayoutAmount(Number(e.target.value) * 100)}
              className="mt-1 w-full px-3 py-2 rounded-md bg-neutral-900 border border-neutral-700 text-white text-sm focus:outline-none focus:border-neutral-500"
            />
          </label>
        </div>

        <label className="block">
          <span className="text-xs text-neutral-400">Street name</span>
          <input
            value={streetName}
            onChange={(e) => setStreetName(e.target.value)}
            placeholder="e.g. W Roosevelt Rd"
            className="mt-1 w-full px-3 py-2 rounded-md bg-neutral-900 border border-neutral-700 text-white text-sm focus:outline-none focus:border-neutral-500"
          />
        </label>

        <div className="grid grid-cols-2 gap-2">
          <label className="block">
            <span className="text-xs text-neutral-400">From corner</span>
            <input
              value={fromCorner}
              onChange={(e) => setFromCorner(e.target.value)}
              placeholder="S Pulaski Rd"
              className="mt-1 w-full px-3 py-2 rounded-md bg-neutral-900 border border-neutral-700 text-white text-sm focus:outline-none focus:border-neutral-500"
            />
          </label>
          <label className="block">
            <span className="text-xs text-neutral-400">To corner</span>
            <input
              value={toCorner}
              onChange={(e) => setToCorner(e.target.value)}
              placeholder="S Keeler Ave"
              className="mt-1 w-full px-3 py-2 rounded-md bg-neutral-900 border border-neutral-700 text-white text-sm focus:outline-none focus:border-neutral-500"
            />
          </label>
        </div>

        {error && <div className="text-red-400 text-sm">{error}</div>}

        <button
          onClick={handleSave}
          disabled={saving || coords.length < 2}
          className="w-full py-2.5 rounded-md bg-green-600 text-white text-sm font-medium hover:bg-green-500 disabled:opacity-50 transition-colors"
        >
          {saving ? 'Creating…' : 'Create Route + Block'}
        </button>
      </div>
    </div>
  )
}
