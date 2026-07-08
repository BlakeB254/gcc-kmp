'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import { useParams, useSearchParams } from 'next/navigation'
import type { BlockCompletionReportRow } from '@/db/schema'
import type { GpsTrack, AreaCleaned, ImagePair, CountsByType } from '@/lib/block-completion-report'
import { GpsTrackSvg } from '../GpsTrackSvg'

// ─────────────────────────────────────────────────────────────────────────
// Detail route param `id` is the jobId, not the report id. This reuses the
// existing job-keyed GET /api/jobs/[id]/completion-report (Task 1; admin
// can fetch any job's report) instead of adding a second by-report-id
// fetch route — the list page already links here with the jobId it has on
// hand. The Regenerate button below, however, calls
// POST /api/admin/reports/[id]/regenerate using the report's own `id`
// field (from the fetched row), not the jobId — Task 1's regenerate route
// treats [id] as a block_completion_reports.id first and only falls back
// to treating it as a jobId when no report has that id. Using the report's
// real id keeps the call unambiguous instead of relying on that fallback.
// ─────────────────────────────────────────────────────────────────────────

function formatDuration(seconds: number | null): string {
  if (seconds == null) return '—'
  const mins = Math.floor(seconds / 60)
  const hrs = Math.floor(mins / 60)
  if (hrs > 0) return `${hrs}h ${mins % 60}m ${seconds % 60}s`
  return `${mins}m ${seconds % 60}s`
}

function formatWeight(lbs: number | null): string {
  if (lbs == null) return '—'
  return `${lbs.toFixed(1)} lbs`
}

