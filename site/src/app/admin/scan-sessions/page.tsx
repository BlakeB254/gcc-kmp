'use client'
import { useCallback, useEffect, useMemo, useState } from 'react'
import Link from 'next/link'

// Admin "Sessions" list (scan-session-replay PRD Phase 2): every indexed
// scan session with its flight-recorder headline metrics and derived red
// flags — the triage view that replaces asking testers for screen
// recordings. Click-through to the replay page.

export interface ScanSessionListRow {
  id: number
  sessionId: string
  deviceId: string
  deviceModel: string | null
  platform: string | null
  osVersion: string | null
  lapKind: string | null
  routeId: number | null
  locationCellId: string | null
  startedAt: string | null
  completedAt: string | null
  photoCount: number
  chunkCount: number
  observedItemCount: number
  scannedCellCount: number
  totalBytes: number | null
  geoRefEventCount: number
  eventLogR2Key: string | null
  eventCount: number | null
  gateFlipCount: number | null
  tapCount: number | null
  confirmCount: number | null
  rejectCount: number | null
  raycastMissCount: number | null
  detectionCount: number | null
  uploadAttemptCount: number | null
  uploadFailureCount: number | null
  errorCount: number | null
  indexedAt: string
  /** Server-derived (lib/scan-session-flags.ts) so the site panel and the
   *  iOS admin (GccApiClient/AdminScanSessionSummary) render identical
   *  flags from one threshold set. */
  redFlags: string[]
}

function fmtDuration(startedAt: string | null, completedAt: string | null): string {
  if (!startedAt || !completedAt) return '—'
  const ms = new Date(completedAt).getTime() - new Date(startedAt).getTime()
  if (!Number.isFinite(ms) || ms < 0) return '—'
  const totalSec = Math.round(ms / 1000)
  return `${Math.floor(totalSec / 60)}m${String(totalSec % 60).padStart(2, '0')}s`
}

