'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'

interface Reservation {
  id: number
  amount: string
  sponsorType: string | null
  reservedBlockCount: number
  reservedBlockCodes: string | null
  reservationAllocatedAt: string | null
  reservationNote: string | null
  preferredCleaningDate: string | null
  stripeCheckoutSessionId: string | null
  createdAt: string
  sponsorName: string | null
  sponsorBusiness: string | null
  sponsorEmail: string | null
}

interface Summary {
  open: number
  openBlocks: number
  openCents: number
  allocated: number
}

const day = (iso: string | null) => (iso ? new Date(iso).toLocaleDateString() : '—')
const dollars = (cents: number | string) => `$${(Number(cents) / 100).toLocaleString()}`

const FREQUENCY: Record<string, string> = {
  one_time: 'One time',
  weekly: 'Weekly',
  biweekly: 'Every 2 weeks',
  monthly: 'Monthly',
}

export default function AdminReservationsPage() {
  const [rows, setRows] = useState<Reservation[]>([])
  const [summary, setSummary] = useState<Summary | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notes, setNotes] = useState<Record<number, string>>({})

  const authHeaders = useMemo((): Record<string, string> => {
    if (typeof window === 'undefined') return {}
    const t = localStorage.getItem('adminToken')
    return t ? { Authorization: `Bearer ${t}` } : {}
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await fetch('/api/admin/reservations', { headers: authHeaders })
      if (!res.ok) throw new Error(`Reservations: ${res.status}`)
      const body = await res.json()
      setRows(body.data || [])
      setSummary(body.summary || null)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load')
    } finally {
      setLoading(false)
    }
  }, [authHeaders])

  useEffect(() => {
    load()
  }, [load])

  async function act(id: number, action: 'allocate' | 'reopen') {
    setBusy(true)
    setError(null)
    try {
      const res = await fetch('/api/admin/reservations', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...authHeaders },
        body: JSON.stringify({ id, action, note: notes[id] || undefined }),
      })
      if (!res.ok) throw new Error((await res.json().catch(() => ({}))).error || `Status ${res.status}`)
      await load()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Action failed')
    } finally {
      setBusy(false)
    }
  }

  const open = rows.filter((r) => !r.reservationAllocatedAt)
  const done = rows.filter((r) => r.reservationAllocatedAt)

  return (
    <div className="max-w-5xl p-6 text-white">
      <h1 className="text-xl font-semibold">Block Reservations</h1>
      <p className="mt-1 text-sm text-neutral-400">
        Block sponsorships that were paid while the blocks were not open. Each one is a cleaning
        GCC owes a sponsor. Schedule it in Routes &amp; Blocks, then mark it allocated here.
        Marking a reservation allocated does not open any block to workers.
      </p>

      {error && (
        <div className="mt-4 rounded-lg bg-red-950/40 px-4 py-2 text-sm text-red-300">{error}</div>
      )}

      {summary && (
        <div className="mt-6 grid grid-cols-2 gap-3 sm:grid-cols-4">
          {[
            { label: 'Waiting to be allocated', value: summary.open },
            { label: 'Blocks owed', value: summary.openBlocks },
            { label: 'Paid, not yet allocated', value: dollars(summary.openCents) },
            { label: 'Allocated', value: summary.allocated },
          ].map((s) => (
            <div key={s.label} className="rounded-lg border border-neutral-800 bg-neutral-900 p-4">
              <div className="text-2xl font-semibold">{s.value}</div>
              <div className="mt-1 text-xs text-neutral-400">{s.label}</div>
            </div>
          ))}
        </div>
      )}

      <section className="mt-8">
        <h2 className="font-semibold">Waiting ({open.length})</h2>
        {loading && <p className="mt-2 text-sm text-neutral-400">Loading…</p>}
        {!loading && open.length === 0 && (
          <p className="mt-2 text-sm text-neutral-400">No reservations are waiting.</p>
        )}
        <div className="mt-3 space-y-3">
          {open.map((r) => (
            <div key={r.id} className="rounded-lg border border-neutral-800 bg-neutral-900 p-4 text-sm">
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <div className="font-medium">
                  {r.sponsorBusiness || r.sponsorName || 'Unknown sponsor'}
                  <span className="ml-2 font-normal text-neutral-400">{r.sponsorEmail}</span>
                </div>
                <div className="text-neutral-300">
                  {r.reservedBlockCount} block{r.reservedBlockCount === 1 ? '' : 's'} ·{' '}
                  {dollars(r.amount)} · {FREQUENCY[r.sponsorType || ''] || r.sponsorType || '—'}
                </div>
              </div>
              <dl className="mt-2 grid gap-x-6 gap-y-1 text-xs text-neutral-400 sm:grid-cols-3">
                <div>
                  <dt className="inline">Blocks asked for: </dt>
                  <dd className="inline text-neutral-200">
                    {r.reservedBlockCodes ? r.reservedBlockCodes.split(',').join(', ') : 'GCC chooses'}
                  </dd>
                </div>
                <div>
                  <dt className="inline">Paid: </dt>
                  <dd className="inline text-neutral-200">{day(r.createdAt)}</dd>
                </div>
                <div>
                  <dt className="inline">Preferred cleaning date: </dt>
                  <dd className="inline text-neutral-200">{day(r.preferredCleaningDate)}</dd>
                </div>
              </dl>
              <div className="mt-3 flex flex-wrap items-center gap-2">
                <input
                  type="text"
                  value={notes[r.id] || ''}
                  onChange={(e) => setNotes((n) => ({ ...n, [r.id]: e.target.value }))}
                  placeholder="Note — which block, which date"
                  aria-label={`Allocation note for reservation ${r.id}`}
                  maxLength={1000}
                  className="min-w-[16rem] flex-1 rounded-md border border-neutral-700 bg-neutral-950 px-3 py-1.5 text-xs text-neutral-100"
                />
                <button
                  onClick={() => act(r.id, 'allocate')}
                  disabled={busy}
                  className="rounded-md bg-emerald-600 px-3 py-1.5 text-xs font-semibold hover:bg-emerald-500 disabled:opacity-50"
                >
                  Mark allocated
                </button>
              </div>
            </div>
          ))}
        </div>
      </section>

      {done.length > 0 && (
        <section className="mt-10">
          <h2 className="font-semibold">Allocated ({done.length})</h2>
          <div className="mt-3 overflow-x-auto rounded-lg border border-neutral-800">
            <table className="w-full text-left text-sm">
              <thead className="bg-neutral-900 text-xs text-neutral-400">
                <tr>
                  <th className="px-3 py-2 font-medium">Sponsor</th>
                  <th className="px-3 py-2 font-medium">Blocks</th>
                  <th className="px-3 py-2 font-medium">Allocated</th>
                  <th className="px-3 py-2 font-medium">Note</th>
                  <th className="px-3 py-2 font-medium" />
                </tr>
              </thead>
              <tbody>
                {done.map((r) => (
                  <tr key={r.id} className="border-t border-neutral-800">
                    <td className="px-3 py-2">{r.sponsorBusiness || r.sponsorName || '—'}</td>
                    <td className="px-3 py-2 text-neutral-300">
                      {r.reservedBlockCount}
                      {r.reservedBlockCodes ? ` (${r.reservedBlockCodes.split(',').join(', ')})` : ''}
                    </td>
                    <td className="px-3 py-2 text-xs text-neutral-400">{day(r.reservationAllocatedAt)}</td>
                    <td className="px-3 py-2 text-xs text-neutral-300">{r.reservationNote || '—'}</td>
                    <td className="px-3 py-2 text-xs">
                      <button
                        onClick={() => act(r.id, 'reopen')}
                        disabled={busy}
                        className="text-neutral-400 hover:text-white hover:underline disabled:opacity-50"
                      >
                        Reopen
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </div>
  )
}
