'use client'

/**
 * Release batch detail — edit batch fields, reorder the route queue, inspect
 * offers, cancel the batch.
 *
 * API (see docs/plans/route-admin-plan-2026-08-20.md, phase 2/3):
 *   GET   /api/admin/route-releases/[releaseId]        → { data: ReleaseDetail }
 *   PATCH /api/admin/route-releases/[releaseId]        name/releaseAt/offerWindowMinutes/
 *                                                      cleanupStartsAt/cleanupEndsAt/targetSlots,
 *                                                      or { action: 'cancel' }
 *   PUT   /api/admin/route-releases/[releaseId]/order  { routeIds: number[] } → reordered list
 */

import { use, useCallback, useEffect, useRef, useState } from 'react'
import Link from 'next/link'
import { AdminConfirmDialog } from '@/components/admin/AdminConfirmDialog'
import {
  batchStatusBadge,
  formatDateTime,
  isoToLocalInput,
  localInputToIso,
  type ReleaseDetail,
  type ReleaseRouteRow,
} from '../release-utils'

const inputCls =
  'w-full px-3 py-2 rounded-md bg-neutral-900 border border-neutral-700 text-white text-sm focus:outline-none focus:border-neutral-500'
const labelCls = 'block space-y-1'
const spanCls = 'text-xs text-neutral-400'

function sortByPosition(rows: ReleaseRouteRow[]): ReleaseRouteRow[] {
  return [...rows].sort((a, b) => (a.position ?? 0) - (b.position ?? 0))
}

