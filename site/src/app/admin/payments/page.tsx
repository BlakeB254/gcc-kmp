'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'

interface StripeStatus {
  configured: boolean
  secretKeyMode: 'live' | 'test' | 'missing' | 'unknown'
  publishableKeyMode: 'live' | 'test' | 'missing' | 'unknown'
  keysMatch: boolean
  webhookSecretSet: boolean
  account: {
    id: string
    name: string | null
    email: string | null
    country: string | null
    chargesEnabled: boolean
    payoutsEnabled: boolean
    detailsSubmitted: boolean
  } | null
  error: string | null
}

function Row({ label, value, ok }: { label: string; value: string; ok?: boolean }) {
  return (
    <div className="flex items-center justify-between gap-4 border-b border-neutral-800 px-4 py-3 text-sm last:border-b-0">
      <span className="text-neutral-400">{label}</span>
      <span
        className={
          ok === undefined ? 'text-neutral-100' : ok ? 'text-emerald-300' : 'font-semibold text-red-300'
        }
      >
        {value}
      </span>
    </div>
  )
}

export default function AdminPaymentsPage() {
  const [status, setStatus] = useState<StripeStatus | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const authHeaders = useMemo((): Record<string, string> => {
    if (typeof window === 'undefined') return {}
    const t = localStorage.getItem('adminToken')
    return t ? { Authorization: `Bearer ${t}` } : {}
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await fetch('/api/admin/stripe-status', { headers: authHeaders })
      if (!res.ok) throw new Error(`Stripe status: ${res.status}`)
      setStatus(await res.json())
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load')
    } finally {
      setLoading(false)
    }
  }, [authHeaders])

  useEffect(() => {
    load()
  }, [load])

  const healthy =
    !!status?.account &&
    status.secretKeyMode === 'live' &&
    status.keysMatch &&
    status.webhookSecretSet &&
    status.account.chargesEnabled

  return (
    <div className="max-w-2xl p-6 text-white">
      <h1 className="text-xl font-semibold">Payments</h1>
      <p className="mt-1 text-sm text-neutral-400">
        The Stripe account this site takes sponsorships and donations on. Check that the account
        name below is the organization&rsquo;s own.
      </p>

      {loading && <p className="mt-6 text-sm text-neutral-400">Asking Stripe…</p>}
      {error && (
        <div className="mt-4 rounded-lg bg-red-950/40 px-4 py-2 text-sm text-red-300">{error}</div>
      )}

      {status && (
        <>
          <div
            className={`mt-6 rounded-lg px-4 py-3 text-sm font-medium ${
              healthy ? 'bg-emerald-950/50 text-emerald-300' : 'bg-red-950/50 text-red-300'
            }`}
          >
            {healthy
              ? 'Checkout is on a live Stripe account that can take payments.'
              : 'Something needs attention — see the rows in red.'}
          </div>

          <div className="mt-4 rounded-lg border border-neutral-800 bg-neutral-900">
            <Row
              label="Stripe account"
              value={status.account?.name || status.account?.id || 'unknown'}
              ok={status.account ? undefined : false}
            />
            {status.account && (
              <>
                <Row label="Account ID" value={status.account.id} />
                <Row label="Account email" value={status.account.email || '—'} />
                <Row
                  label="Can take payments"
                  value={status.account.chargesEnabled ? 'Yes' : 'No'}
                  ok={status.account.chargesEnabled}
                />
                <Row
                  label="Can pay out to the bank"
                  value={status.account.payoutsEnabled ? 'Yes' : 'No'}
                  ok={status.account.payoutsEnabled}
                />
              </>
            )}
            <Row
              label="Mode"
              value={
                status.secretKeyMode === 'live'
                  ? 'Live (real payments)'
                  : status.secretKeyMode === 'test'
                    ? 'Test (no real money moves)'
                    : status.secretKeyMode
              }
              ok={status.secretKeyMode === 'live'}
            />
            <Row
              label="Browser key matches server key"
              value={status.keysMatch ? 'Yes' : `No — browser key is ${status.publishableKeyMode}`}
              ok={status.keysMatch}
            />
            <Row
              label="Webhook secret set"
              value={status.webhookSecretSet ? 'Yes' : 'No — paid checkouts will not be recorded'}
              ok={status.webhookSecretSet}
            />
          </div>

          {status.error && (
            <p className="mt-3 text-sm text-red-300">Stripe said: {status.error}</p>
          )}
        </>
      )}

      <button
        onClick={load}
        disabled={loading}
        className="mt-6 rounded-md bg-neutral-700 px-3 py-1.5 text-xs font-medium hover:bg-neutral-600 disabled:opacity-50"
      >
        Check again
      </button>
    </div>
  )
}
