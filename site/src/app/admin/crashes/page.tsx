'use client'
import { useCallback, useEffect, useMemo, useState } from 'react'

interface CrashReport {
  id: number
  deviceId: string
  platform: string
  appVersion: string | null
  exceptionMessage: string
  stackTrace: string
  occurredAt: string
  createdAt: string
}

export default function AdminCrashesPage() {
  const [crashes, setCrashes] = useState<CrashReport[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [expandedId, setExpandedId] = useState<number | null>(null)

  const authHeaders = useMemo((): Record<string, string> => {
    if (typeof window === 'undefined') return {}
    const t = localStorage.getItem('adminToken')
    return t ? { Authorization: `Bearer ${t}` } : {}
  }, [])

  const loadCrashes = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await fetch('/api/admin/crashes', { headers: authHeaders })
      if (!res.ok) throw new Error(`Crashes: ${res.status}`)
      const body = await res.json()
      setCrashes(body.data || [])
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load')
    } finally {
      setLoading(false)
    }
  }, [authHeaders])

  useEffect(() => {
    loadCrashes()
  }, [loadCrashes])

  return (
    <div className="p-6 text-white">
      <h1 className="mb-4 text-xl font-semibold">Crash Reports</h1>
      {loading && <p className="text-neutral-400">Loading...</p>}
      {error && (
        <div className="mb-4 rounded-lg bg-red-950/40 px-4 py-2 text-sm text-red-300">
          {error}
        </div>
      )}
      {!loading && !error && crashes.length === 0 && (
        <p className="text-neutral-400">No crash reports recorded.</p>
      )}
      <div className="rounded-lg border border-neutral-800 bg-neutral-900">
        {crashes.map((c) => (
          <div
            key={c.id}
            className="border-b border-neutral-800 px-4 py-3 text-xs last:border-b-0"
          >
            <div
              className="flex cursor-pointer items-center justify-between"
              onClick={() => setExpandedId(expandedId === c.id ? null : c.id)}
            >
              <span className="font-mono text-neutral-400">{c.platform}</span>
              <span className="flex-1 px-3 truncate">{c.exceptionMessage}</span>
              <span className="text-neutral-500">{c.occurredAt}</span>
            </div>
            {expandedId === c.id && (
              <pre className="mt-2 overflow-x-auto whitespace-pre-wrap text-neutral-300">
                {c.stackTrace}
              </pre>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}