export default function AdminReportDetailPage() {
  const params = useParams<{ id: string }>()
  const searchParams = useSearchParams()
  const jobId = params.id
  const workerNameHint = searchParams.get('worker')

  const [report, setReport] = useState<BlockCompletionReportRow | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [regenerating, setRegenerating] = useState(false)
  const [toast, setToast] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null)

  const authHeaders = useMemo((): Record<string, string> => {
    if (typeof window === 'undefined') return {}
    const token = localStorage.getItem('adminToken')
    return token ? { Authorization: `Bearer ${token}` } : {}
  }, [])

  const fetchReport = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await fetch(`/api/jobs/${jobId}/completion-report`, { headers: authHeaders })
      const json = await res.json().catch(() => ({}))
      if (!res.ok) {
        throw new Error(json.error || `Report fetch failed (${res.status})`)
      }
      setReport(json.data)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load completion report.')
    } finally {
      setLoading(false)
    }
  }, [jobId, authHeaders])

  useEffect(() => {
    fetchReport()
  }, [fetchReport])

  const handleRegenerate = useCallback(async () => {
    if (!report) return
    setRegenerating(true)
    try {
      const res = await fetch(`/api/admin/reports/${report.id}/regenerate`, {
        method: 'POST',
        headers: authHeaders,
      })
      const json = await res.json().catch(() => ({}))
      if (!res.ok) throw new Error(json.error || `Regenerate failed (${res.status})`)
      setReport(json.data)
      setToast({ kind: 'ok', text: 'Report regenerated.' })
    } catch (e) {
      setToast({ kind: 'error', text: e instanceof Error ? e.message : 'Failed to regenerate report.' })
    } finally {
      setRegenerating(false)
      setTimeout(() => setToast(null), 6000)
    }
  }, [report, authHeaders])

  if (loading) {
    return <div className="p-6 text-neutral-500 text-sm">Loading report…</div>
  }

  if (error || !report) {
    return (
      <div className="p-6 space-y-4">
        <Link href="/admin/reports" className="text-sm text-neutral-400 hover:text-white transition-colors">
          &larr; Back to reports
        </Link>
        <div className="rounded-lg bg-red-950/40 border border-red-900/50 px-4 py-3 text-sm text-red-300">
          {error ?? 'Report not found.'}
        </div>
      </div>
    )
  }

  const gpsTrack = report.gpsTrack as GpsTrack | null
  const areasCleaned = (report.areasCleaned as AreaCleaned[] | null) ?? []
  const imagePairs = (report.imagePairs as ImagePair[] | null) ?? []
  const counts = report.countsByType as CountsByType | null

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <Link href="/admin/reports" className="text-sm text-neutral-400 hover:text-white transition-colors">
            &larr; Back to reports
          </Link>
          <h1 className="text-xl font-semibold text-white mt-2">
            Completion Report — Job #{report.jobId}
          </h1>
          <p className="text-sm text-neutral-400 mt-1">
            {workerNameHint || `Worker #${report.workerId}`} &middot; Route #{report.routeId}
          </p>
        </div>
        <button
          onClick={handleRegenerate}
          disabled={regenerating}
          className="px-4 py-2 rounded-md bg-blue-600 text-white text-sm font-medium hover:bg-blue-500 disabled:opacity-40 transition-colors"
        >
          {regenerating ? 'Regenerating…' : 'Regenerate'}
        </button>
      </div>

      {toast && (
        <div
          className={`text-xs rounded-md px-3 py-2 border ${
            toast.kind === 'ok'
              ? 'text-green-400 bg-green-950/40 border-green-900/50'
              : 'text-red-400 bg-red-950/40 border-red-900/50'
          }`}
        >
          {toast.text}
        </div>
      )}

      {/* Summary stats */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <div className="border border-neutral-800 rounded-lg p-4">
          <div className="text-xs text-neutral-500 mb-1">Duration</div>
          <div className="text-lg text-white font-medium">{formatDuration(report.durationSeconds)}</div>
        </div>
        <div className="border border-neutral-800 rounded-lg p-4">
          <div className="text-xs text-neutral-500 mb-1">Cleaned Weight</div>
          <div className="text-lg text-white font-medium">{formatWeight(report.cleanedEstimatedWeightLbs)}</div>
        </div>
        <div className="border border-neutral-800 rounded-lg p-4">
          <div className="text-xs text-neutral-500 mb-1">Total Est. Weight</div>
          <div className="text-lg text-white font-medium">{formatWeight(report.totalEstimatedWeightLbs)}</div>
        </div>
        <div className="border border-neutral-800 rounded-lg p-4">
          <div className="text-xs text-neutral-500 mb-1">Photo Pairs</div>
          <div className="text-lg text-white font-medium">{imagePairs.length}</div>
        </div>
      </div>

      <div className="text-xs text-neutral-500">
        Started {report.startedAt ? new Date(report.startedAt).toLocaleString() : '—'} &middot; Completed{' '}
        {report.completedAt ? new Date(report.completedAt).toLocaleString() : '—'}
      </div>

      {/* GPS track */}
      <section>
        <h2 className="text-sm font-semibold text-neutral-300 mb-2">GPS Track</h2>
        <GpsTrackSvg gpsTrack={gpsTrack} />
      </section>

      {/* Counts by type / severity */}
      {counts && (Object.keys(counts.byType ?? {}).length > 0 || Object.keys(counts.bySeverity ?? {}).length > 0) && (
        <section className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div>
            <h2 className="text-sm font-semibold text-neutral-300 mb-2">Counts by Type</h2>
            <div className="border border-neutral-800 rounded-lg divide-y divide-neutral-800">
              {Object.entries(counts.byType ?? {}).map(([type, count]) => (
                <div key={type} className="flex items-center justify-between px-4 py-2 text-sm">
                  <span className="text-neutral-300">{type}</span>
                  <span className="text-neutral-500">{count}</span>
                </div>
              ))}
              {Object.keys(counts.byType ?? {}).length === 0 && (
                <div className="px-4 py-2 text-sm text-neutral-600">None</div>
              )}
            </div>
          </div>
          <div>
            <h2 className="text-sm font-semibold text-neutral-300 mb-2">Counts by Severity</h2>
            <div className="border border-neutral-800 rounded-lg divide-y divide-neutral-800">
              {Object.entries(counts.bySeverity ?? {}).map(([severity, count]) => (
                <div key={severity} className="flex items-center justify-between px-4 py-2 text-sm">
                  <span className="text-neutral-300">{severity}</span>
                  <span className="text-neutral-500">{count}</span>
                </div>
              ))}
              {Object.keys(counts.bySeverity ?? {}).length === 0 && (
                <div className="px-4 py-2 text-sm text-neutral-600">None</div>
              )}
            </div>
          </div>
        </section>
      )}

      {/* Areas cleaned */}
      <section>
        <h2 className="text-sm font-semibold text-neutral-300 mb-2">
          Areas Cleaned ({areasCleaned.length})
        </h2>
        {areasCleaned.length === 0 ? (
          <div className="text-sm text-neutral-600">No cleaned areas recorded.</div>
        ) : (
          <div className="border border-neutral-800 rounded-lg overflow-hidden">
            <table className="w-full text-sm">
              <thead>
                <tr className="bg-neutral-900 text-neutral-400 text-left">
                  <th className="px-4 py-2 font-medium">Cell</th>
                  <th className="px-4 py-2 font-medium">Domain</th>
                  <th className="px-4 py-2 font-medium">Observations</th>
                  <th className="px-4 py-2 font-medium">Coordinates</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-neutral-800">
                {areasCleaned.map((a) => (
                  <tr key={a.locationCellId}>
                    <td className="px-4 py-2 font-mono text-neutral-400 text-xs">{a.locationCellId}</td>
                    <td className="px-4 py-2 text-neutral-300">{a.domain}</td>
                    <td className="px-4 py-2 text-neutral-400">{a.observationCount}</td>
                    <td className="px-4 py-2 text-neutral-500 text-xs">
                      {a.lat.toFixed(5)}, {a.lng.toFixed(5)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {/* Before/after image pairs */}
      <section>
        <h2 className="text-sm font-semibold text-neutral-300 mb-2">
          Before / After Photos ({imagePairs.length})
        </h2>
        {imagePairs.length === 0 ? (
          <div className="text-sm text-neutral-600">No photo pairs recorded.</div>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
            {imagePairs.map((pair, idx) => (
              <div key={`${pair.locationCellId}-${idx}`} className="border border-neutral-800 rounded-lg overflow-hidden">
                <div className="grid grid-cols-2 gap-px bg-neutral-800">
                  <div className="bg-neutral-900">
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img
                      src={pair.before.photoUrl}
                      alt="Before"
                      className="w-full h-32 object-cover"
                    />
                    <div className="px-2 py-1 text-[10px] text-neutral-500">Before</div>
                  </div>
                  <div className="bg-neutral-900">
                    {pair.after ? (
                      <>
                        {/* eslint-disable-next-line @next/next/no-img-element */}
                        <img
                          src={pair.after.photoUrl}
                          alt="After"
                          className="w-full h-32 object-cover"
                        />
                        <div className="px-2 py-1 text-[10px] text-neutral-500">After</div>
                      </>
                    ) : (
                      <div className="w-full h-32 flex items-center justify-center text-[10px] text-neutral-600">
                        No after photo
                      </div>
                    )}
                  </div>
                </div>
                <div className="px-3 py-2 text-xs text-neutral-500 flex items-center justify-between">
                  <span>{pair.itemType ?? 'unclassified'}</span>
                  <span className="font-mono text-neutral-600">{pair.locationCellId}</span>
                </div>
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  )
}
