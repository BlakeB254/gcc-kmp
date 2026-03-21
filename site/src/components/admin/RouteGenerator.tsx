'use client'

import { useState, useCallback } from 'react'
import { COMMUNITY_AREAS } from '@/lib/chicago-geo-data'
import { GeneratorMap } from './GeneratorMap'
import type { RouteProposal } from '@/lib/route-grid-generator'

interface Props {
  onCreated: () => void
}

export function RouteGenerator({ onCreated }: Props) {
  const [areaNumber, setAreaNumber] = useState<number>(29) // Default: North Lawndale
  const [serviceAreaId, setServiceAreaId] = useState(1)
  const [blockWidth, setBlockWidth] = useState(130)
  const [blockHeight, setBlockHeight] = useState(200)
  const [proposals, setProposals] = useState<RouteProposal[]>([])
  const [accepted, setAccepted] = useState<Set<string>>(new Set())
  const [existingRoutes, setExistingRoutes] = useState<{ type: string; coordinates: [number, number][] }[]>([])
  const [generating, setGenerating] = useState(false)
  const [accepting, setAccepting] = useState(false)
  const [error, setError] = useState('')
  const [result, setResult] = useState('')

  const selectedArea = COMMUNITY_AREAS.find((a) => a.number === areaNumber)

  const handleGenerate = useCallback(async () => {
    setGenerating(true)
    setError('')
    setResult('')
    setProposals([])
    setAccepted(new Set())

    try {
      const token = localStorage.getItem('adminToken')

      // Fetch existing routes for overlap display
      const routeRes = await fetch('/api/admin/routes', {
        headers: { Authorization: `Bearer ${token}` },
      })
      if (routeRes.ok) {
        const routeData = await routeRes.json()
        setExistingRoutes(
          (routeData.data || [])
            .filter((r: any) => r.geometry)
            .map((r: any) => r.geometry)
        )
      }

      const res = await fetch('/api/admin/routes/generate', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({
          communityAreaNumber: areaNumber,
          serviceAreaId,
          blockWidth,
          blockHeight,
        }),
      })

      if (!res.ok) {
        const data = await res.json()
        setError(data.error || 'Generation failed')
        return
      }

      const data = await res.json()
      setProposals(data.proposals || [])
      // Accept all by default
      setAccepted(new Set((data.proposals || []).map((p: RouteProposal) => p.id)))
    } catch {
      setError('Network error')
    } finally {
      setGenerating(false)
    }
  }, [areaNumber, serviceAreaId, blockWidth, blockHeight])

  function toggleProposal(id: string) {
    setAccepted((prev) => {
      const next = new Set(prev)
      if (next.has(id)) {
        next.delete(id)
      } else {
        next.add(id)
      }
      return next
    })
  }

  function selectAll() {
    setAccepted(new Set(proposals.map((p) => p.id)))
  }

  function deselectAll() {
    setAccepted(new Set())
  }

  async function handleAccept() {
    const selected = proposals.filter((p) => accepted.has(p.id))
    if (selected.length === 0) return

    setAccepting(true)
    setError('')
    setResult('')

    try {
      const token = localStorage.getItem('adminToken')
      const res = await fetch('/api/admin/routes/accept', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({
          serviceAreaId,
          proposals: selected.map((p) => ({
            routeCode: p.routeCode,
            blockCode: p.blockCode,
            displayName: p.displayName,
            geometry: p.geometry,
            blockPolygon: p.blockPolygon,
            center: p.center,
            distanceMeters: p.distanceMeters,
            estimatedMinutes: p.estimatedMinutes,
            side: p.side,
          })),
        }),
      })

      if (!res.ok) {
        const data = await res.json()
        setError(data.error || 'Accept failed')
        return
      }

      const data = await res.json()
      setResult(`Created ${data.created} route/block pairs`)
      setTimeout(() => onCreated(), 1500)
    } catch {
      setError('Network error')
    } finally {
      setAccepting(false)
    }
  }

  return (
    <div className="space-y-4">
      <h2 className="text-lg font-semibold text-white">Auto-Generate Route Grid</h2>

      {/* Controls */}
      <div className="flex flex-wrap gap-3 items-end">
        <label className="block">
          <span className="text-xs text-neutral-400">Community Area</span>
          <select
            value={areaNumber}
            onChange={(e) => setAreaNumber(Number(e.target.value))}
            className="mt-1 block w-56 px-3 py-2 rounded-md bg-neutral-900 border border-neutral-700 text-white text-sm focus:outline-none"
          >
            {COMMUNITY_AREAS.map((a) => (
              <option key={a.number} value={a.number}>
                {a.number}. {a.displayName}
              </option>
            ))}
          </select>
        </label>

        <label className="block">
          <span className="text-xs text-neutral-400">Block Width (m)</span>
          <input
            type="number"
            value={blockWidth}
            onChange={(e) => setBlockWidth(Number(e.target.value))}
            className="mt-1 block w-24 px-3 py-2 rounded-md bg-neutral-900 border border-neutral-700 text-white text-sm focus:outline-none"
          />
        </label>

        <label className="block">
          <span className="text-xs text-neutral-400">Block Height (m)</span>
          <input
            type="number"
            value={blockHeight}
            onChange={(e) => setBlockHeight(Number(e.target.value))}
            className="mt-1 block w-24 px-3 py-2 rounded-md bg-neutral-900 border border-neutral-700 text-white text-sm focus:outline-none"
          />
        </label>

        <button
          onClick={handleGenerate}
          disabled={generating}
          className="px-5 py-2 rounded-md bg-blue-600 text-white text-sm font-medium hover:bg-blue-500 disabled:opacity-50 transition-colors"
        >
          {generating ? 'Generating…' : 'Generate Preview'}
        </button>
      </div>

      {error && <div className="text-red-400 text-sm">{error}</div>}
      {result && <div className="text-green-400 text-sm">{result}</div>}

      {/* Map + selection controls */}
      {selectedArea && proposals.length > 0 && (
        <>
          <div className="flex items-center justify-between">
            <div className="text-sm text-neutral-400">
              <span className="text-white font-medium">{accepted.size}</span> of{' '}
              <span className="text-white font-medium">{proposals.length}</span> proposals selected
            </div>
            <div className="flex gap-2">
              <button
                onClick={selectAll}
                className="px-3 py-1 text-xs rounded-md bg-neutral-800 text-neutral-300 hover:bg-neutral-700 transition-colors"
              >
                Select All
              </button>
              <button
                onClick={deselectAll}
                className="px-3 py-1 text-xs rounded-md bg-neutral-800 text-neutral-300 hover:bg-neutral-700 transition-colors"
              >
                Deselect All
              </button>
              <button
                onClick={handleAccept}
                disabled={accepting || accepted.size === 0}
                className="px-4 py-1 text-xs rounded-md bg-green-600 text-white font-medium hover:bg-green-500 disabled:opacity-50 transition-colors"
              >
                {accepting ? 'Creating…' : `Accept ${accepted.size} Selected`}
              </button>
            </div>
          </div>

          <GeneratorMap
            areaPolygon={selectedArea.polygon}
            areaBounds={selectedArea.bounds}
            proposals={proposals}
            accepted={accepted}
            existingRoutes={existingRoutes}
            onToggle={toggleProposal}
          />

          {/* Legend */}
          <div className="flex gap-6 text-xs text-neutral-400">
            <span className="flex items-center gap-1.5">
              <span className="w-3 h-3 rounded-sm bg-green-500/40 border border-green-500 inline-block" />
              Accepted
            </span>
            <span className="flex items-center gap-1.5">
              <span className="w-3 h-3 rounded-sm bg-amber-500/40 border border-amber-500 inline-block" />
              Pending
            </span>
            <span className="flex items-center gap-1.5">
              <span className="w-3 h-3 rounded-sm bg-neutral-500/40 border border-neutral-500 inline-block" />
              Existing
            </span>
            <span className="flex items-center gap-1.5">
              <span className="w-3 h-3 rounded-sm border border-red-500 border-dashed inline-block" />
              Area Boundary
            </span>
          </div>
        </>
      )}

      {/* Empty state after generation */}
      {selectedArea && proposals.length === 0 && !generating && (
        <div className="text-neutral-500 text-sm py-8 text-center">
          {error ? 'Generation failed — adjust parameters and retry.' : 'Select a community area and click Generate Preview.'}
        </div>
      )}
    </div>
  )
}
