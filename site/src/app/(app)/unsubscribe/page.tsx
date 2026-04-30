'use client'

import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'next/navigation'

export default function UnsubscribePage() {
  const params = useSearchParams()
  const token = params.get('token') || ''
  const [status, setStatus] = useState<'idle' | 'submitting' | 'done' | 'error'>('idle')
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [email, setEmail] = useState<string | null>(null)

  // Decode email from token client-side just for display (the server still re-verifies)
  useEffect(() => {
    if (!token) return
    try {
      const [payloadB64] = token.split('.')
      const payload = atob(payloadB64.replace(/-/g, '+').replace(/_/g, '/'))
      const [maybeEmail] = payload.split('|')
      if (maybeEmail && maybeEmail.includes('@')) setEmail(maybeEmail)
    } catch {
      // ignore — server will reject malformed tokens
    }
  }, [token])

  async function handleConfirm() {
    setStatus('submitting')
    setError(null)
    try {
      const res = await fetch('/api/unsubscribe', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token, reason: reason.trim() || undefined }),
      })
      if (!res.ok) {
        const j = await res.json().catch(() => ({}))
        throw new Error(j.error || `Status ${res.status}`)
      }
      setStatus('done')
    } catch (e) {
      setStatus('error')
      setError(e instanceof Error ? e.message : 'Unsubscribe failed')
    }
  }

  if (!token) {
    return (
      <Wrap>
        <H>Missing unsubscribe link</H>
        <P>This page must be visited from the unsubscribe link in one of our emails.</P>
      </Wrap>
    )
  }

  if (status === 'done') {
    return (
      <Wrap>
        <H>You are unsubscribed</H>
        <P>
          {email ? (
            <><strong>{email}</strong> has been removed from the Global Community Cleaners newsletter.</>
          ) : (
            'Your email has been removed from the Global Community Cleaners newsletter.'
          )}
          {' '}You will no longer receive these issues. If this was a mistake, you can resubscribe anytime at{' '}
          <a className="text-emerald-700 underline" href="/">globalcommunitycleaners.org</a>.
        </P>
      </Wrap>
    )
  }

  return (
    <Wrap>
      <H>Unsubscribe from the GCC newsletter</H>
      <P>
        {email ? <>You're about to unsubscribe <strong>{email}</strong>.</> : "You're about to unsubscribe from the GCC newsletter."}
        {' '}You can resubscribe anytime, and this only affects newsletter mailings — you'll still receive transactional messages tied to any sponsorships, donations, or jobs.
      </P>

      <label className="mt-6 block text-sm font-medium text-neutral-700">
        Tell us why? (optional — helps us improve)
      </label>
      <textarea
        rows={3}
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        placeholder="Too frequent · Not relevant · Got what I needed · Other"
        className="mt-2 w-full rounded-md border border-neutral-300 px-3 py-2 text-sm text-neutral-900 placeholder:text-neutral-400 focus:border-emerald-600 focus:outline-none"
      />

      {error && (
        <div className="mt-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      )}

      <button
        onClick={handleConfirm}
        disabled={status === 'submitting'}
        className="mt-6 inline-flex items-center justify-center rounded-md bg-neutral-900 px-5 py-2.5 text-sm font-medium text-white hover:bg-neutral-800 disabled:opacity-50"
      >
        {status === 'submitting' ? 'Unsubscribing…' : 'Confirm unsubscribe'}
      </button>
      <a
        href="/"
        className="ml-3 inline-flex items-center justify-center rounded-md px-4 py-2.5 text-sm text-neutral-600 hover:text-neutral-900"
      >
        Cancel
      </a>
    </Wrap>
  )
}

function Wrap({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-screen bg-neutral-50 py-16">
      <div className="mx-auto max-w-xl rounded-xl bg-white p-8 shadow">{children}</div>
    </div>
  )
}
function H({ children }: { children: React.ReactNode }) {
  return <h1 className="text-2xl font-semibold text-neutral-900">{children}</h1>
}
function P({ children }: { children: React.ReactNode }) {
  return <p className="mt-3 text-sm leading-relaxed text-neutral-600">{children}</p>
}
