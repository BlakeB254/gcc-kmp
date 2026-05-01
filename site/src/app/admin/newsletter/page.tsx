'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'

interface AudienceMember {
  email: string
  name: string | null
  source: 'sponsor' | 'waitlist'
}

interface SendResult {
  mode: 'test' | 'production'
  sendId: number | null
  from: string
  subject: string
  attempted: number
  success: number
  failure: number
  failed: Array<{ email: string; error: string }>
}

export default function AdminNewsletterPage() {
  const [audience, setAudience] = useState<AudienceMember[]>([])
  const [counts, setCounts] = useState({ total: 0, sponsors: 0, waitlist: 0 })
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [sendResult, setSendResult] = useState<SendResult | null>(null)
  const [sending, setSending] = useState<'test' | 'production' | null>(null)
  const [testRecipient, setTestRecipient] = useState('')
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [previousSends, setPreviousSends] = useState<any[]>([])

  const authHeaders = useMemo((): Record<string, string> => {
    if (typeof window === 'undefined') return {}
    const t = localStorage.getItem('adminToken')
    return t ? { Authorization: `Bearer ${t}` } : {}
  }, [])

  const loadAudience = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const [aRes, sRes] = await Promise.all([
        fetch('/api/admin/newsletter/audience', { headers: authHeaders }),
        fetch('/api/admin/newsletter/send', { headers: authHeaders }),
      ])
      if (!aRes.ok) throw new Error(`Audience: ${aRes.status}`)
      const a = await aRes.json()
      setAudience(a.data || [])
      setCounts(a.counts || { total: 0, sponsors: 0, waitlist: 0 })
      if (sRes.ok) {
        const s = await sRes.json()
        setPreviousSends(s.data || [])
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load')
    } finally {
      setLoading(false)
    }
  }, [authHeaders])

  useEffect(() => {
    loadAudience()
  }, [loadAudience])

  async function handleSend(mode: 'test' | 'production') {
    setSending(mode)
    setError(null)
    setSendResult(null)
    try {
      const body: any = { mode }
      if (mode === 'test') body.testRecipient = testRecipient.trim()
      const res = await fetch('/api/admin/newsletter/send', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...authHeaders },
        body: JSON.stringify(body),
      })
      const j = await res.json()
      if (!res.ok) {
        throw new Error(j.error || `Status ${res.status}`)
      }
      setSendResult(j.data)
      if (mode === 'production') {
        setConfirmOpen(false)
        await loadAudience()
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Send failed')
    } finally {
      setSending(null)
    }
  }

  return (
    <div className="p-8">
      <header className="mb-8">
        <h1 className="text-2xl font-semibold text-white">Newsletter</h1>
        <p className="mt-1 text-sm text-neutral-400">
          Issue #1 — "Sixty years and two blocks" — drops May 1, 2026
        </p>
      </header>

      <div className="mb-6 grid grid-cols-3 gap-4">
        <Stat label="Total recipients" value={String(counts.total)} accent />
        <Stat label="Sponsor subscribers" value={String(counts.sponsors)} />
        <Stat label="Waitlist subscribers" value={String(counts.waitlist)} />
      </div>

      {error && (
        <div className="mb-4 rounded-md border border-red-900 bg-red-950/40 px-4 py-3 text-sm text-red-300">
          {error}
        </div>
      )}

      {previousSends.length > 0 && (
        <div className="mb-6 rounded-md border border-amber-900 bg-amber-950/30 px-4 py-3 text-sm text-amber-200">
          ⚠️ Issue #1 has already been sent. Previous send:{' '}
          <strong>
            {previousSends[0].successCount} / {previousSends[0].recipientCount} delivered
          </strong>{' '}
          on {new Date(previousSends[0].startedAt).toLocaleString('en-US')}.
        </div>
      )}

      <div className="grid grid-cols-3 gap-6">
        {/* Left: Preview iframe */}
        <div className="col-span-2 rounded-lg border border-neutral-800 bg-neutral-900">
          <div className="flex items-center justify-between border-b border-neutral-800 px-6 py-3">
            <h2 className="text-sm font-semibold text-white">Email preview</h2>
            <a
              href="/api/admin/newsletter/preview"
              target="_blank"
              rel="noreferrer"
              className="text-xs text-emerald-400 hover:text-emerald-300"
            >
              Open in new tab →
            </a>
          </div>
          <iframe
            src="/api/admin/newsletter/preview"
            className="h-[800px] w-full bg-white"
            title="Newsletter preview"
          />
        </div>

        {/* Right: Audience + send controls */}
        <div className="space-y-6">
          {/* Audience list */}
          <div className="rounded-lg border border-neutral-800 bg-neutral-900">
            <div className="border-b border-neutral-800 px-4 py-3">
              <h2 className="text-sm font-semibold text-white">Recipients</h2>
              <p className="text-xs text-neutral-500">
                Excludes opt-outs, test addresses, and example.* domains
              </p>
            </div>
            <div className="max-h-[300px] overflow-auto">
              {loading ? (
                <div className="px-4 py-6 text-sm text-neutral-500">Loading…</div>
              ) : audience.length === 0 ? (
                <div className="px-4 py-6 text-sm text-neutral-500">No recipients.</div>
              ) : (
                audience.map((m) => (
                  <div
                    key={m.email}
                    className="flex items-center justify-between border-b border-neutral-800 px-4 py-2 text-xs last:border-b-0"
                  >
                    <div>
                      <div className="text-neutral-200">{m.email}</div>
                      <div className="text-neutral-500">{m.name || '—'}</div>
                    </div>
                    <span
                      className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${
                        m.source === 'sponsor'
                          ? 'bg-emerald-950 text-emerald-300'
                          : 'bg-blue-950 text-blue-300'
                      }`}
                    >
                      {m.source}
                    </span>
                  </div>
                ))
              )}
            </div>
          </div>

          {/* Send test */}
          <div className="rounded-lg border border-neutral-800 bg-neutral-900 p-4">
            <h2 className="text-sm font-semibold text-white">Send test email</h2>
            <p className="mt-1 text-xs text-neutral-500">
              Sends only to one address so you can verify rendering before going live.
            </p>
            <input
              type="email"
              placeholder="you@example.com"
              value={testRecipient}
              onChange={(e) => setTestRecipient(e.target.value)}
              className="mt-3 w-full rounded-md border border-neutral-700 bg-neutral-950 px-3 py-2 text-sm text-white placeholder:text-neutral-500"
            />
            <button
              onClick={() => handleSend('test')}
              disabled={!testRecipient.includes('@') || sending !== null}
              className="mt-3 w-full rounded-md bg-neutral-700 px-4 py-2 text-sm font-medium text-white hover:bg-neutral-600 disabled:opacity-50"
            >
              {sending === 'test' ? 'Sending test…' : 'Send test'}
            </button>
          </div>

          {/* Send to all */}
          <div className="rounded-lg border border-emerald-900 bg-emerald-950/30 p-4">
            <h2 className="text-sm font-semibold text-white">Send to all subscribers</h2>
            <p className="mt-1 text-xs text-neutral-400">
              Sends Issue #1 to all {counts.total} subscribers. This is one-shot — once it
              ships, the system will refuse to resend the same issue.
            </p>
            <button
              onClick={() => setConfirmOpen(true)}
              disabled={sending !== null || counts.total === 0 || previousSends.length > 0}
              className="mt-3 w-full rounded-md bg-emerald-600 px-4 py-2 text-sm font-medium text-white hover:bg-emerald-500 disabled:opacity-50"
            >
              {previousSends.length > 0
                ? 'Already sent'
                : `Send to all ${counts.total} subscribers`}
            </button>
          </div>

          {/* Result */}
          {sendResult && (
            <div className="rounded-lg border border-neutral-700 bg-neutral-900 p-4 text-xs">
              <h3 className="text-sm font-semibold text-white">
                {sendResult.mode === 'test' ? 'Test send result' : 'Send complete'}
              </h3>
              <div className="mt-2 space-y-1 text-neutral-300">
                <div>From: <span className="font-mono text-neutral-200">{sendResult.from}</span></div>
                <div>Subject: <span className="text-neutral-200">{sendResult.subject}</span></div>
                <div>
                  Delivered: <span className="text-emerald-400">{sendResult.success}</span> / {sendResult.attempted}
                  {sendResult.failure > 0 && (
                    <span className="ml-2 text-red-400">({sendResult.failure} failed)</span>
                  )}
                </div>
                {sendResult.failed.length > 0 && (
                  <details className="mt-2">
                    <summary className="cursor-pointer text-neutral-400">Failures</summary>
                    <ul className="mt-2 space-y-1">
                      {sendResult.failed.map((f) => (
                        <li key={f.email} className="text-red-400">
                          {f.email}: {f.error}
                        </li>
                      ))}
                    </ul>
                  </details>
                )}
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Confirm modal */}
      {confirmOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/70"
          onClick={() => setConfirmOpen(false)}
        >
          <div
            className="w-full max-w-md rounded-xl border border-neutral-800 bg-neutral-900 p-6"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 className="text-lg font-semibold text-white">Send Issue #1 to {counts.total} people?</h2>
            <p className="mt-2 text-sm text-neutral-400">
              The newsletter will go out via Resend at roughly 3 emails/second. Once it ships, the
              system records the send and won't allow a duplicate. Failures will be reported back.
            </p>
            <p className="mt-3 text-sm text-neutral-300">
              Recipients: <strong>{counts.sponsors} sponsors</strong> + <strong>{counts.waitlist} waitlist</strong>.
            </p>
            <div className="mt-6 flex justify-end gap-3">
              <button
                onClick={() => setConfirmOpen(false)}
                className="rounded-md px-4 py-2 text-sm text-neutral-400 hover:text-neutral-200"
              >
                Cancel
              </button>
              <button
                onClick={() => handleSend('production')}
                disabled={sending !== null}
                className="rounded-md bg-emerald-600 px-4 py-2 text-sm font-medium text-white hover:bg-emerald-500 disabled:opacity-50"
              >
                {sending === 'production' ? 'Sending…' : 'Yes, send to all'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

function Stat({
  label,
  value,
  accent = false,
}: {
  label: string
  value: string
  accent?: boolean
}) {
  return (
    <div
      className={`rounded-lg border px-4 py-3 ${
        accent
          ? 'border-emerald-900 bg-emerald-950/30'
          : 'border-neutral-800 bg-neutral-900'
      }`}
    >
      <div className="text-xs uppercase tracking-wider text-neutral-500">{label}</div>
      <div className={`mt-1 text-2xl font-semibold ${accent ? 'text-emerald-300' : 'text-white'}`}>
        {value}
      </div>
    </div>
  )
}
