'use client'

import { useCallback, useEffect, useState } from 'react'
import { BoundaryEditor, type EditorBlock } from '@/components/admin/BoundaryEditor'

/**
 * Admin boundary editor page (route-v2). Full editing of each block's
 * external boundary (locationBoundary) and internal exclusion islands
 * (innerBoundary) — see BoundaryEditor for the interaction model.
 */
export default function AdminBoundariesPage() {
  const [blocks, setBlocks] = useState<EditorBlock[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')

  const fetchBlocks = useCallback(async () => {
    try {
      const token = localStorage.getItem('adminToken')
      const res = await fetch('/api/admin/blocks', {
        headers: { Authorization: `Bearer ${token}` },
      })
      if (!res.ok) {
        setLoadError('Failed to load blocks')
        return
      }
      const json = await res.json()
      setBlocks(
        (json.data || []).map((b: any) => ({
          id: b.id,
          code: b.code,
          name: b.name,
          routeId: b.routeId ?? null,
          boundary: b.boundary ?? null,
          innerBoundary: b.innerBoundary ?? null,
        }))
      )
      setLoadError('')
    } catch {
      setLoadError('Network error while loading blocks')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchBlocks()
  }, [fetchBlocks])

  return (
    <div className="p-6 space-y-4">
      <div>
        <h1 className="text-xl font-semibold text-white">Boundary Editor</h1>
        <p className="text-xs text-neutral-500 mt-1 max-w-2xl">
          Edit each block&apos;s external boundary and its internal exclusion islands. External boundaries tile the
          neighborhood — expanding into a neighbor thins the neighbor on save. Internal islands mark private
          built-up stretches that are excluded from scan coverage; vacant stretches carry no island and stay scannable.
        </p>
      </div>

      {loading ? (
        <div className="text-neutral-500 text-sm py-12">Loading blocks…</div>
      ) : loadError ? (
        <div className="text-red-400 text-sm py-12">{loadError}</div>
      ) : (
        <BoundaryEditor blocks={blocks} onBlocksChanged={fetchBlocks} />
      )}
    </div>
  )
}
