'use client'

import { useState, Suspense } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'
import Link from 'next/link'
import { Logo } from '@/components/brand/Logo'
import { FormInput } from '@/components/forms/FormInput'
import { LoadingFallback } from '@/components/shared/LoadingFallback'
import { cn } from '@/lib/utils'

interface UniversalLoginResponse {
  success: boolean
  accountType?: 'worker' | 'sponsor'
  roles?: Array<'worker' | 'sponsor' | 'admin'>
  token?: string
  user?: {
    verificationStatus?: string
    documents?: {
      governmentIdUploaded?: boolean
      proofOfAddressUploaded?: boolean
    }
  }
  error?: string
  code?: string
  workerId?: number
  sponsorId?: number
}

function LoginContent() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const next = searchParams.get('next')

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  function landingFor(data: UniversalLoginResponse): string {
    if (next && next.startsWith('/')) return next
    if (data.roles?.includes('admin')) return '/admin/routes'
    if (data.accountType === 'sponsor') return '/sponsor/dashboard'
    if (data.accountType === 'worker') {
      // Steer pending workers through the doc-upload checkpoint.
      const docs = data.user?.documents
      const allUploaded = docs?.governmentIdUploaded && docs?.proofOfAddressUploaded
      if (!allUploaded) return '/worker/documents'
      if (data.user?.verificationStatus !== 'approved') return '/worker/pending'
      return '/worker/dashboard'
    }
    return '/'
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    setLoading(true)

    try {
      const res = await fetch('/api/auth/universal-login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ email, password }),
      })
      const data: UniversalLoginResponse = await res.json()

      if (!res.ok || !data.success) {
        // Special-case: email not verified — bounce to the right verify-email page.
        if (data.code === 'EMAIL_NOT_VERIFIED') {
          if (data.workerId) router.push(`/worker/verify-email?id=${data.workerId}`)
          else if (data.sponsorId) router.push(`/sponsor/verify-email?id=${data.sponsorId}`)
          else setError(data.error || 'Please verify your email before signing in.')
          return
        }
        setError(data.error || 'Sign-in failed.')
        return
      }

      // Stash the Bearer token for the admin panel's localStorage check.
      if (data.token) {
        try {
          localStorage.setItem('adminToken', data.token)
        } catch {
          // localStorage unavailable (private mode) — admin panel will need to refresh.
        }
      }

      router.push(landingFor(data))
    } catch {
      setError('Network error. Please try again.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-brand-green-50 via-white to-brand-green-50 py-golden-lg sm:py-golden-xl px-4">
      <div className="max-w-md mx-auto">
        <div className="text-center mb-golden-md sm:mb-golden-lg">
          <div className="mb-golden-sm sm:mb-golden-md">
            <Logo size="md" className="mx-auto" />
          </div>
          <h1 className="text-2xl sm:text-3xl font-bold text-neutral-900 mb-2">Sign In</h1>
          <p className="text-neutral-600">Workers, sponsors, and admins — one login.</p>
        </div>

        <form
          onSubmit={handleSubmit}
          className="bg-white rounded-2xl shadow-lg border border-neutral-200 p-6 sm:p-8 space-y-5"
        >
          <FormInput
            label="Email"
            id="email"
            type="email"
            value={email}
            onChange={setEmail}
            placeholder="you@example.com"
            autoComplete="email"
          />

          <FormInput
            label="Password"
            id="password"
            type="password"
            value={password}
            onChange={setPassword}
            placeholder="Your password"
            autoComplete="current-password"
          />

          {error && (
            <div className="p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700">
              {error}
            </div>
          )}

          <button
            type="submit"
            disabled={loading || !email || !password}
            className={cn(
              'w-full py-3 px-4 rounded-xl font-semibold text-white transition-all',
              loading || !email || !password
                ? 'bg-neutral-400 cursor-not-allowed'
                : 'bg-brand-green-600 hover:bg-brand-green-700 active:bg-brand-green-800 shadow-md hover:shadow-lg',
            )}
          >
            {loading ? 'Signing in…' : 'Sign In'}
          </button>

          <div className="flex items-center justify-between text-sm">
            <Link href="/worker/forgot-password" className="text-brand-green-700 hover:underline">
              Forgot password?
            </Link>
            <Link href="/worker/signup" className="text-brand-green-700 font-medium hover:underline">
              Create account
            </Link>
          </div>
        </form>

        <p className="text-center text-xs text-neutral-400 mt-4">
          Sponsors can register at{' '}
          <Link href="/sponsor/signup" className="underline hover:text-neutral-600">
            /sponsor/signup
          </Link>
          .
        </p>
      </div>
    </div>
  )
}

export default function LoginPage() {
  return (
    <Suspense fallback={<LoadingFallback />}>
      <LoginContent />
    </Suspense>
  )
}
