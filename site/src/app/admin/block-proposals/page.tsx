'use client'

import { useEffect, useState, useCallback, useMemo } from 'react'
import { CheckCircle2, XCircle, Loader2, MapPin, Building2 } from 'lucide-react'
import { BlockProposalsMap, type BlockProposalMapEntry } from '@/components/admin/BlockProposalsMap'

interface Proposal {
  id: number
  status: 'pending' | 'approved' | 'rejected'
  codeHint: string | null
  name: string
  streetName: string | null
  fromCorner: string | null
  toCorner: string | null
  boundary: { type: 'Polygon'; coordinates: [number, number][][] }
  center: { type: 'Point'; coordinates: [number, number] }
  exclusionZoneCount: number
  exclusionZones: { polygon: { type: 'Polygon'; coordinates: [number, number][][] } }[] | null
  generationBatch: string | null
  rejectionReason: string | null
  publishedBlockId: number | null
}

interface ExistingBlock {
  code: string | null
  boundary: { type: 'Polygon'; coordinates: [number, number][][] } | null
}

function authHeaders(): HeadersInit {
  const token = localStorage.getItem('adminToken')
  return { Authorization: `Bearer ${token}` }
}

export default function BlockProposalsPage() {
  const [proposals, setProposals] = useState<Proposal[]>([])
  const [existingBlocks, setExistingBlocks] = useState<ExistingBlock[]>([])
  const [loading, setLoading] = useState(true)
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [statusFilter, setStatusFilter] = useState<'pending' | 'approved' | 'rejected' | 'all'>('pending')
  const [actioningId, setActioningId] = useState<number | null>(null)
  const [rejectReasonDraft, setRejectReasonDraft] = useState('')

  const fetchProposals = useCallback(async () => {
    setLoading(true)
    try {
      const qs = statusFilter === 'all' ? '' : `?status=${statusFilter}`
      const [proposalsRes, blocksRes] = await Promise.all([
        fetch(`/api/admin/block-proposals${qs}`, { headers: authHeaders() }),
        fetch('/api/admin/blocks', { headers: authHeaders() }),
      ])
      const proposalsData = await proposalsRes.json()
      const blocksData = await blocksRes.json()
      setProposals(proposalsData.data || [])
      setExistingBlocks((blocksData.data || []).map((b: { code: string | null; boundary: ExistingBlock['boundary'] }) => ({ code: b.code, boundary: b.boundary })))
    } catch (error) {
      console.error('Failed to load block proposals', error)
    } finally {
      setLoading(false)
    }
  }, [statusFilter])

  useEffect(() => {
    fetchProposals()
  }, [fetchProposals])

  const selected = useMemo(() => proposals.find((p) => p.id === selectedId) || null, [proposals, selectedId])

  async function handleApprove(id: number) {
    // An approved proposal becomes a block with status `available`, which is
    // the status that lets a worker claim it — and a claimed, cleaned block is
    // $100 owed. Make that a decision, not a side effect of a click.
    const ok = window.confirm(
      'Approving creates this block as AVAILABLE, which lets workers claim it now.\n\n' +
        'A claimed and cleaned block is $100 owed to the worker. Only approve a block that has money behind it.\n\n' +
        'Approve this block?',
    )
    if (!ok) return
    setActioningId(id)
    try {
      const res = await fetch(`/api/admin/block-proposals/${id}/approve`, { method: 'POST', headers: authHeaders() })
      if (!res.ok) {
        const data = await res.json().catch(() => ({}))
        alert(`Approve failed: ${data.error || res.statusText}`)
        return
      }
      await fetchProposals()
    } finally {
      setActioningId(null)
    }
  }

  async function handleReject(id: number) {
    setActioningId(id)
    try {
      const res = await fetch(`/api/admin/block-proposals/${id}/reject`, {
        method: 'POST',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ reason: rejectReasonDraft || null }),
      })
      if (!res.ok) {
        const data = await res.json().catch(() => ({}))
        alert(`Reject failed: ${data.error || res.statusText}`)
        return
      }
      setRejectReasonDraft('')
      await fetchProposals()
    } finally {
      setActioningId(null)
    }
  }

  const mapEntries: BlockProposalMapEntry[] = proposals.map((p) => ({
    id: p.id,
    status: p.status,
    codeHint: p.codeHint,
    boundary: p.boundary,
    center: p.center,
    exclusionZones: p.exclusionZones,
  }))

  const pendingCount = proposals.filter((p) => p.status === 'pending').length

  return (
    <div className="p-6 max-w-7xl mx-auto">
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-neutral-900">Block Proposals</h1>
        <p className="text-neutral-500 mt-1">
          Auto-generated block candidates from real street data, awaiting review. Nothing here is live until
          approved — approving inserts a real row into <code className="text-xs bg-neutral-100 px-1 py-0.5 rounded">blocks</code>,
          rejecting just marks the candidate as declined.{' '}
          <strong className="text-neutral-700">
            An approved block is open for workers to claim straight away, so approve only blocks that are funded.
          </strong>
        </p>
      </div>

      {/* Status filter tabs */}
      <div className="flex items-center gap-2 mb-4">
        {(['pending', 'approved', 'rejected', 'all'] as const).map((s) => (
          <button
            key={s}
            onClick={() => {
              setStatusFilter(s)
              setSelectedId(null)
            }}
            className={`px-3 py-1.5 rounded-lg text-sm font-medium capitalize transition-colors ${
              statusFilter === s ? 'bg-brand-green-600 text-white' : 'bg-neutral-100 text-neutral-600 hover:bg-neutral-200'
            }`}
          >
            {s}
            {s === 'pending' && pendingCount > 0 && (
              <span className="ml-1.5 rounded-full bg-white/20 px-1.5 py-0.5 text-xs">{pendingCount}</span>
            )}
          </button>
        ))}
      </div>

      {loading ? (
        <div className="flex items-center justify-center py-24">
          <Loader2 className="w-6 h-6 animate-spin text-brand-green-600" />
        </div>
      ) : proposals.length === 0 ? (
        <div className="text-center py-24 text-neutral-400">No {statusFilter !== 'all' ? statusFilter : ''} proposals.</div>
      ) : (
        <div className="grid lg:grid-cols-[1fr_360px] gap-6">
          {/* Map */}
          <div className="rounded-2xl overflow-hidden border border-neutral-200">
            <BlockProposalsMap
              proposals={mapEntries}
              existingBlocks={existingBlocks}
              selectedId={selectedId}
              onSelect={setSelectedId}
            />
          </div>

          {/* List + detail */}
          <div className="space-y-3 max-h-[480px] overflow-y-auto pr-1">
            {proposals.map((p) => (
              <div
                key={p.id}
                onClick={() => setSelectedId(p.id)}
                className={`cursor-pointer rounded-xl border p-3 transition-colors ${
                  p.id === selectedId ? 'border-brand-green-400 bg-brand-green-50' : 'border-neutral-200 bg-white hover:bg-neutral-50'
                }`}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="font-mono text-sm font-semibold text-neutral-900">{p.codeHint}</span>
                  <span
                    className={`text-[10px] font-semibold uppercase tracking-wide px-2 py-0.5 rounded-full ${
                      p.status === 'pending'
                        ? 'bg-amber-100 text-amber-700'
                        : p.status === 'approved'
                          ? 'bg-green-100 text-green-700'
                          : 'bg-red-100 text-red-700'
                    }`}
                  >
                    {p.status}
                  </span>
                </div>
                <p className="text-xs text-neutral-500 mt-1 flex items-center gap-1">
                  <MapPin className="w-3 h-3" />
                  {p.fromCorner} &rarr; {p.toCorner}
                </p>
                <p className="text-xs text-neutral-400 mt-0.5 flex items-center gap-1">
                  <Building2 className="w-3 h-3" />
                  {p.exclusionZoneCount} exclusion zones
                </p>

                {p.status === 'pending' && (
                  <div className="flex gap-2 mt-2">
                    <button
                      onClick={(e) => {
                        e.stopPropagation()
                        handleApprove(p.id)
                      }}
                      disabled={actioningId === p.id}
                      className="flex-1 inline-flex items-center justify-center gap-1 text-xs font-semibold py-1.5 rounded-lg bg-green-600 text-white hover:bg-green-700 disabled:opacity-50"
                    >
                      {actioningId === p.id ? <Loader2 className="w-3 h-3 animate-spin" /> : <CheckCircle2 className="w-3 h-3" />}
                      Approve
                    </button>
                    <button
                      onClick={(e) => {
                        e.stopPropagation()
                        setSelectedId(p.id)
                        handleReject(p.id)
                      }}
                      disabled={actioningId === p.id}
                      className="flex-1 inline-flex items-center justify-center gap-1 text-xs font-semibold py-1.5 rounded-lg bg-neutral-100 text-neutral-600 hover:bg-red-100 hover:text-red-700 disabled:opacity-50"
                    >
                      <XCircle className="w-3 h-3" />
                      Reject
                    </button>
                  </div>
                )}
                {p.status === 'rejected' && p.rejectionReason && (
                  <p className="text-xs text-red-500 mt-2 italic">&ldquo;{p.rejectionReason}&rdquo;</p>
                )}
                {p.status === 'approved' && p.publishedBlockId && (
                  <p className="text-xs text-green-600 mt-2">Published as block #{p.publishedBlockId}</p>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {selected && selected.status === 'pending' && (
        <div className="mt-4 flex items-center gap-2 max-w-lg">
          <input
            type="text"
            placeholder="Optional rejection reason (e.g. crosses a park boundary)"
            value={rejectReasonDraft}
            onChange={(e) => setRejectReasonDraft(e.target.value)}
            className="flex-1 text-sm border border-neutral-200 rounded-lg px-3 py-2"
          />
        </div>
      )}
    </div>
  )
}
