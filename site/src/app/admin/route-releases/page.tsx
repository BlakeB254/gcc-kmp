'use client'

/**
 * Route release batches — list + create + cancel.
 *
 * API (see docs/plans/route-admin-plan-2026-08-20.md, phase 2/3):
 *   GET   /api/admin/route-releases            → { data: Batch[] }
 *   POST  /api/admin/route-releases            → { data: Batch } (201)
 *   PATCH /api/admin/route-releases/[id]       { action: 'cancel' } → cancelled batch
 *
 * Detail/editing/reorder lives at /admin/route-releases/[releaseId].
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import Link from 'next/link'
import {
  batchStatusBadge,
  formatDateTime,
  localInputToIso,
  type ReleaseBatch,
} from './release-utils'

interface RouteOption {
  id: number
  code: string
  displayName: string
  status: string
}

export default function AdminRouteReleasesPage() {
  const [batches, setBatches] = useState<ReleaseBatch[]>([])
  const [loading, setLoading] = useState(true)
  const [showCreate, setShowCreate] = useState(false)
  const [busyId, setBusyId] = useState<number | null>(null)
  const [toast, setToast] = useState<{ kind: 'ok' | 'warn' | 'error'; text: string } | null>(null)
  const toastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // create-form state
  const [routes, setRoutes] = useState<RouteOption[]>([])
  const [formName, setFormName] = useState('')
  const [formServiceAreaId, setFormServiceAreaId] = useState('1')
  const [formCommunityName, setFormCommunityName] = useState('')
  const [formZips, setFormZips] = useState('')
  const [formReleaseAt, setFormReleaseAt] = useState('')
  const [formOfferWindow, setFormOfferWindow] = useState('240')
  const [formCleanupStart, setFormCleanupStart] = useState('')
  const [formCleanupEnd, setFormCleanupEnd] = useState('')
  const [formTargetSlots, setFormTargetSlots] = useState('')
  const [formRouteIds, setFormRouteIds] = useState<Set<number>>(new Set())
  const [creating, setCreating] = useState(false)
  const [formError, setFormError] = useState('')

  const showToast = useCallback((kind: 'ok' | 'warn' | 'error', text: string, ms = 6000) => {
    if (toastTimerRef.current) clearTimeout(toastTimerRef.current)
    setToast({ kind, text })
    toastTimerRef.current = setTimeout(() => setToast(null), ms)
  }, [])

  const fetchBatches = useCallback(async () => {
    setLoading(true)
    try {
      const token = localStorage.getItem('adminToken')
      const res = await fetch('/api/admin/route-releases', {
        headers: { Authorization: `Bearer ${token}` },
      })
      if (res.ok) {
        const json = await res.json()
        setBatches(json.data || [])
      }
    } catch {
      // silent
    } finally {
      setLoading(false)
    }
  }, [])

  const fetchRoutes = useCallback(async () => {
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
    }
  }, [])

  useEffect(() => {
    fetchBatches()
  }, [fetchBatches])

  function toggleCreate() {
    const next = !showCreate
    setShowCreate(next)
    if (next && routes.length === 0) fetchRoutes()
  }

  function toggleRouteId(id: number) {
    setFormRouteIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  async function handleCreate() {
    setFormError('')
    const name = formName.trim()
    const serviceAreaId = Number(formServiceAreaId)
    const releaseAt = localInputToIso(formReleaseAt)
    const offerWindowMinutes = Number(formOfferWindow)
    const routeIds = Array.from(formRouteIds)
    const eligibleZipCodes = formZips
      .split(',')
      .map((z) => z.trim())
      .filter(Boolean)
    const targetSlots = formTargetSlots.trim() ? Number(formTargetSlots) : undefined

    if (!name) return setFormError('Name is required.')
    if (!Number.isFinite(serviceAreaId) || serviceAreaId <= 0)
      return setFormError('Service area ID must be a positive number.')
    if (!releaseAt) return setFormError('Release time is required.')
    if (!Number.isFinite(offerWindowMinutes) || offerWindowMinutes < 15 || offerWindowMinutes > 1440)
      return setFormError('Offer window must be 15–1440 minutes.')
    if (routeIds.length === 0) return setFormError('Select at least one route.')
    if (targetSlots !== undefined && (!Number.isFinite(targetSlots) || targetSlots <= 0))
      return setFormError('Target slots must be a positive number.')

    setCreating(true)
    try {
      const token = localStorage.getItem('adminToken')
      const res = await fetch('/api/admin/route-releases', {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name,
          serviceAreaId,
          routeIds,
          releaseAt,
          offerWindowMinutes,
          communityName: formCommunityName.trim() || undefined,
          eligibleZipCodes: eligibleZipCodes.length ? eligibleZipCodes : undefined,
          cleanupStartsAt: localInputToIso(formCleanupStart) ?? undefined,
          cleanupEndsAt: localInputToIso(formCleanupEnd) ?? undefined,
          targetSlots,
        }),
      })
      const json = await res.json().catch(() => ({}))
      if (!res.ok) throw new Error(json.error || `Create failed (${res.status})`)
      setShowCreate(false)
      setFormName('')
      setFormCommunityName('')
      setFormZips('')
      setFormReleaseAt('')
      setFormCleanupStart('')
      setFormCleanupEnd('')
      setFormTargetSlots('')
      setFormRouteIds(new Set())
      showToast('ok', `Batch "${name}" created.`)
      fetchBatches()
    } catch (e) {
      setFormError(e instanceof Error ? e.message : 'Failed to create batch.')
    } finally {
      setCreating(false)
    }
  }

  async function handleCancel(batch: ReleaseBatch) {
    const ok = window.confirm(
      `Cancel batch "${batch.name}"? Open slots and pending offers will be cancelled.`
    )
    if (!ok) return
    setBusyId(batch.id)
    try {
      const token = localStorage.getItem('adminToken')
      const res = await fetch(`/api/admin/route-releases/${batch.id}`, {
        method: 'PATCH',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ action: 'cancel' }),
      })
      const json = await res.json().catch(() => ({}))
      if (!res.ok) throw new Error(json.error || `Cancel failed (${res.status})`)
      showToast('ok', `Batch "${batch.name}" cancelled.`)
      fetchBatches()
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to cancel batch.')
    } finally {
      setBusyId(null)
    }
  }

  const cancellable = (b: ReleaseBatch) =>
    b.status === 'draft' || b.status === 'scheduled' || b.status === 'active'

  const inputCls =
    'w-full px-3 py-2 rounded-md bg-neutral-900 border border-neutral-700 text-white text-sm focus:outline-none focus:border-neutral-500'
  const labelCls = 'block space-y-1'
  const spanCls = 'text-xs text-neutral-400'

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold text-white">Route Releases</h1>
        <button
          onClick={toggleCreate}
          className="px-4 py-2 rounded-md bg-green-600 text-white text-sm font-medium hover:bg-green-500 transition-colors"
        >
          {showCreate ? 'Close Form' : 'New Batch'}
        </button>
      </div>

      {toast && (
        <div
          className={`text-xs rounded-md px-3 py-2 border ${
            toast.kind === 'ok'
              ? 'text-green-400 bg-green-950/40 border-green-900/50'
              : toast.kind === 'warn'
                ? 'text-amber-400 bg-amber-950/40 border-amber-900/50'
                : 'text-red-400 bg-red-950/40 border-red-900/50'
          }`}
        >
          {toast.text}
        </div>
      )}

      {showCreate && (
        <div className="border border-neutral-800 rounded-lg p-5 space-y-4">
          <h2 className="text-base font-semibold text-white">Create release batch</h2>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <label className={labelCls}>
              <span className={spanCls}>Name *</span>
              <input
                value={formName}
                onChange={(e) => setFormName(e.target.value)}
                placeholder="North Lawndale — Week 34"
                className={inputCls}
              />
            </label>
            <label className={labelCls}>
              <span className={spanCls}>Service area ID *</span>
              <input
                type="number"
                min={1}
                value={formServiceAreaId}
                onChange={(e) => setFormServiceAreaId(e.target.value)}
                className={inputCls}
              />
            </label>
            <label className={labelCls}>
              <span className={spanCls}>Community name</span>
              <input
                value={formCommunityName}
                onChange={(e) => setFormCommunityName(e.target.value)}
                placeholder="Defaults to the service area name"
                className={inputCls}
              />
            </label>
            <label className={labelCls}>
              <span className={spanCls}>Eligible ZIP codes (comma-separated)</span>
              <input
                value={formZips}
                onChange={(e) => setFormZips(e.target.value)}
                placeholder="60623, 60624"
                className={inputCls}
              />
            </label>
            <label className={labelCls}>
              <span className={spanCls}>Release at *</span>
              <input
                type="datetime-local"
                value={formReleaseAt}
                onChange={(e) => setFormReleaseAt(e.target.value)}
                className={inputCls}
              />
            </label>
            <label className={labelCls}>
              <span className={spanCls}>Offer window (minutes)</span>
              <input
                type="number"
                min={15}
                max={1440}
                value={formOfferWindow}
                onChange={(e) => setFormOfferWindow(e.target.value)}
                className={inputCls}
              />
            </label>
            <label className={labelCls}>
              <span className={spanCls}>Cleanup starts</span>
              <input
                type="datetime-local"
                value={formCleanupStart}
                onChange={(e) => setFormCleanupStart(e.target.value)}
                className={inputCls}
              />
            </label>
            <label className={labelCls}>
              <span className={spanCls}>Cleanup ends</span>
              <input
                type="datetime-local"
                value={formCleanupEnd}
                onChange={(e) => setFormCleanupEnd(e.target.value)}
                className={inputCls}
              />
            </label>
            <label className={labelCls}>
              <span className={spanCls}>Target slots</span>
              <input
                type="number"
                min={1}
                value={formTargetSlots}
                onChange={(e) => setFormTargetSlots(e.target.value)}
                placeholder="Defaults to route count"
                className={inputCls}
              />
            </label>
          </div>

          <div className="space-y-2">
            <div className="text-xs text-neutral-400">
              Routes in this batch * ({formRouteIds.size} selected)
            </div>
            {routes.length === 0 ? (
              <div className="text-neutral-500 text-sm">Loading routes…</div>
            ) : (
              <div className="max-h-48 overflow-auto border border-neutral-800 rounded-md divide-y divide-neutral-800">
                {routes
                  .filter((r) => r.status !== 'archived')
                  .map((r) => (
                    <label
                      key={r.id}
                      className="flex items-center gap-3 px-3 py-2 hover:bg-neutral-900/50 cursor-pointer text-sm"
                    >
                      <input
                        type="checkbox"
                        checked={formRouteIds.has(r.id)}
                        onChange={() => toggleRouteId(r.id)}
                        className="accent-green-600"
                      />
                      <span className="font-mono text-green-400 text-xs">{r.code}</span>
                      <span className="text-neutral-200">{r.displayName}</span>
                      <span className="text-neutral-600 text-xs ml-auto">{r.status}</span>
                    </label>
                  ))}
              </div>
            )}
          </div>

          {formError && <div className="text-xs text-red-400">{formError}</div>}

          <div className="flex gap-2">
            <button
              onClick={handleCreate}
              disabled={creating}
              className="px-4 py-2 rounded-md bg-green-600 text-white text-sm font-medium hover:bg-green-500 disabled:opacity-50 transition-colors"
            >
              {creating ? 'Creating…' : 'Create Batch'}
            </button>
            <button
              onClick={toggleCreate}
              disabled={creating}
              className="px-4 py-2 rounded-md bg-neutral-800 text-neutral-300 text-sm hover:bg-neutral-700 disabled:opacity-40 transition-colors"
            >
              Cancel
            </button>
          </div>
        </div>
      )}

      {loading ? (
        <div className="text-neutral-500 text-sm">Loading batches…</div>
      ) : batches.length === 0 ? (
        <div className="text-neutral-500 text-sm">No release batches yet.</div>
      ) : (
        <div className="border border-neutral-800 rounded-lg overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-neutral-900 text-neutral-400 text-left">
                <th className="px-4 py-3 font-medium">Name</th>
                <th className="px-4 py-3 font-medium">Community</th>
                <th className="px-4 py-3 font-medium">Status</th>
                <th className="px-4 py-3 font-medium">Release At</th>
                <th className="px-4 py-3 font-medium">Wave</th>
                <th className="px-4 py-3 font-medium">Target Slots</th>
                <th className="px-4 py-3 font-medium">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-neutral-800">
              {batches.map((b) => {
                const badge = batchStatusBadge(b.status)
                const busy = busyId === b.id
                return (
                  <tr key={b.id} className="hover:bg-neutral-900/50 transition-colors">
                    <td className="px-4 py-3 text-neutral-200">{b.name}</td>
                    <td className="px-4 py-3 text-neutral-400">{b.communityName || '—'}</td>
                    <td className="px-4 py-3">
                      <span
                        className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${badge.cls}`}
                      >
                        {badge.label}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-neutral-400 text-xs">{formatDateTime(b.releaseAt)}</td>
                    <td className="px-4 py-3 text-neutral-400">
                      {b.waveNumber}
                      {b.nextWaveAt && (b.status === 'active' || b.status === 'scheduled') && (
                        <div className="text-[10px] text-neutral-600 mt-0.5">
                          next {formatDateTime(b.nextWaveAt)}
                        </div>
                      )}
                    </td>
                    <td className="px-4 py-3 text-neutral-400">{b.targetSlots ?? '—'}</td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-1.5 flex-wrap">
                        <Link
                          href={`/admin/route-releases/${b.id}`}
                          className="px-2 py-1 rounded text-xs font-medium bg-neutral-800 text-neutral-300 hover:bg-neutral-700 transition-colors"
                        >
                          View
                        </Link>
                        {cancellable(b) && (
                          <button
                            onClick={() => handleCancel(b)}
                            disabled={busy}
                            className="px-2 py-1 rounded text-xs font-medium bg-red-900/50 text-red-400 hover:bg-red-900 disabled:opacity-40 transition-colors"
                          >
                            Cancel
                          </button>
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