export default function AdminRouteReleaseDetailPage({
  params,
}: {
  params: Promise<{ releaseId: string }>
}) {
  const { releaseId } = use(params)
  const [detail, setDetail] = useState<ReleaseDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [toast, setToast] = useState<{ kind: 'ok' | 'warn' | 'error'; text: string } | null>(null)
  const toastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // edit-form state
  const [editName, setEditName] = useState('')
  const [editReleaseAt, setEditReleaseAt] = useState('')
  const [editOfferWindow, setEditOfferWindow] = useState('')
  const [editCleanupStart, setEditCleanupStart] = useState('')
  const [editCleanupEnd, setEditCleanupEnd] = useState('')
  const [editTargetSlots, setEditTargetSlots] = useState('')
  const [saving, setSaving] = useState(false)

  const [orderSaving, setOrderSaving] = useState(false)
  const [showCancelDialog, setShowCancelDialog] = useState(false)
  const [cancelling, setCancelling] = useState(false)

  const showToast = useCallback((kind: 'ok' | 'warn' | 'error', text: string, ms = 6000) => {
    if (toastTimerRef.current) clearTimeout(toastTimerRef.current)
    setToast({ kind, text })
    toastTimerRef.current = setTimeout(() => setToast(null), ms)
  }, [])

  const hydrateForm = useCallback((d: ReleaseDetail) => {
    setEditName(d.name ?? '')
    setEditReleaseAt(isoToLocalInput(d.releaseAt))
    setEditOfferWindow(String(d.offerWindowMinutes ?? 240))
    setEditCleanupStart(isoToLocalInput(d.cleanupStartsAt))
    setEditCleanupEnd(isoToLocalInput(d.cleanupEndsAt))
    setEditTargetSlots(d.targetSlots != null ? String(d.targetSlots) : '')
  }, [])

  const fetchDetail = useCallback(async () => {
    setLoading(true)
    setLoadError('')
    try {
      const token = localStorage.getItem('adminToken')
      const res = await fetch(`/api/admin/route-releases/${releaseId}`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      const json = await res.json().catch(() => ({}))
      if (!res.ok) throw new Error(json.error || `Failed to load batch (${res.status})`)
      const d: ReleaseDetail = json.data ?? json
      d.routes = sortByPosition(d.routes || [])
      d.offers = d.offers || []
      setDetail(d)
      hydrateForm(d)
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : 'Failed to load batch.')
    } finally {
      setLoading(false)
    }
  }, [releaseId, hydrateForm])

  useEffect(() => {
    fetchDetail()
  }, [fetchDetail])

  async function handleSave() {
    if (!detail) return
    const offerWindowMinutes = Number(editOfferWindow)
    if (!editName.trim()) return showToast('error', 'Name is required.')
    if (!Number.isFinite(offerWindowMinutes) || offerWindowMinutes < 15 || offerWindowMinutes > 1440)
      return showToast('error', 'Offer window must be 15–1440 minutes.')
    const targetSlots = editTargetSlots.trim() ? Number(editTargetSlots) : null
    if (targetSlots !== null && (!Number.isFinite(targetSlots) || targetSlots <= 0))
      return showToast('error', 'Target slots must be a positive number.')

    setSaving(true)
    try {
      const token = localStorage.getItem('adminToken')
      const res = await fetch(`/api/admin/route-releases/${releaseId}`, {
        method: 'PATCH',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: editName.trim(),
          releaseAt: localInputToIso(editReleaseAt),
          offerWindowMinutes,
          cleanupStartsAt: localInputToIso(editCleanupStart),
          cleanupEndsAt: localInputToIso(editCleanupEnd),
          targetSlots,
        }),
      })
      const json = await res.json().catch(() => ({}))
      if (!res.ok) throw new Error(json.error || `Save failed (${res.status})`)
      showToast('ok', 'Batch updated.')
      fetchDetail()
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to save batch.')
    } finally {
      setSaving(false)
    }
  }

  async function moveRoute(index: number, direction: -1 | 1) {
    if (!detail || orderSaving) return
    const rows = sortByPosition(detail.routes)
    const target = index + direction
    if (target < 0 || target >= rows.length) return
    const reordered = [...rows]
    const [moved] = reordered.splice(index, 1)
    reordered.splice(target, 0, moved)

    // Optimistic update (positions become 1..N for display)
    const optimistic = reordered.map((r, i) => ({ ...r, position: i + 1 }))
    setDetail({ ...detail, routes: optimistic })
    setOrderSaving(true)
    try {
      const token = localStorage.getItem('adminToken')
      const res = await fetch(`/api/admin/route-releases/${releaseId}/order`, {
        method: 'PUT',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ routeIds: reordered.map((r) => r.routeId) }),
      })
      const json = await res.json().catch(() => ({}))
      if (!res.ok) throw new Error(json.error || `Reorder failed (${res.status})`)
      showToast('ok', 'Queue order saved.')
      fetchDetail()
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to save order.')
      fetchDetail() // roll back to server state
    } finally {
      setOrderSaving(false)
    }
  }

  async function handleCancelBatch() {
    if (!detail) return
    setCancelling(true)
    try {
      const token = localStorage.getItem('adminToken')
      const res = await fetch(`/api/admin/route-releases/${releaseId}`, {
        method: 'PATCH',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ action: 'cancel' }),
      })
      const json = await res.json().catch(() => ({}))
      if (!res.ok) throw new Error(json.error || `Cancel failed (${res.status})`)
      setShowCancelDialog(false)
      showToast('ok', `Batch "${detail.name}" cancelled.`)
      fetchDetail()
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to cancel batch.')
    } finally {
      setCancelling(false)
    }
  }

  if (loading) {
    return <div className="p-6 text-neutral-500 text-sm">Loading batch…</div>
  }
  if (loadError || !detail) {
    return (
      <div className="p-6 space-y-4">
        <Link
          href="/admin/route-releases"
          className="text-sm text-neutral-400 hover:text-white transition-colors"
        >
          &larr; Back to releases
        </Link>
        <div className="text-red-400 text-sm">{loadError || 'Batch not found.'}</div>
      </div>
    )
  }

  const badge = batchStatusBadge(detail.status)
  const cancellable =
    detail.status === 'draft' || detail.status === 'scheduled' || detail.status === 'active'

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <Link
            href="/admin/route-releases"
            className="text-sm text-neutral-400 hover:text-white transition-colors"
          >
            &larr; Back to releases
          </Link>
          <h1 className="text-xl font-semibold text-white mt-1">
            {detail.name}{' '}
            <span className={`inline-block px-2 py-0.5 rounded text-xs font-medium align-middle ${badge.cls}`}>
              {badge.label}
            </span>
          </h1>
          <div className="text-xs text-neutral-500 mt-1">
            {detail.communityName || '—'} · wave {detail.waveNumber} · window {detail.offerWindowMinutes} min
            {detail.nextWaveAt ? ` · next wave ${formatDateTime(detail.nextWaveAt)}` : ''}
          </div>
        </div>
        {cancellable && (
          <button
            onClick={() => setShowCancelDialog(true)}
            className="px-4 py-2 rounded-md bg-red-700 text-white text-sm font-medium hover:bg-red-600 transition-colors"
          >
            Cancel Batch
          </button>
        )}
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

      {/* Batch fields */}
      <div className="border border-neutral-800 rounded-lg p-5 space-y-4">
        <h2 className="text-base font-semibold text-white">Batch settings</h2>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <label className={labelCls}>
            <span className={spanCls}>Name</span>
            <input value={editName} onChange={(e) => setEditName(e.target.value)} className={inputCls} />
          </label>
          <label className={labelCls}>
            <span className={spanCls}>Release at</span>
            <input
              type="datetime-local"
              value={editReleaseAt}
              onChange={(e) => setEditReleaseAt(e.target.value)}
              className={inputCls}
            />
          </label>
          <label className={labelCls}>
            <span className={spanCls}>Offer window (minutes)</span>
            <input
              type="number"
              min={15}
              max={1440}
              value={editOfferWindow}
              onChange={(e) => setEditOfferWindow(e.target.value)}
              className={inputCls}
            />
          </label>
          <label className={labelCls}>
            <span className={spanCls}>Cleanup starts</span>
            <input
              type="datetime-local"
              value={editCleanupStart}
              onChange={(e) => setEditCleanupStart(e.target.value)}
              className={inputCls}
            />
          </label>
          <label className={labelCls}>
            <span className={spanCls}>Cleanup ends</span>
            <input
              type="datetime-local"
              value={editCleanupEnd}
              onChange={(e) => setEditCleanupEnd(e.target.value)}
              className={inputCls}
            />
          </label>
          <label className={labelCls}>
            <span className={spanCls}>Target slots</span>
            <input
              type="number"
              min={1}
              value={editTargetSlots}
              onChange={(e) => setEditTargetSlots(e.target.value)}
              className={inputCls}
            />
          </label>
        </div>
        <div>
          <button
            onClick={handleSave}
            disabled={saving}
            className="px-4 py-2 rounded-md bg-green-600 text-white text-sm font-medium hover:bg-green-500 disabled:opacity-50 transition-colors"
          >
            {saving ? 'Saving…' : 'Save Changes'}
          </button>
        </div>
      </div>

      {/* Route queue */}
      <div className="space-y-2">
        <h2 className="text-base font-semibold text-white">
          Route queue <span className="text-neutral-500 text-sm font-normal">({detail.routes.length} slots)</span>
        </h2>
        {detail.routes.length === 0 ? (
          <div className="text-neutral-500 text-sm">No routes in this batch.</div>
        ) : (
          <div className="border border-neutral-800 rounded-lg overflow-hidden">
            <table className="w-full text-sm">
              <thead>
                <tr className="bg-neutral-900 text-neutral-400 text-left">
                  <th className="px-4 py-3 font-medium">#</th>
                  <th className="px-4 py-3 font-medium">Route</th>
                  <th className="px-4 py-3 font-medium">Job</th>
                  <th className="px-4 py-3 font-medium">Status</th>
                  <th className="px-4 py-3 font-medium">Order</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-neutral-800">
                {detail.routes.map((r, i) => (
                  <tr key={r.id} className="hover:bg-neutral-900/50 transition-colors">
                    <td className="px-4 py-3 text-neutral-500">{r.position ?? i + 1}</td>
                    <td className="px-4 py-3 text-neutral-200">
                      {r.code ? (
                        <>
                          <span className="font-mono text-green-400 text-xs mr-2">{r.code}</span>
                          {r.displayName || ''}
                        </>
                      ) : (
                        `Route #${r.routeId}`
                      )}
                    </td>
                    <td className="px-4 py-3 text-neutral-400">#{r.jobId}</td>
                    <td className="px-4 py-3">
                      <span
                        className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${
                          r.status === 'open'
                            ? 'bg-green-900/40 text-green-400'
                            : r.status === 'claimed'
                              ? 'bg-blue-900/40 text-blue-400'
                              : 'bg-neutral-800 text-neutral-400'
                        }`}
                      >
                        {r.status}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-1.5">
                        <button
                          onClick={() => moveRoute(i, -1)}
                          disabled={orderSaving || i === 0}
                          className="px-2 py-1 rounded text-xs font-medium bg-neutral-800 text-neutral-300 hover:bg-neutral-700 disabled:opacity-40 transition-colors"
                        >
                          ↑
                        </button>
                        <button
                          onClick={() => moveRoute(i, 1)}
                          disabled={orderSaving || i === detail.routes.length - 1}
                          className="px-2 py-1 rounded text-xs font-medium bg-neutral-800 text-neutral-300 hover:bg-neutral-700 disabled:opacity-40 transition-colors"
                        >
                          ↓
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Offers */}
      <div className="space-y-2">
        <h2 className="text-base font-semibold text-white">
          Offers <span className="text-neutral-500 text-sm font-normal">({detail.offers.length})</span>
        </h2>
        {detail.offers.length === 0 ? (
          <div className="text-neutral-500 text-sm">No offers sent yet.</div>
        ) : (
          <div className="border border-neutral-800 rounded-lg overflow-hidden">
            <table className="w-full text-sm">
              <thead>
                <tr className="bg-neutral-900 text-neutral-400 text-left">
                  <th className="px-4 py-3 font-medium">Worker</th>
                  <th className="px-4 py-3 font-medium">Wave</th>
                  <th className="px-4 py-3 font-medium">Status</th>
                  <th className="px-4 py-3 font-medium">Offered</th>
                  <th className="px-4 py-3 font-medium">Expires</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-neutral-800">
                {detail.offers.map((o) => (
                  <tr key={o.id} className="hover:bg-neutral-900/50 transition-colors">
                    <td className="px-4 py-3 text-neutral-200">
                      {o.workerName || `Worker #${o.workerId}`}
                    </td>
                    <td className="px-4 py-3 text-neutral-400">{o.waveNumber}</td>
                    <td className="px-4 py-3">
                      <span
                        className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${
                          o.status === 'pending'
                            ? 'bg-amber-900/40 text-amber-400'
                            : o.status === 'accepted'
                              ? 'bg-green-900/40 text-green-400'
                              : o.status === 'declined'
                                ? 'bg-red-900/40 text-red-400'
                                : 'bg-neutral-800 text-neutral-400'
                        }`}
                      >
                        {o.status}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-neutral-400 text-xs">{formatDateTime(o.offeredAt)}</td>
                    <td className="px-4 py-3 text-neutral-400 text-xs">{formatDateTime(o.expiresAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {showCancelDialog && (
        <AdminConfirmDialog
          title={<>Cancel batch <span className="font-mono text-red-400">{detail.name}</span>?</>}
          body={
            <p>
              This cancels all open route slots and pending offers in the batch. Claimed jobs are
              unaffected. This cannot be undone.
            </p>
          }
          confirmLabel="Cancel Batch"
          confirmTone="danger"
          requireTypedText={detail.name}
          busy={cancelling}
          onConfirm={handleCancelBatch}
          onClose={() => setShowCancelDialog(false)}
        />
      )}
    </div>
  )
}
