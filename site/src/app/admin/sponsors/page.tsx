'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSearchParams, useRouter } from 'next/navigation'

type ItemKind =
  | 'recognition_wall'
  | 'certificate'
  | 'tax_receipt'
  | 'newsletter_feature'
  | 'social_recognition'

interface DeliverableItem {
  id: number
  packageId: number
  kind: ItemKind
  status: 'pending' | 'completed'
  data: Record<string, any> | null
  triggeredAt: string | null
}

interface Pkg {
  id: number
  sponsorId: number
  sponsorName: string | null
  sponsorEmail: string | null
  sponsorBusinessName: string | null
  amountCents: number
  source: 'online' | 'offline'
  tier: string
  status: 'pending' | 'in_progress' | 'completed'
  externalReference: string | null
  notes: string | null
  transactionId: number | null
  createdAt: string
  completedAt: string | null
  items: DeliverableItem[]
}

const ITEM_LABELS: Record<ItemKind, { label: string; description: string }> = {
  recognition_wall: {
    label: 'Name on sponsor recognition wall',
    description: 'Adds the sponsor to the public wall and emails them the link.',
  },
  certificate: {
    label: 'Digital certificate of appreciation',
    description: 'Generates a print-ready certificate URL and emails the sponsor.',
  },
  tax_receipt: {
    label: 'Tax-deductible donation receipt',
    description: 'Generates a 501(c)(3) receipt with EIN and gift details.',
  },
  newsletter_feature: {
    label: 'Featured on monthly newsletter',
    description: 'Drafts a feature blurb and queues it for the next send.',
  },
  social_recognition: {
    label: 'Social media recognition',
    description: 'Drafts X / LinkedIn / Instagram copy for admin review and posting.',
  },
}

const ITEM_ORDER: ItemKind[] = [
  'recognition_wall',
  'certificate',
  'tax_receipt',
  'newsletter_feature',
  'social_recognition',
]

function formatDollars(cents: number) {
  const dollars = cents / 100
  if (Number.isInteger(dollars)) return `$${dollars.toLocaleString('en-US')}`
  return dollars.toLocaleString('en-US', { style: 'currency', currency: 'USD' })
}

function formatDate(iso: string | null) {
  if (!iso) return '—'
  return new Date(iso).toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  })
}

