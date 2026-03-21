'use client'

import { useEffect, useState, useCallback } from 'react'
import { RouteDrawer } from '@/components/admin/RouteDrawer'
import { RouteGenerator } from '@/components/admin/RouteGenerator'

interface RouteRow {
  id: number
  code: string
  displayName: string
  status: string
  distanceMeters: number | null
  estimatedDurationMinutes: number | null
  payoutAmount: number
  createdAt: string
}

type View = 'list' | 'draw' | 'generate'

export default function AdminRoutesPage() {
  const [routes, setRoutes] = useState<RouteRow[]>([])
  const [loading, setLoading] = useState(true)
  const [view, setView] = useState<View>('list')

  const fetchRoutes = useCallback(async () => {
    setLoading(true)
    try {
      const token = localStorage.getItem('adminToken')
      const res = await fetch('/api/admin/routes', {
        headers: { Authorization: `Bearer ${token}` },
      })
      if (res.ok) {
        const json = await res.json()
        setRoutes(json.data || [])
      }
    } catch {
      // silent
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchRoutes()
  }, [fetchRoutes])

  function handleCreated() {
    setView('list')
    fetchRoutes()
  }

  if (view === 'draw') {
    return (
      <div className="p-6">
        <button
          onClick={() => setView('list')}
          className="mb-4 text-sm text-neutral-400 hover:text-white transition-colors"
        >
          &larr; Back to routes
        </button>
        <RouteDrawer onCreated={handleCreated} />
      </div>
    )
  }

  if (view === 'generate') {
    return (
      <div className="p-6">
        <button
          onClick={() => setView('list')}
          className="mb-4 text-sm text-neutral-400 hover:text-white transition-colors"
        >
          &larr; Back to routes
        </button>
        <RouteGenerator onCreated={handleCreated} />
      </div>
    )
  }

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold text-white">Routes & Blocks</h1>
        <div className="flex gap-2">
          <button
            onClick={() => setView('draw')}
            className="px-4 py-2 rounded-md bg-green-600 text-white text-sm font-medium hover:bg-green-500 transition-colors"
          >
            Create Route
          </button>
          <button
            onClick={() => setView('generate')}
            className="px-4 py-2 rounded-md bg-blue-600 text-white text-sm font-medium hover:bg-blue-500 transition-colors"
          >
            Auto-Generate
          </button>
        </div>
      </div>

      {loading ? (
        <div className="text-neutral-500 text-sm">Loading routes…</div>
      ) : routes.length === 0 ? (
        <div className="text-neutral-500 text-sm">No routes yet. Create one or auto-generate a grid.</div>
      ) : (
        <div className="border border-neutral-800 rounded-lg overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-neutral-900 text-neutral-400 text-left">
                <th className="px-4 py-3 font-medium">Code</th>
                <th className="px-4 py-3 font-medium">Name</th>
                <th className="px-4 py-3 font-medium">Distance</th>
                <th className="px-4 py-3 font-medium">Time</th>
                <th className="px-4 py-3 font-medium">Payout</th>
                <th className="px-4 py-3 font-medium">Status</th>
                <th className="px-4 py-3 font-medium">Created</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-neutral-800">
              {routes.map((r) => (
                <tr key={r.id} className="hover:bg-neutral-900/50 transition-colors">
                  <td className="px-4 py-3 font-mono text-green-400">{r.code}</td>
                  <td className="px-4 py-3 text-neutral-200">{r.displayName}</td>
                  <td className="px-4 py-3 text-neutral-400">
                    {r.distanceMeters ? `${Math.round(r.distanceMeters)}m` : '—'}
                  </td>
                  <td className="px-4 py-3 text-neutral-400">
                    {r.estimatedDurationMinutes ? `${r.estimatedDurationMinutes} min` : '—'}
                  </td>
                  <td className="px-4 py-3 text-neutral-400">
                    ${(r.payoutAmount / 100).toFixed(0)}
                  </td>
                  <td className="px-4 py-3">
                    <span
                      className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${
                        r.status === 'published'
                          ? 'bg-green-900/40 text-green-400'
                          : r.status === 'draft'
                            ? 'bg-neutral-800 text-neutral-400'
                            : 'bg-yellow-900/40 text-yellow-400'
                      }`}
                    >
                      {r.status}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-neutral-500 text-xs">
                    {new Date(r.createdAt).toLocaleDateString()}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