function fmtBytes(bytes: number | null): string {
  if (bytes == null) return '—'
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)}KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)}MB`
}

export default function AdminScanSessionsPage() {
  const [sessions, setSessions] = useState<ScanSessionListRow[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [lapKind, setLapKind] = useState('')
  const [days, setDays] = useState('')
  const [deviceId, setDeviceId] = useState('')
  const [flaggedOnly, setFlaggedOnly] = useState(false)

  const authHeaders = useMemo((): Record<string, string> => {
    if (typeof window === 'undefined') return {}
    const t = localStorage.getItem('adminToken')
    return t ? { Authorization: `Bearer ${t}` } : {}
  }, [])

  const loadSessions = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const params = new URLSearchParams()
      if (lapKind) params.set('lapKind', lapKind)
      if (days) params.set('days', days)
      if (deviceId) params.set('deviceId', deviceId)
      const qs = params.toString()
      const res = await fetch(`/api/admin/scan-sessions${qs ? `?${qs}` : ''}`, { headers: authHeaders })
      if (!res.ok) throw new Error(`Sessions: ${res.status}`)
      const body = await res.json()
      setSessions(body.data || [])
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load')
    } finally {
      setLoading(false)
    }
  }, [authHeaders, lapKind, days, deviceId])

  useEffect(() => {
    loadSessions()
  }, [loadSessions])

  const visible = flaggedOnly ? sessions.filter((s) => (s.redFlags ?? []).length > 0) : sessions

  const selectCls =
    'rounded-md border border-neutral-700 bg-neutral-900 px-2 py-1.5 text-xs text-neutral-200'

  return (
    <div className="p-6 text-white">
      <h1 className="mb-1 text-xl font-semibold">Scan Sessions</h1>
      <p className="mb-4 text-xs text-neutral-500">
        Every indexed field session with its flight-recorder metrics. Click a session to replay it.
      </p>

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <select value={lapKind} onChange={(e) => setLapKind(e.target.value)} className={selectCls}>
          <option value="">All laps</option>
          <option value="baseline">Baseline (scout)</option>
          <option value="clean">Clean</option>
          <option value="recovered">Recovered (crashed)</option>
        </select>
        <select value={days} onChange={(e) => setDays(e.target.value)} className={selectCls}>
          <option value="">All time</option>
          <option value="1">Last 24h</option>
          <option value="7">Last 7 days</option>
          <option value="30">Last 30 days</option>
        </select>
        <input
          value={deviceId}
          onChange={(e) => setDeviceId(e.target.value)}
          placeholder="Device ID"
          className={`${selectCls} w-44 font-mono placeholder:text-neutral-600`}
        />
        <label className="flex items-center gap-1.5 text-xs text-neutral-400">
          <input
            type="checkbox"
            checked={flaggedOnly}
            onChange={(e) => setFlaggedOnly(e.target.checked)}
            className="accent-red-500"
          />
          Red-flagged only
        </label>
        <span className="ml-auto text-xs text-neutral-500">
          {visible.length} session{visible.length === 1 ? '' : 's'}
        </span>
      </div>

      {loading && <p className="text-neutral-400">Loading...</p>}
      {error && (
        <div className="mb-4 rounded-lg bg-red-950/40 px-4 py-2 text-sm text-red-300">{error}</div>
      )}
      {!loading && !error && visible.length === 0 && (
        <p className="text-neutral-400">No scan sessions match.</p>
      )}

      {!loading && !error && visible.length > 0 && (
        <div className="overflow-x-auto rounded-lg border border-neutral-800 bg-neutral-900">
          <table className="w-full text-left text-xs">
            <thead>
              <tr className="border-b border-neutral-800 text-neutral-500">
                <th className="px-3 py-2 font-medium">Started</th>
                <th className="px-3 py-2 font-medium">Lap</th>
                <th className="px-3 py-2 font-medium">Device</th>
                <th className="px-3 py-2 font-medium">Duration</th>
                <th className="px-3 py-2 font-medium text-right">Items</th>
                <th className="px-3 py-2 font-medium text-right">Cells</th>
                <th className="px-3 py-2 font-medium text-right">Taps</th>
                <th className="px-3 py-2 font-medium text-right">Confirms</th>
                <th className="px-3 py-2 font-medium text-right">Gate flips</th>
                <th className="px-3 py-2 font-medium text-right">Bytes</th>
                <th className="px-3 py-2 font-medium">Flags</th>
              </tr>
            </thead>
            <tbody>
              {visible.map((s) => {
                const flags = s.redFlags ?? []
                return (
                  <tr key={s.id} className="border-b border-neutral-800 last:border-b-0 hover:bg-neutral-800/50">
                    <td className="px-3 py-2 whitespace-nowrap">
                      <Link
                        href={`/admin/scan-sessions/${encodeURIComponent(s.sessionId)}`}
                        className="text-emerald-400 hover:underline"
                      >
                        {s.startedAt ? new Date(s.startedAt).toLocaleString() : s.sessionId}
                      </Link>
                    </td>
                    <td className="px-3 py-2">
                      <span
                        className={
                          s.lapKind === 'recovered'
                            ? 'text-red-400'
                            : s.lapKind === 'clean'
                              ? 'text-green-400'
                              : 'text-neutral-300'
                        }
                      >
                        {s.lapKind ?? 'env'}
                      </span>
                    </td>
                    <td className="px-3 py-2 font-mono text-neutral-400">
                      {s.deviceModel ?? '—'}
                      <span className="text-neutral-600"> {s.osVersion ?? ''}</span>
                    </td>
                    <td className="px-3 py-2">{fmtDuration(s.startedAt, s.completedAt)}</td>
                    <td className="px-3 py-2 text-right">{s.observedItemCount}</td>
                    <td className="px-3 py-2 text-right">{s.scannedCellCount}</td>
                    <td className="px-3 py-2 text-right">{s.tapCount ?? '—'}</td>
                    <td className="px-3 py-2 text-right">{s.confirmCount ?? '—'}</td>
                    <td className={`px-3 py-2 text-right ${(s.gateFlipCount ?? 0) >= 4 ? 'text-amber-400' : ''}`}>
                      {s.gateFlipCount ?? '—'}
                    </td>
                    <td className="px-3 py-2 text-right text-neutral-400">{fmtBytes(s.totalBytes)}</td>
                    <td className="px-3 py-2">
                      <div className="flex flex-wrap gap-1">
                        {flags.map((f) => (
                          <span
                            key={f}
                            className="rounded bg-red-950/60 px-1.5 py-0.5 text-[10px] text-red-300"
                          >
                            {f}
                          </span>
                        ))}
                        {flags.length === 0 && !s.eventLogR2Key && (
                          <span className="rounded bg-neutral-800 px-1.5 py-0.5 text-[10px] text-neutral-500">
                            no event log
                          </span>
                        )}
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