export default function AdminSponsorsPage() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const focusedPackageId = searchParams.get('package')

  const [packages, setPackages] = useState<Pkg[]>([])
  const [loading, setLoading] = useState(true)
  const [createOpen, setCreateOpen] = useState(false)
  const [drawerPkg, setDrawerPkg] = useState<Pkg | null>(null)
  const [error, setError] = useState<string | null>(null)

  const authHeaders = useMemo((): Record<string, string> => {
    if (typeof window === 'undefined') return {}
    const token = localStorage.getItem('adminToken')
    return token ? { Authorization: `Bearer ${token}` } : {}
  }, [])

  const fetchPackages = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await fetch('/api/admin/sponsor-deliverables', { headers: authHeaders })
      if (!res.ok) throw new Error(`Fetch failed: ${res.status}`)
      const json = await res.json()
      setPackages(json.data || [])
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load packages')
    } finally {
      setLoading(false)
    }
  }, [authHeaders])

  useEffect(() => {
    fetchPackages()
  }, [fetchPackages])

  // Auto-open drawer if ?package= is in URL
  useEffect(() => {
    if (focusedPackageId && packages.length > 0) {
      const target = packages.find((p) => p.id === Number(focusedPackageId))
      if (target) setDrawerPkg(target)
    }
  }, [focusedPackageId, packages])

  const stats = useMemo(() => {
    const total = packages.length
    const pending = packages.filter((p) => p.status !== 'completed').length
    const totalRaised = packages.reduce((sum, p) => sum + p.amountCents, 0)
    return { total, pending, totalRaised }
  }, [packages])

  return (
    <div className="p-8">
      <header className="mb-8 flex items-end justify-between gap-6">
        <div>
          <h1 className="text-2xl font-semibold text-white">Sponsor Deliverables</h1>
          <p className="mt-1 text-sm text-neutral-400">
            Manage recognition deliverables for $2,000+ sponsors. Online payments
            auto-create packages via the Stripe webhook; offline gifts (checks,
            wires) are entered manually here.
          </p>
        </div>
        <button
          onClick={() => setCreateOpen(true)}
          className="rounded-md bg-emerald-600 px-4 py-2 text-sm font-medium text-white hover:bg-emerald-500"
        >
          + Record offline gift
        </button>
      </header>

      <div className="mb-6 grid grid-cols-3 gap-4">
        <Stat label="Packages" value={String(stats.total)} />
        <Stat label="Awaiting deliverables" value={String(stats.pending)} />
        <Stat label="Total recognized" value={formatDollars(stats.totalRaised)} />
      </div>

      {error && (
        <div className="mb-4 rounded-md border border-red-900 bg-red-950/40 px-4 py-3 text-sm text-red-300">
          {error}
        </div>
      )}

      <div className="rounded-lg border border-neutral-800 bg-neutral-900">
        <div className="grid grid-cols-[1fr_140px_120px_140px_140px_80px] gap-4 border-b border-neutral-800 px-6 py-3 text-xs font-semibold uppercase tracking-wider text-neutral-500">
          <div>Sponsor</div>
          <div>Amount</div>
          <div>Source</div>
          <div>Date</div>
          <div>Status</div>
          <div></div>
        </div>

        {loading ? (
          <div className="px-6 py-12 text-center text-sm text-neutral-500">Loading…</div>
        ) : packages.length === 0 ? (
          <div className="px-6 py-12 text-center text-sm text-neutral-500">
            No deliverable packages yet. Record your first offline gift to get started.
          </div>
        ) : (
          packages.map((p) => {
            const display = p.sponsorBusinessName || p.sponsorName || `Sponsor #${p.sponsorId}`
            const completedCount = p.items.filter((it) => it.status === 'completed').length
            return (
              <button
                key={p.id}
                onClick={() => setDrawerPkg(p)}
                className="grid w-full grid-cols-[1fr_140px_120px_140px_140px_80px] items-center gap-4 border-b border-neutral-800 px-6 py-4 text-left text-sm last:border-b-0 hover:bg-neutral-800/40"
              >
                <div>
                  <div className="font-medium text-white">{display}</div>
                  <div className="text-xs text-neutral-500">{p.sponsorEmail}</div>
                </div>
                <div className="font-mono text-white">{formatDollars(p.amountCents)}</div>
                <div className="text-neutral-400">
                  <span
                    className={`inline-flex rounded-md px-2 py-0.5 text-xs font-medium ${
                      p.source === 'online'
                        ? 'bg-blue-950/50 text-blue-300'
                        : 'bg-neutral-800 text-neutral-300'
                    }`}
                  >
                    {p.source === 'online' ? 'Stripe' : 'Offline'}
                  </span>
                </div>
                <div className="text-neutral-300">{formatDate(p.createdAt)}</div>
                <div>
                  <StatusBadge status={p.status} />
                  <div className="mt-1 text-xs text-neutral-500">
                    {completedCount} / {p.items.length} done
                  </div>
                </div>
                <div className="text-right text-xs text-neutral-500">→</div>
              </button>
            )
          })
        )}
      </div>

      {createOpen && (
        <CreateOfflineModal
          authHeaders={authHeaders}
          onClose={() => setCreateOpen(false)}
          onCreated={async () => {
            setCreateOpen(false)
            await fetchPackages()
          }}
        />
      )}

      {drawerPkg && (
        <PackageDrawer
          pkg={drawerPkg}
          authHeaders={authHeaders}
          onClose={() => {
            setDrawerPkg(null)
            if (focusedPackageId) router.replace('/admin/sponsors')
          }}
          onItemTriggered={fetchPackages}
        />
      )}
    </div>
  )
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-neutral-800 bg-neutral-900 px-4 py-3">
      <div className="text-xs uppercase tracking-wider text-neutral-500">{label}</div>
      <div className="mt-1 text-xl font-semibold text-white">{value}</div>
    </div>
  )
}

