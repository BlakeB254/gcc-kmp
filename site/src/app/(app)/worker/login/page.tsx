'use client'

import { useState } from 'react'
import Link from 'next/link'
import Image from 'next/image'
import { useRouter } from 'next/navigation'
import { Logo } from '@/components/brand/Logo'
import { useAuthForm } from '@/hooks/useAuthForm'

interface LoginError {
  message: string
  code?: string
  workerId?: string
  reason?: string
}

export default function WorkerLoginPage() {
  const router = useRouter()
  const [detailedError, setDetailedError] = useState<LoginError | null>(null)

  const { formData, updateField, isLoading, error, handleSubmit: baseSubmit, setError } = useAuthForm({
    apiEndpoint: '/api/workers/login',
    successRedirect: '/worker/dashboard',
  })

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setDetailedError(null)
    setError(null)

    const { formData: fd, updateField: uf, ...rest } = { formData, updateField }

    // Manual submission to handle special error codes
    try {
      const res = await fetch('/api/workers/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: formData.email || '', password: formData.password || '' }),
      })

      const data = await res.json()

      if (!res.ok) {
        if (data.code === 'EMAIL_NOT_VERIFIED') {
          setDetailedError({
            message: data.error,
            code: data.code,
            workerId: data.workerId,
          })
          return
        }
        if (data.code === 'VERIFICATION_REJECTED') {
          setDetailedError({
            message: data.error,
            code: data.code,
            reason: data.reason,
          })
          return
        }
        setDetailedError({ message: data.error || 'Login failed' })
        return
      }

      router.push('/worker/dashboard')
    } catch (err) {
      setDetailedError({ message: err instanceof Error ? err.message : 'Login failed' })
    }
  }

  const displayError = detailedError || (error ? { message: error } : null)

  return (
    <div className="min-h-screen flex">
      {/* Left side - Image - optimized */}
      <div className="hidden lg:block lg:w-1/2 relative">
        <picture>
          <source
            srcSet="/images/optimized/community-street-tablet.webp"
            type="image/webp"
          />
          <Image
            src="/images/optimized/community-street-tablet.jpg"
            alt="North Lawndale community"
            fill
            className="object-cover"
            sizes="50vw"
          />
        </picture>
        <div className="absolute inset-0 bg-gradient-to-r from-black/60 to-transparent" />
        <div className="absolute bottom-12 left-12 max-w-md">
          <h2 className="text-3xl font-bold text-white mb-4">
            Welcome back, neighbor.
          </h2>
          <p className="text-neutral-200">
            Ready to make a difference? Log in to see available blocks and continue earning.
          </p>
        </div>
      </div>

      {/* Right side - Form */}
      <div className="w-full lg:w-1/2 flex items-center justify-center p-8">
        <div className="w-full max-w-md">
          {/* Logo */}
          <div className="mb-8">
            <Logo variant="full" size="md" />
          </div>

          <h1 className="text-2xl font-bold text-neutral-900 mb-2">
            Worker Login
          </h1>
          <p className="text-neutral-600 mb-8">
            Enter your credentials to access your dashboard
          </p>

          {displayError && (
            <div className={`mb-6 p-4 rounded-xl border text-sm ${
              detailedError?.code === 'EMAIL_NOT_VERIFIED'
                ? 'bg-amber-50 border-amber-200 text-amber-800'
                : detailedError?.code === 'VERIFICATION_REJECTED'
                ? 'bg-red-50 border-red-200 text-red-700'
                : 'bg-red-50 border-red-200 text-red-700'
            }`}>
              <p className="font-medium">{displayError.message}</p>

              {detailedError?.code === 'EMAIL_NOT_VERIFIED' && detailedError.workerId && (
                <div className="mt-3">
                  <Link
                    href={`/worker/verify-email?id=${detailedError.workerId}`}
                    className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-amber-100 hover:bg-amber-200 text-amber-900 font-medium transition-colors"
                  >
                    <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
                    </svg>
                    Verify Your Email
                  </Link>
                </div>
              )}

              {detailedError?.code === 'VERIFICATION_REJECTED' && detailedError.reason && (
                <p className="mt-2 text-xs">{detailedError.reason}</p>
              )}
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-6">
            <div>
              <label htmlFor="email" className="block text-sm font-medium text-neutral-700 mb-2">
                Email Address
              </label>
              <input
                id="email"
                type="email"
                required
                value={formData.email || ''}
                onChange={(e) => updateField('email', e.target.value)}
                className="input-field"
                placeholder="you@example.com"
              />
            </div>

            <div>
              <label htmlFor="password" className="block text-sm font-medium text-neutral-700 mb-2">
                Password
              </label>
              <input
                id="password"
                type="password"
                required
                value={formData.password || ''}
                onChange={(e) => updateField('password', e.target.value)}
                className="input-field"
                placeholder="Enter your password"
              />
            </div>

            <div className="flex items-center justify-between">
              <label className="flex items-center gap-2">
                <input type="checkbox" className="rounded border-neutral-300" />
                <span className="text-sm text-neutral-600">Remember me</span>
              </label>
              <Link href="/worker/forgot-password" className="text-sm text-brand-green-600 hover:underline">
                Forgot password?
              </Link>
            </div>

            <button
              type="submit"
              disabled={isLoading}
              className="btn-primary w-full py-3 disabled:opacity-50"
            >
              {isLoading ? 'Signing in...' : 'Sign In'}
            </button>
          </form>

          <p className="mt-8 text-center text-neutral-600">
            Don&apos;t have an account?{' '}
            <Link href="/worker/signup" className="text-brand-green-600 font-medium hover:underline">
              Sign up to start earning
            </Link>
          </p>

          {/* Back to home */}
          <div className="mt-8 pt-8 border-t border-neutral-200 text-center">
            <Link href="/" className="text-sm text-neutral-500 hover:text-neutral-700">
              &larr; Back to homepage
            </Link>
          </div>
        </div>
      </div>
    </div>
  )
}
