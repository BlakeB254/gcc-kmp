'use client'

import { useState, useCallback, useMemo } from 'react'
import { Source, Layer } from 'react-map-gl/maplibre'
import { BaseMap } from '@/components/map/BaseMap'
import { ZOOM } from '@/lib/design-tokens'

interface Props {
  blockId: number
  /** Optional map center to start drawing from; defaults to the Chicago service area center. */
  center?: { longitude: number; latitude: number }
  onCreated: () => void
}

export function ExclusionZoneDrawer({ blockId, center, onCreated }: Props) {
  const [coords, setCoords] = useState<[number, number][]>([])
  const [label, setLabel] = useState('')
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

  const polygonGeoJSON = useMemo(() => {
    if (coords.length < 3) return null
    const ring = [...coords, coords[0]] // close the ring
    return {
      type: 'Feature' as const,
      properties: {},
      geometry: {
        type: 'Polygon' as const,
        coordinates: [ring],
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
    if (coords.length < 3) {
      setError('Click at least 3 points to draw a polygon')
      return
    }

    setSaving(true)
    setError('')

    try {
      const ring = [...coords, coords[0]]
      const token = localStorage.getItem('adminToken')
      const res = await fetch(`/api/admin/blocks/${blockId}/exclusion-zones`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({
          polygon: { type: 'Polygon', coordinates: [ring] },
          label: label.trim() || undefined,
        }),
      })

      if (!res.ok) {
        const data = await res.json()
        setError(data.error || 'Failed to create exclusion zone')
        return
      }

      setCoords([])
      setLabel('')
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
          initialViewState={{
            longitude: center?.longitude ?? -87.72,
            latitude: center?.latitude ?? 41.86,
            zoom: ZOOM.STREET,
          }}
          onClick={handleMapClick}
          cursor="crosshair"
        >
          {/* Exclusion zone polygon */}
          {polygonGeoJSON && (
            <Source id="exclusion-zone-draft" type="geojson" data={polygonGeoJSON}>
              <Layer
                id="exclusion-zone-fill"
                type="fill"
                paint={{
                  'fill-color': '#EF4444',
                  'fill-opacity': 0.2,
                }}
              />
              <Layer
                id="exclusion-zone-outline"
                type="line"
                paint={{
                  'line-color': '#EF4444',
                  'line-width': 2,
                }}
              />
            </Source>
          )}

          {/* Vertices */}
          <Source id="exclusion-zone-points" type="geojson" data={pointsGeoJSON}>
            <Layer
              id="exclusion-zone-points-layer"
              type="circle"
              paint={{
                'circle-radius': 6,
                'circle-color': '#ffffff',
                'circle-stroke-color': '#EF4444',
                'circle-stroke-width': 2,
              }}
            />
          </Source>
        </BaseMap>
      </div>

      {/* Side panel */}
      <div className="space-y-4 overflow-auto">
        <h2 className="text-lg font-semibold text-white">Draw Exclusion Zone</h2>
        <p className="text-xs text-neutral-500">
          Click on the map to place polygon points (e.g. around a gated yard). The zone is excluded from scan coverage for this block.
        </p>

        {/* Stats */}
        <div className="grid grid-cols-2 gap-2 text-xs">
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
          <span className="text-xs text-neutral-400">Label</span>
          <input
            value={label}
            onChange={(e) => setLabel(e.target.value)}
            placeholder="e.g. gated yard at 123 Main St"
            className="mt-1 w-full px-3 py-2 rounded-md bg-neutral-900 border border-neutral-700 text-white text-sm focus:outline-none focus:border-neutral-500"
          />
        </label>

        {error && <div className="text-red-400 text-sm">{error}</div>}

        <button
          onClick={handleSave}
          disabled={saving || coords.length < 3}
          className="w-full py-2.5 rounded-md bg-red-600 text-white text-sm font-medium hover:bg-red-500 disabled:opacity-50 transition-colors"
        >
          {saving ? 'Saving…' : 'Save Exclusion Zone'}
        </button>
      </div>
    </div>
  )
}