function StatusBadge({ status }: { status: 'pending' | 'in_progress' | 'completed' }) {
  const map: Record<typeof status, { label: string; cls: string }> = {
    pending: { label: 'Pending', cls: 'bg-amber-950/50 text-amber-300' },
    in_progress: { label: 'In progress', cls: 'bg-blue-950/50 text-blue-300' },
    completed: { label: 'Completed', cls: 'bg-emerald-950/50 text-emerald-300' },
  }
  const m = map[status]
  return (
    <span className={`inline-flex rounded-md px-2 py-0.5 text-xs font-medium ${m.cls}`}>
      {m.label}
    </span>
  )
}

// ---------------------------------------------------------------------------
// Create offline gift modal
// ---------------------------------------------------------------------------
function CreateOfflineModal({
  authHeaders,
  onClose,
  onCreated,
}: {
  authHeaders: Record<string, string>
  onClose: () => void
  onCreated: () => void
}) {
  const [sponsorName, setSponsorName] = useState('')
  const [sponsorEmail, setSponsorEmail] = useState('')
  const [businessName, setBusinessName] = useState('')
  const [amountDollars, setAmountDollars] = useState('2000')
  const [externalReference, setExternalReference] = useState('')
  const [notes, setNotes] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [err, setErr] = useState<string | null>(null)

  const [searchQuery, setSearchQuery] = useState('')
  const [searchResults, setSearchResults] = useState<
    Array<{ id: number; name: string; email: string; businessName: string | null }>
  >([])
  const [pickedSponsorId, setPickedSponsorId] = useState<number | null>(null)

  // Sponsor search debounce
  useEffect(() => {
    if (searchQuery.length < 2) {
      setSearchResults([])
      return
    }
    const timer = setTimeout(async () => {
      try {
        const res = await fetch(
          `/api/admin/sponsor-deliverables/sponsors?q=${encodeURIComponent(searchQuery)}`,
          { headers: authHeaders }
        )
        if (res.ok) {
          const json = await res.json()
          setSearchResults(json.data || [])
        }
      } catch {
        // silent
      }
    }, 250)
    return () => clearTimeout(timer)
  }, [searchQuery, authHeaders])

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setErr(null)
    setSubmitting(true)
    try {
      const amountCents = Math.round(parseFloat(amountDollars) * 100)
      const body: any = {
        amountCents,
        externalReference: externalReference.trim() || undefined,
        notes: notes.trim() || undefined,
      }
      if (pickedSponsorId) {
        body.sponsorId = pickedSponsorId
      } else {
        body.sponsorName = sponsorName.trim()
        body.sponsorEmail = sponsorEmail.trim()
        body.sponsorBusinessName = businessName.trim() || undefined
      }
      const res = await fetch('/api/admin/sponsor-deliverables', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...authHeaders },
        body: JSON.stringify(body),
      })
      if (!res.ok) {
        const j = await res.json().catch(() => ({}))
        throw new Error(j.error || `Status ${res.status}`)
      }
      onCreated()
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to create package')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center overflow-auto bg-black/70 p-6"
      onClick={onClose}
    >
      <div
        className="mt-12 w-full max-w-xl rounded-xl border border-neutral-800 bg-neutral-900 p-6"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="text-lg font-semibold text-white">Record offline sponsorship</h2>
        <p className="mt-1 text-sm text-neutral-400">
          For checks, wires, ACH, or in-person gifts. Generates the same 5-item
          deliverable package as an online $2,000+ Stripe payment.
        </p>

        <form onSubmit={handleSubmit} className="mt-6 space-y-4">
          <div>
            <label className="text-xs font-medium uppercase tracking-wider text-neutral-400">
              Sponsor (search existing or enter new)
            </label>
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => {
                setSearchQuery(e.target.value)
                setPickedSponsorId(null)
              }}
              placeholder="Search by name, business, or email…"
              className="mt-1 w-full rounded-md border border-neutral-700 bg-neutral-950 px-3 py-2 text-sm text-white placeholder:text-neutral-500"
            />
            {searchResults.length > 0 && !pickedSponsorId && (
              <div className="mt-2 max-h-48 overflow-auto rounded-md border border-neutral-800 bg-neutral-950">
                {searchResults.map((s) => (
                  <button
                    key={s.id}
                    type="button"
                    onClick={() => {
                      setPickedSponsorId(s.id)
                      setSearchQuery(s.businessName || s.name)
                      setSearchResults([])
                    }}
                    className="flex w-full flex-col items-start gap-0.5 border-b border-neutral-800 px-3 py-2 text-left text-sm last:border-b-0 hover:bg-neutral-800"
                  >
                    <span className="text-white">{s.businessName || s.name}</span>
                    <span className="text-xs text-neutral-500">{s.email}</span>
                  </button>
                ))}
              </div>
            )}
            {pickedSponsorId && (
              <div className="mt-2 rounded-md bg-emerald-950/40 px-3 py-2 text-xs text-emerald-300">
                Using existing sponsor #{pickedSponsorId}.{' '}
                <button
                  type="button"
                  onClick={() => {
                    setPickedSponsorId(null)
                    setSearchQuery('')
                  }}
                  className="underline"
                >
                  Clear
                </button>
              </div>
            )}
          </div>

          {!pickedSponsorId && (
            <div className="grid grid-cols-2 gap-4">
              <div className="col-span-2">
                <label className="text-xs font-medium uppercase tracking-wider text-neutral-400">
                  Contact name *
                </label>
                <input
                  type="text"
                  required
                  value={sponsorName}
                  onChange={(e) => setSponsorName(e.target.value)}
                  className="mt-1 w-full rounded-md border border-neutral-700 bg-neutral-950 px-3 py-2 text-sm text-white"
                />
              </div>
              <div>
                <label className="text-xs font-medium uppercase tracking-wider text-neutral-400">
                  Email *
                </label>
                <input
                  type="email"
                  required
                  value={sponsorEmail}
                  onChange={(e) => setSponsorEmail(e.target.value)}
                  className="mt-1 w-full rounded-md border border-neutral-700 bg-neutral-950 px-3 py-2 text-sm text-white"
                />
              </div>
              <div>
                <label className="text-xs font-medium uppercase tracking-wider text-neutral-400">
                  Business / org (optional)
                </label>
                <input
                  type="text"
                  value={businessName}
                  onChange={(e) => setBusinessName(e.target.value)}
                  placeholder="e.g., University of Illinois Chicago"
                  className="mt-1 w-full rounded-md border border-neutral-700 bg-neutral-950 px-3 py-2 text-sm text-white"
                />
              </div>
            </div>
          )}

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="text-xs font-medium uppercase tracking-wider text-neutral-400">
                Amount (USD) *
              </label>
              <input
                type="number"
                required
                min="2000"
                step="any"
                value={amountDollars}
                onChange={(e) => setAmountDollars(e.target.value)}
                className="mt-1 w-full rounded-md border border-neutral-700 bg-neutral-950 px-3 py-2 text-sm text-white"
              />
              <p className="mt-1 text-xs text-neutral-500">Minimum $2,000 to qualify.</p>
            </div>
            <div>
              <label className="text-xs font-medium uppercase tracking-wider text-neutral-400">
                Reference (check #, etc.)
              </label>
              <input
                type="text"
                value={externalReference}
                onChange={(e) => setExternalReference(e.target.value)}
                placeholder="UIC check #1234"
                className="mt-1 w-full rounded-md border border-neutral-700 bg-neutral-950 px-3 py-2 text-sm text-white"
              />
            </div>
          </div>

          <div>
            <label className="text-xs font-medium uppercase tracking-wider text-neutral-400">
              Notes (admin only)
            </label>
            <textarea
              rows={3}
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              className="mt-1 w-full rounded-md border border-neutral-700 bg-neutral-950 px-3 py-2 text-sm text-white"
            />
          </div>

          {err && (
            <div className="rounded-md border border-red-900 bg-red-950/40 px-3 py-2 text-sm text-red-300">
              {err}
            </div>
          )}

          <div className="flex justify-end gap-3 border-t border-neutral-800 pt-4">
            <button
              type="button"
              onClick={onClose}
              className="rounded-md px-4 py-2 text-sm text-neutral-400 hover:text-neutral-200"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={submitting}
              className="rounded-md bg-emerald-600 px-4 py-2 text-sm font-medium text-white hover:bg-emerald-500 disabled:opacity-50"
            >
              {submitting ? 'Creating…' : 'Create package'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Per-package drawer with 5 trigger buttons
// ---------------------------------------------------------------------------
function PackageDrawer({
  pkg,
  authHeaders,
  onClose,
  onItemTriggered,
}: {
  pkg: Pkg
  authHeaders: Record<string, string>
  onClose: () => void
  onItemTriggered: () => Promise<void>
}) {
  const [busyItemId, setBusyItemId] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [items, setItems] = useState(pkg.items)

  // Sort items in canonical order
  const orderedItems = useMemo(() => {
    return [...items].sort(
      (a, b) => ITEM_ORDER.indexOf(a.kind) - ITEM_ORDER.indexOf(b.kind)
    )
  }, [items])

  const display = pkg.sponsorBusinessName || pkg.sponsorName || `Sponsor #${pkg.sponsorId}`

  async function handleTrigger(item: DeliverableItem) {
    setBusyItemId(item.id)
    setError(null)
    try {
      const res = await fetch(
        `/api/admin/sponsor-deliverables/${pkg.id}/items/${item.id}/trigger`,
        {
          method: 'POST',
          headers: authHeaders,
        }
      )
      if (!res.ok) {
        const j = await res.json().catch(() => ({}))
        throw new Error(j.error || `Status ${res.status}`)
      }
      const json = await res.json()
      // Optimistic update — mark item completed and merge data
      setItems((prev) =>
        prev.map((it) =>
          it.id === item.id
            ? {
                ...it,
                status: 'completed',
                data: json.data?.data || it.data,
                triggeredAt: new Date().toISOString(),
              }
            : it
        )
      )
      await onItemTriggered()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Trigger failed')
    } finally {
      setBusyItemId(null)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex justify-end bg-black/60" onClick={onClose}>
      <div
        className="h-full w-full max-w-2xl overflow-auto border-l border-neutral-800 bg-neutral-900 p-8"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="border-b border-neutral-800 pb-6">
          <div className="flex items-start justify-between">
            <div>
              <p className="text-xs uppercase tracking-wider text-neutral-500">Package #{pkg.id}</p>
              <h2 className="mt-1 text-2xl font-semibold text-white">{display}</h2>
              <p className="mt-1 text-sm text-neutral-400">{pkg.sponsorEmail}</p>
            </div>
            <button
              onClick={onClose}
              className="text-neutral-400 hover:text-neutral-200"
              aria-label="Close"
            >
              ✕
            </button>
          </div>
          <div className="mt-4 grid grid-cols-3 gap-4 text-sm">
            <div>
              <p className="text-xs uppercase tracking-wider text-neutral-500">Amount</p>
              <p className="mt-1 font-mono text-white">{formatDollars(pkg.amountCents)}</p>
            </div>
            <div>
              <p className="text-xs uppercase tracking-wider text-neutral-500">Source</p>
              <p className="mt-1 capitalize text-white">{pkg.source}</p>
            </div>
            <div>
              <p className="text-xs uppercase tracking-wider text-neutral-500">Tier</p>
              <p className="mt-1 capitalize text-white">{pkg.tier}</p>
            </div>
          </div>
          {pkg.externalReference && (
            <div className="mt-3 text-sm">
              <span className="text-xs uppercase tracking-wider text-neutral-500">Reference: </span>
              <span className="text-neutral-300">{pkg.externalReference}</span>
            </div>
          )}
          {pkg.notes && (
            <div className="mt-3 rounded-md bg-neutral-950 px-3 py-2 text-sm text-neutral-300">
              {pkg.notes}
            </div>
          )}
        </header>

        {error && (
          <div className="my-4 rounded-md border border-red-900 bg-red-950/40 px-4 py-3 text-sm text-red-300">
            {error}
          </div>
        )}

        <div className="mt-6 space-y-3">
          {orderedItems.map((item) => {
            const meta = ITEM_LABELS[item.kind]
            const completed = item.status === 'completed'
            const busy = busyItemId === item.id
            return (
              <div
                key={item.id}
                className={`rounded-lg border p-4 ${
                  completed
                    ? 'border-emerald-900 bg-emerald-950/20'
                    : 'border-neutral-800 bg-neutral-900'
                }`}
              >
                <div className="flex items-start justify-between gap-4">
                  <div className="flex-1">
                    <div className="flex items-center gap-2">
                      <h3 className="text-base font-medium text-white">{meta.label}</h3>
                      {completed && (
                        <span className="rounded bg-emerald-950 px-2 py-0.5 text-xs font-medium text-emerald-300">
                          ✓ Done
                        </span>
                      )}
                    </div>
                    <p className="mt-1 text-sm text-neutral-400">{meta.description}</p>
                    {completed && item.triggeredAt && (
                      <p className="mt-2 text-xs text-neutral-500">
                        Triggered {new Date(item.triggeredAt).toLocaleString('en-US')}
                      </p>
                    )}
                    <ItemDetail item={item} />
                  </div>
                  <button
                    onClick={() => handleTrigger(item)}
                    disabled={busy}
                    className={`shrink-0 rounded-md px-4 py-2 text-sm font-medium ${
                      completed
                        ? 'bg-neutral-800 text-neutral-300 hover:bg-neutral-700'
                        : 'bg-emerald-600 text-white hover:bg-emerald-500'
                    } disabled:opacity-50`}
                  >
                    {busy ? 'Working…' : completed ? 'Re-send' : 'Trigger'}
                  </button>
                </div>
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}

function ItemDetail({ item }: { item: DeliverableItem }) {
  if (!item.data || item.status !== 'completed') return null
  const data = item.data

  if (item.kind === 'recognition_wall') {
    return (
      <div className="mt-3 text-xs text-neutral-400">
        Wall URL:{' '}
        <a href={data.wallUrl} target="_blank" rel="noreferrer" className="text-emerald-400 underline">
          {data.wallUrl}
        </a>
      </div>
    )
  }
  if (item.kind === 'certificate') {
    return (
      <div className="mt-3 text-xs text-neutral-400">
        Certificate:{' '}
        <a
          href={data.certificateUrl}
          target="_blank"
          rel="noreferrer"
          className="text-emerald-400 underline"
        >
          {data.certificateUrl}
        </a>
      </div>
    )
  }
  if (item.kind === 'tax_receipt') {
    return (
      <div className="mt-3 text-xs text-neutral-400">
        Receipt #{data.receiptNumber} ·{' '}
        <a
          href={data.receiptUrl}
          target="_blank"
          rel="noreferrer"
          className="text-emerald-400 underline"
        >
          View
        </a>
      </div>
    )
  }
  if (item.kind === 'newsletter_feature') {
    return (
      <div className="mt-3 rounded-md bg-neutral-950 px-3 py-2 text-xs italic text-neutral-300">
        “{data.blurb}”
      </div>
    )
  }
  if (item.kind === 'social_recognition') {
    return (
      <div className="mt-3 space-y-2">
        {(['twitter', 'linkedin', 'instagram'] as const).map((channel) =>
          data[channel] ? (
            <div key={channel} className="rounded-md bg-neutral-950 px-3 py-2 text-xs">
              <p className="font-medium uppercase tracking-wider text-neutral-500">{channel}</p>
              <p className="mt-1 whitespace-pre-wrap text-neutral-300">{data[channel]}</p>
            </div>
          ) : null
        )}
      </div>
    )
  }
  return null
}
