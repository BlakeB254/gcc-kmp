'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import Link from 'next/link'

interface ReportListRow {
  id: number
  jobId: number
  routeId: number
  workerId: number
  workerName: string | null
  startedAt: string | null
  completedAt: string | null
  durationSeconds: number | null
  totalEstimatedWeightLbs: number | null
  cleanedEstimatedWeightLbs: number | null
  imagePairCount: number
  createdAt: string
}

function formatDuration(seconds: number | null): string {
  if (seconds == null) return '—'
  const mins = Math.floor(seconds / 60)
  const hrs = Math.floor(mins / 60)
  if (hrs > 0) return `${hrs}h ${mins % 60}m`
  return `${mins}m ${seconds % 60}s`
}

function formatWeight(lbs: number | null): string {
  if (lbs == null) return '—'
  return `${lbs.toFixed(1)} lbs`
}

export default function AdminReportsPage() {
  const [reports, setReports] = useState<ReportListRow[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const authHeaders = useMemo((): Record<string, string> => {
    if (typeof window === 'undefined') return {}
    const token = localStorage.getItem('adminToken')
    return token ? { Authorization: `Bearer ${token}` } : {}
  }, [])

  const fetchReports = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await fetch('/api/admin/reports', { headers: authHeaders })
      if (!res.ok) throw new Error(`Reports: ${res.status}`)
      const json = await res.json()
      setReports(json.data || [])
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load completion reports.')
    } finally {
      setLoading(false)
    }
  }, [authHeaders])

  useEffect(() => {
    fetchReports()
  }, [fetchReports])

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold text-white">Block Completion Reports</h1>
        <button
          onClick={fetchReports}
          className="px-4 py-2 rounded-md bg-neutral-800 text-neutral-200 text-sm font-medium hover:bg-neutral-700 transition-colors"
        >
          Refresh
        </button>
      </div>

      {error && (
        <div className="rounded-lg bg-red-950/40 border border-red-900/50 px-4 py-2 text-sm text-red-300">
          {error}
        </div>
      )}

      {loading ? (
        <div className="text-neutral-500 text-sm">Loading reports…</div>
      ) : reports.length === 0 ? (
        <div className="text-neutral-500 text-sm">No completion reports yet.</div>
      ) : (
        <div className="border border-neutral-800 rounded-lg overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-neutral-900 text-neutral-400 text-left">
                <th className="px-4 py-3 font-medium">Completed</th>
                <th className="px-4 py-3 font-medium">Worker</th>
                <th className="px-4 py-3 font-medium">Duration</th>
                <th className="px-4 py-3 font-medium">Cleaned Weight</th>
                <th className="px-4 py-3 font-medium">Photo Pairs</th>
                <th className="px-4 py-3 font-medium">Job</th>
                <th className="px-4 py-3 font-medium"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-neutral-800">
              {reports.map((r) => (
                <tr key={r.id} className="hover:bg-neutral-900/50 transition-colors">
                  <td className="px-4 py-3 text-neutral-400 text-xs">
                    {r.completedAt ? new Date(r.completedAt).toLocaleString() : '—'}
                  </td>
                  <td className="px-4 py-3 text-neutral-200">
                    {r.workerName ?? `Worker #${r.workerId}`}
                  </td>
                  <td className="px-4 py-3 text-neutral-400">{formatDuration(r.durationSeconds)}</td>
                  <td className="px-4 py-3 text-neutral-400">
                    {formatWeight(r.cleanedEstimatedWeightLbs)}
                    {r.totalEstimatedWeightLbs != null && (
                      <span className="text-neutral-600"> / {formatWeight(r.totalEstimatedWeightLbs)}</span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-neutral-400">{r.imagePairCount}</td>
                  <td className="px-4 py-3 font-mono text-neutral-500 text-xs">#{r.jobId}</td>
                  <td className="px-4 py-3">
                    <Link
                      href={`/admin/reports/${r.jobId}?worker=${encodeURIComponent(r.workerName ?? '')}`}
                      className="px-2 py-1 rounded text-xs font-medium bg-neutral-800 text-neutral-300 hover:bg-neutral-700 transition-colors"
                    >
                      View
                    </Link>
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
