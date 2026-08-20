'use client'

import { useEffect, useState, useCallback, useRef } from 'react'
import Link from 'next/link'
import { RouteDrawer } from '@/components/admin/RouteDrawer'
import { RouteGenerator } from '@/components/admin/RouteGenerator'
import { ExclusionZoneDrawer } from '@/components/admin/ExclusionZoneDrawer'
import { RouteEditModal, type RouteEditValues } from '@/components/admin/RouteEditModal'
import { AdminConfirmDialog } from '@/components/admin/AdminConfirmDialog'

type ClaimStatus = 'open' | 'closed' | 'scheduled'

interface RouteRow {
  id: number
  code: string
  displayName: string
  status: string
  claimStatus?: ClaimStatus | null
  distanceMeters: number | null
  estimatedDurationMinutes: number | null
  payoutAmount: number
  description: string | null
  blockId: number | null
  createdAt: string
}

// Effective worker-facing state from (status, claimStatus) — mirrors the
// state matrix in docs/plans/route-admin-plan-2026-08-20.md phase 1.
function effectiveState(route: RouteRow): { label: string; cls: string; hint: string } {
  if (route.status === 'draft')
    return { label: 'Draft', cls: 'bg-neutral-800 text-neutral-400', hint: 'Hidden from workers' }
  if (route.status === 'archived')
    return { label: 'Archived', cls: 'bg-yellow-900/40 text-yellow-400', hint: 'Hidden from workers' }
  // published — claimStatus defaults to 'open' (matches the migration backfill)
  const cs = route.claimStatus ?? 'open'
  if (cs === 'open')
    return { label: 'Live', cls: 'bg-green-900/40 text-green-400', hint: 'Claimable by workers' }
  if (cs === 'scheduled')
    return { label: 'Scheduled', cls: 'bg-blue-900/40 text-blue-400', hint: 'Opens for claims later' }
  return {
    label: 'Live · not claimable',
    cls: 'bg-amber-900/40 text-amber-400',
    hint: 'Visible, claims closed',
  }
}

type DeleteDialog =
  | { kind: 'confirm'; route: RouteRow }
  | { kind: 'blocked'; route: RouteRow; message: string; summary: { activeJobs?: number; pendingOffers?: number } }
  | { kind: 'result'; route: RouteRow; summary: Record<string, unknown> | null }

interface BlockRow {
  id: number
  code: string | null
  name: string
  center: { type: 'Point'; coordinates: [number, number] } | null
}

type View = 'list' | 'draw' | 'generate' | 'exclusion-zones'

export default function AdminRoutesPage() {
  const [routes, setRoutes] = useState<RouteRow[]>([])
  const [loading, setLoading] = useState(true)
  const [view, setView] = useState<View>('list')
  const [blocks, setBlocks] = useState<BlockRow[]>([])
  const [blocksLoading, setBlocksLoading] = useState(false)
  const [selectedBlockId, setSelectedBlockId] = useState<number | null>(null)
  const [busyRouteId, setBusyRouteId] = useState<number | null>(null)
  const [editingRoute, setEditingRoute] = useState<RouteRow | null>(null)
  const [savingEdit, setSavingEdit] = useState(false)
  const [deleteDialog, setDeleteDialog] = useState<DeleteDialog | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [toast, setToast] = useState<{ kind: 'ok' | 'warn' | 'error'; text: string } | null>(null)
  const toastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const showToast = useCallback((kind: 'ok' | 'warn' | 'error', text: string, ms = 6000) => {
    if (toastTimerRef.current) clearTimeout(toastTimerRef.current)
    setToast({ kind, text })
    toastTimerRef.current = setTimeout(() => setToast(null), ms)
  }, [])

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

  const fetchBlocks = useCallback(async () => {
    setBlocksLoading(true)
    try {
      const token = localStorage.getItem('adminToken')
      const res = await fetch('/api/admin/blocks', {
        headers: { Authorization: `Bearer ${token}` },
      })
      if (res.ok) {
        const json = await res.json()
        setBlocks(json.data || [])
      }
    } catch {
      // silent
    } finally {
      setBlocksLoading(false)
    }
  }, [])

  function handleCreated() {
    setView('list')
    fetchRoutes()
  }

  const putRoute = useCallback(async (routeId: number, body: Record<string, unknown>) => {
    const token = localStorage.getItem('adminToken')
    const res = await fetch(`/api/admin/routes/${routeId}`, {
      method: 'PUT',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    const json = await res.json().catch(() => ({}))
    if (!res.ok) throw new Error(json.error || `Update failed (${res.status})`)
    return json.data
  }, [])

  // New verb endpoints (phase 2 API): publish/unpublish/archive/claim-status.
  // Each returns the updated route; we refetch the list to stay consistent.
  const postRouteVerb = useCallback(
    async (routeId: number, verb: 'publish' | 'unpublish' | 'archive' | 'claim-status', body?: Record<string, unknown>) => {
      const token = localStorage.getItem('adminToken')
      const res = await fetch(`/api/admin/routes/${routeId}/${verb}`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify(body ?? {}),
      })
      const json = await res.json().catch(() => ({}))
      if (!res.ok) throw new Error(json.error || `${verb} failed (${res.status})`)
      return json.data
    },
    []
  )

  const handleVerb = useCallback(
    async (route: RouteRow, verb: 'publish' | 'unpublish' | 'archive') => {
      setBusyRouteId(route.id)
      try {
        await postRouteVerb(route.id, verb)
        if (verb === 'publish') {
          showToast('ok', `${route.code} published — live for workers.`)
        } else if (verb === 'unpublish') {
          showToast('ok', `${route.code} unpublished — back to draft.`)
        } else {
          showToast('ok', `${route.code} archived — hidden from workers.`)
        }
        fetchRoutes()
      } catch (e) {
        showToast('error', e instanceof Error ? e.message : `Failed to ${verb} route.`)
      } finally {
        setBusyRouteId(null)
      }
    },
    [postRouteVerb, fetchRoutes, showToast]
  )

  const handleClaimToggle = useCallback(
    async (route: RouteRow) => {
      const current = route.claimStatus ?? 'open'
      const next: ClaimStatus = current === 'open' ? 'closed' : 'open'
      setBusyRouteId(route.id)
      try {
        await postRouteVerb(route.id, 'claim-status', { claimStatus: next })
        showToast(
          'ok',
          next === 'open'
            ? `${route.code} claims opened — workers can claim it.`
            : `${route.code} claims closed — visible but not claimable.`
        )
        fetchRoutes()
      } catch (e) {
        showToast('error', e instanceof Error ? e.message : 'Failed to update claim status.')
      } finally {
        setBusyRouteId(null)
      }
    },
    [postRouteVerb, fetchRoutes, showToast]
  )

  const handleDeleteConfirm = useCallback(async () => {
    if (!deleteDialog || deleteDialog.kind !== 'confirm') return
    const route = deleteDialog.route
    setDeleting(true)
    try {
      const token = localStorage.getItem('adminToken')
      const res = await fetch(`/api/admin/routes/${route.id}`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${token}` },
      })
      const json = await res.json().catch(() => ({}))
      if (res.status === 409) {
        // Busy route — show what is blocking the delete.
        setDeleteDialog({
          kind: 'blocked',
          route,
          message: json.error || 'Route has active work and cannot be deleted.',
          summary: json.summary || {},
        })
        return
      }
      if (!res.ok) throw new Error(json.error || `Delete failed (${res.status})`)
      const summary =
        json.summary ?? json.data?.summary ?? (json.data && typeof json.data === 'object' ? json.data : null)
      setRoutes((rs) => rs.filter((r) => r.id !== route.id))
      setDeleteDialog({ kind: 'result', route, summary })
      fetchRoutes()
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to delete route.')
      setDeleteDialog(null)
    } finally {
      setDeleting(false)
    }
  }, [deleteDialog, fetchRoutes, showToast])

  const handleEditSave = useCallback(
    async (values: RouteEditValues) => {
      if (!editingRoute) return
      setSavingEdit(true)
      try {
        await putRoute(editingRoute.id, {
          displayName: values.displayName,
          payoutAmount: values.payoutDollars, // dollars — endpoint converts to cents
          description: values.description || null,
        })
        setRoutes((rs) =>
          rs.map((r) =>
            r.id === editingRoute.id
              ? {
                  ...r,
                  displayName: values.displayName,
                  payoutAmount: Math.round(values.payoutDollars * 100),
                  description: values.description || null,
                }
              : r
          )
        )
        setEditingRoute(null)
        showToast('ok', `${editingRoute.code} updated.`)
        fetchRoutes()
      } catch (e) {
        showToast('error', e instanceof Error ? e.message : 'Failed to update route.')
      } finally {
        setSavingEdit(false)
      }
    },
    [editingRoute, putRoute, fetchRoutes, showToast]
  )

  function handleExclusionZoneCreated() {
    // Stay on the drawer so the admin can keep drawing zones for this
    // block; just clear the in-progress polygon (handled inside the
    // drawer itself). Nothing to refetch here — routes/blocks lists are
    // unaffected by exclusion zones.
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

  if (view === 'exclusion-zones') {
    const selectedBlock = blocks.find((b) => b.id === selectedBlockId) ?? null

    return (
      <div className="p-6">
        <button
          onClick={() => {
            setView('list')
            setSelectedBlockId(null)
          }}
          className="mb-4 text-sm text-neutral-400 hover:text-white transition-colors"
        >
          &larr; Back to routes
        </button>

        {!selectedBlock ? (
          <div className="space-y-4 max-w-md">
            <h2 className="text-lg font-semibold text-white">Select a block</h2>
            <p className="text-xs text-neutral-500">
              Choose the block to draw a private-property exclusion zone for.
            </p>
            {blocksLoading ? (
              <div className="text-neutral-500 text-sm">Loading blocks…</div>
            ) : blocks.length === 0 ? (
              <div className="text-neutral-500 text-sm">No blocks found.</div>
            ) : (
              <select
                value=""
                onChange={(e) => setSelectedBlockId(Number(e.target.value))}
                className="w-full px-3 py-2 rounded-md bg-neutral-900 border border-neutral-700 text-white text-sm focus:outline-none focus:border-neutral-500"
              >
                <option value="" disabled>
                  Select a block…
                </option>
                {blocks.map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.code ? `${b.code} — ${b.name}` : b.name}
                  </option>
                ))}
              </select>
            )}
          </div>
        ) : (
          <>
            <div className="mb-4 flex items-center justify-between">
              <div className="text-sm text-neutral-400">
                Block: <span className="text-white font-medium">{selectedBlock.code ?? selectedBlock.name}</span>
              </div>
              <button
                onClick={() => setSelectedBlockId(null)}
                className="text-xs text-neutral-400 hover:text-white transition-colors"
              >
                Change block
              </button>
            </div>
            <ExclusionZoneDrawer
              blockId={selectedBlock.id}
              center={
                selectedBlock.center
                  ? {
                      longitude: selectedBlock.center.coordinates[0],
                      latitude: selectedBlock.center.coordinates[1],
                    }
                  : undefined
              }
              onCreated={handleExclusionZoneCreated}
            />
          </>
        )}
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
          <button
            onClick={() => {
              setView('exclusion-zones')
              fetchBlocks()
            }}
            className="px-4 py-2 rounded-md bg-red-600 text-white text-sm font-medium hover:bg-red-500 transition-colors"
          >
            Exclusion Zones
          </button>
        </div>
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
                <th className="px-4 py-3 font-medium">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-neutral-800">
              {routes.map((r) => {
                const busy = busyRouteId === r.id
                return (
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
                      {(() => {
                        const state = effectiveState(r)
                        return (
                          <>
                            <span
                              className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${state.cls}`}
                            >
                              {state.label}
                            </span>
                            <div className="text-[10px] text-neutral-600 mt-0.5">{state.hint}</div>
                          </>
                        )
                      })()}
                    </td>
                    <td className="px-4 py-3 text-neutral-500 text-xs">
                      {new Date(r.createdAt).toLocaleDateString()}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-1.5 flex-wrap">
                        {r.status !== 'archived' &&
                          (r.status === 'published' ? (
                            <button
                              onClick={() => handleVerb(r, 'unpublish')}
                              disabled={busy}
                              className="px-2 py-1 rounded text-xs font-medium bg-neutral-800 text-neutral-300 hover:bg-neutral-700 disabled:opacity-40 transition-colors"
                            >
                              Unpublish
                            </button>
                          ) : (
                            <button
                              onClick={() => handleVerb(r, 'publish')}
                              disabled={busy}
                              className="px-2 py-1 rounded text-xs font-medium bg-green-700 text-white hover:bg-green-600 disabled:opacity-40 transition-colors"
                            >
                              Publish
                            </button>
                          ))}
                        {r.status === 'published' && (
                          <button
                            onClick={() => handleClaimToggle(r)}
                            disabled={busy}
                            className={`px-2 py-1 rounded text-xs font-medium disabled:opacity-40 transition-colors ${
                              (r.claimStatus ?? 'open') === 'open'
                                ? 'bg-amber-800/60 text-amber-300 hover:bg-amber-800'
                                : 'bg-green-700 text-white hover:bg-green-600'
                            }`}
                          >
                            {(r.claimStatus ?? 'open') === 'open' ? 'Close Claims' : 'Open Claims'}
                          </button>
                        )}
                        {r.status === 'archived' ? (
                          <button
                            onClick={() => handleVerb(r, 'publish')}
                            disabled={busy}
                            className="px-2 py-1 rounded text-xs font-medium bg-green-700 text-white hover:bg-green-600 disabled:opacity-40 transition-colors"
                          >
                            Republish
                          </button>
                        ) : (
                          <button
                            onClick={() => handleVerb(r, 'archive')}
                            disabled={busy}
                            className="px-2 py-1 rounded text-xs font-medium bg-neutral-800 text-neutral-300 hover:bg-neutral-700 disabled:opacity-40 transition-colors"
                          >
                            Archive
                          </button>
                        )}
                        <button
                          onClick={() => setEditingRoute(r)}
                          disabled={busy}
                          className="px-2 py-1 rounded text-xs font-medium bg-neutral-800 text-neutral-300 hover:bg-neutral-700 disabled:opacity-40 transition-colors"
                        >
                          Edit
                        </button>
                        {r.blockId !== null ? (
                          <Link
                            href={`/admin/boundaries?blockId=${r.blockId}`}
                            className="px-2 py-1 rounded text-xs font-medium bg-neutral-800 text-neutral-300 hover:bg-neutral-700 transition-colors"
                          >
                            Boundaries
                          </Link>
                        ) : (
                          <span
                            title="No linked block"
                            className="px-2 py-1 rounded text-xs font-medium bg-neutral-900 text-neutral-600 cursor-not-allowed"
                          >
                            Boundaries
                          </span>
                        )}
                        <button
                          onClick={() => setDeleteDialog({ kind: 'confirm', route: r })}
                          disabled={busy}
                          className="px-2 py-1 rounded text-xs font-medium bg-red-900/50 text-red-400 hover:bg-red-900 disabled:opacity-40 transition-colors"
                        >
                          Delete
                        </button>
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      {editingRoute && (
        <RouteEditModal
          routeCode={editingRoute.code}
          initial={{
            displayName: editingRoute.displayName,
            payoutDollars: Math.round(editingRoute.payoutAmount) / 100,
            description: editingRoute.description || '',
          }}
          saving={savingEdit}
          onSave={handleEditSave}
          onClose={() => setEditingRoute(null)}
        />
      )}

      {deleteDialog?.kind === 'confirm' && (
        <AdminConfirmDialog
          title={
            <>
              Delete route <span className="font-mono text-red-400">{deleteDialog.route.code}</span>?
            </>
          }
          body={
            <p>
              This removes the route, its linked block, and exclusion zones, and cancels any open
              release slots and offers. This cannot be undone.
            </p>
          }
          confirmLabel="Delete Route"
          confirmTone="danger"
          busy={deleting}
          onConfirm={handleDeleteConfirm}
          onClose={() => setDeleteDialog(null)}
        />
      )}

      {deleteDialog?.kind === 'blocked' && (
        <AdminConfirmDialog
          title={
            <>
              Cannot delete <span className="font-mono text-red-400">{deleteDialog.route.code}</span>
            </>
          }
          body={
            <>
              <p>{deleteDialog.message}</p>
              <div className="rounded-md border border-neutral-800 bg-neutral-900 px-3 py-2 text-xs space-y-1">
                <div className="flex justify-between">
                  <span className="text-neutral-400">Active jobs</span>
                  <span className="text-white font-medium">{deleteDialog.summary.activeJobs ?? 0}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-neutral-400">Pending offers</span>
                  <span className="text-white font-medium">{deleteDialog.summary.pendingOffers ?? 0}</span>
                </div>
              </div>
              <p className="text-xs text-neutral-500">
                Resolve the active jobs / pending offers (or archive the route) before deleting.
              </p>
            </>
          }
          onClose={() => setDeleteDialog(null)}
        />
      )}

      {deleteDialog?.kind === 'result' && (
        <AdminConfirmDialog
          title={
            <>
              Deleted <span className="font-mono text-green-400">{deleteDialog.route.code}</span>
            </>
          }
          body={
            deleteDialog.summary && Object.keys(deleteDialog.summary).length > 0 ? (
              <>
                <p>The route was deleted. The server cancelled the following:</p>
                <div className="rounded-md border border-neutral-800 bg-neutral-900 px-3 py-2 text-xs space-y-1">
                  {Object.entries(deleteDialog.summary).map(([key, value]) => (
                    <div key={key} className="flex justify-between">
                      <span className="text-neutral-400">{key}</span>
                      <span className="text-white font-medium">{String(value)}</span>
                    </div>
                  ))}
                </div>
              </>
            ) : (
              <p>The route was deleted.</p>
            )
          }
          onClose={() => setDeleteDialog(null)}
        />
      )}
    </div>
  )
}
