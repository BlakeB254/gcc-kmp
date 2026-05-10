'use client'

import { useState, Suspense } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'
import Image from 'next/image'
import Link from 'next/link'
import { Logo } from '@/components/brand/Logo'
import { SocialLoginButtons } from '@/components/auth/SocialLoginButtons'
import { LoadingFallback } from '@/components/shared/LoadingFallback'
import { Mail, Lock, Eye, EyeOff, Loader2, AlertCircle } from 'lucide-react'

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
  reason?: string
  workerId?: number
  sponsorId?: number
}

interface LoginErrorState {
  message: string
  code?: string
  workerId?: number
  sponsorId?: number
  reason?: string
}

function oauthErrorMessage(code: string | null): string | null {
  if (!code) return null
  const messages: Record<string, string> = {
    google_not_configured: 'Google sign-in is not configured for the website yet.',
    apple_not_configured: 'Apple sign-in is not configured for the website yet.',
    google_cancelled: 'Google sign-in was cancelled.',
    apple_cancelled: 'Apple sign-in was cancelled.',
    google_failed: 'Google sign-in failed. Please try again or use email.',
    apple_failed: 'Apple sign-in failed. Please try again or use email.',
    missing_code: 'The sign-in provider did not return the expected authorization code.',
    invalid_request: 'That sign-in request was invalid. Please try again.',
  }
  return messages[code] || 'Social sign-in failed. Please try again or use email.'
}

function LoginContent() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const next = searchParams.get('next')

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<LoginErrorState | null>(null)
  const oauthError = oauthErrorMessage(searchParams.get('oauthError'))
  const displayError: LoginErrorState | null = error || (oauthError ? { message: oauthError } : null)

  function landingFor(_data: UniversalLoginResponse): string {
    // Honor explicit ?next= (e.g., a deep-link to /admin/routes); otherwise
    // every successful sign-in lands in the unified /member portal where
    // worker and sponsor sections render side by side.
    if (next && next.startsWith('/')) return next
    return '/member'
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
        body: JSON.stringify({ email: email.trim(), password }),
      })
      const data: UniversalLoginResponse = await res.json()

      if (!res.ok || !data.success) {
        setError({
          message: data.error || 'Sign-in failed.',
          code: data.code,
          workerId: data.workerId,
          sponsorId: data.sponsorId,
          reason: data.reason,
        })
        return
      }

      if (data.token) {
        try {
          localStorage.setItem('adminToken', data.token)
        } catch {
          // localStorage unavailable (private mode) — admin panel will need a refresh.
        }
      }

      router.push(landingFor(data))
    } catch {
      setError({ message: 'Network error. Please try again.' })
    } finally {
      setLoading(false)
    }
  }

  const errorTone =
    displayError?.code === 'EMAIL_NOT_VERIFIED'
      ? 'bg-amber-50 border-amber-200 text-amber-800'
      : 'bg-red-50 border-red-200 text-red-700'

  return (
    <div className="min-h-screen flex bg-white">
      {/* Left side — community image (desktop only) */}
      <div className="hidden lg:block lg:w-1/2 relative">
        <picture>
          <source
            srcSet="/images/optimized/community-street-tablet.webp"
            type="image/webp"
          />
          <Image
            src="/images/optimized/community-street-tablet.jpg"
            alt="Global Community Cleaners workers"
            fill
            className="object-cover"
            sizes="50vw"
            priority
          />
        </picture>
        <div className="absolute inset-0 bg-gradient-to-r from-brand-green-900/80 via-brand-green-900/50 to-transparent" />
        <div className="absolute bottom-12 left-12 right-12 max-w-md">
          <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-white/15 backdrop-blur-sm border border-white/20 text-white text-xs font-medium uppercase tracking-wider mb-5">
            One sign-in for everyone
          </div>
          <h2 className="text-3xl xl:text-4xl font-bold text-white leading-tight mb-4">
            Welcome back to GCC.
          </h2>
          <p className="text-neutral-200 text-base leading-relaxed">
            Workers, sponsors, and admins — same email, same password, one account.
            Pick up exactly where you left off.
          </p>
        </div>
      </div>

      {/* Right side — form */}
      <div className="w-full lg:w-1/2 flex items-center justify-center bg-gradient-to-br from-brand-green-50 via-white to-brand-green-50 p-6 sm:p-10">
        <div className="w-full max-w-md">
          <div className="mb-8 sm:mb-10">
            <Link href="/" className="inline-block">
              <Logo variant="full" size="md" linkToHome={false} />
            </Link>
          </div>

          <h1 className="text-3xl font-bold text-neutral-900 mb-2 tracking-tight">
            Sign in
          </h1>
          <p className="text-neutral-600 mb-8">
            Enter your email and password to access your dashboard.
          </p>

          <div className="mb-6">
            <SocialLoginButtons mode="signin" returnTo={next && next.startsWith('/') ? next : '/member'} />
            <div className="relative my-6">
              <div className="absolute inset-0 flex items-center">
                <div className="w-full border-t border-neutral-200" />
              </div>
              <div className="relative flex justify-center text-xs uppercase">
                <span className="bg-white px-3 text-neutral-500">or sign in with email</span>
              </div>
            </div>
          </div>

          {displayError && (
            <div className={`mb-6 p-4 rounded-xl border text-sm ${errorTone}`}>
              <div className="flex items-start gap-2">
                <AlertCircle className="w-4 h-4 flex-shrink-0 mt-0.5" />
                <div className="flex-1">
                  <p className="font-medium">{displayError.message}</p>
                  {displayError.code === 'EMAIL_NOT_VERIFIED' && (displayError.workerId || displayError.sponsorId) && (
                    <Link
                      href={
                        displayError.workerId
                          ? `/worker/verify-email?id=${displayError.workerId}`
                          : `/sponsor/verify-email?id=${displayError.sponsorId}`
                      }
                      className="inline-flex items-center gap-2 mt-3 px-4 py-2 rounded-lg bg-amber-100 hover:bg-amber-200 text-amber-900 font-medium transition-colors text-sm"
                    >
                      <Mail className="w-4 h-4" />
                      Verify your email
                    </Link>
                  )}
                  {displayError.code === 'VERIFICATION_REJECTED' && displayError.reason && (
                    <p className="mt-2 text-xs opacity-90">{displayError.reason}</p>
                  )}
                </div>
              </div>
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-5">
            <div>
              <label htmlFor="email" className="block text-sm font-medium text-neutral-700 mb-2">
                Email
              </label>
              <div className="relative">
                <Mail className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-neutral-400 pointer-events-none" />
                <input
                  type="email"
                  id="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="you@example.com"
                  required
                  autoComplete="email"
                  autoCapitalize="off"
                  spellCheck={false}
                  className="w-full pl-10 pr-4 py-3 rounded-xl border-2 border-neutral-200 bg-white focus:border-brand-green-400 focus:ring-4 focus:ring-brand-green-50 transition-all"
                />
              </div>
            </div>

            <div>
              <div className="flex items-center justify-between mb-2">
                <label htmlFor="password" className="block text-sm font-medium text-neutral-700">
                  Password
                </label>
                <Link
                  href="/worker/forgot-password"
                  className="text-xs text-brand-green-700 hover:text-brand-green-800 hover:underline"
                >
                  Forgot password?
                </Link>
              </div>
              <div className="relative">
                <Lock className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-neutral-400 pointer-events-none" />
                <input
                  type={showPassword ? 'text' : 'password'}
                  id="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="Your password"
                  required
                  autoComplete="current-password"
                  className="w-full pl-10 pr-11 py-3 rounded-xl border-2 border-neutral-200 bg-white focus:border-brand-green-400 focus:ring-4 focus:ring-brand-green-50 transition-all"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword(!showPassword)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-neutral-400 hover:text-neutral-600"
                  aria-label={showPassword ? 'Hide password' : 'Show password'}
                  tabIndex={-1}
                >
                  {showPassword ? <EyeOff className="w-5 h-5" /> : <Eye className="w-5 h-5" />}
                </button>
              </div>
            </div>

            <button
              type="submit"
              disabled={loading || !email || !password}
              className="w-full py-3.5 px-6 rounded-xl font-semibold text-white bg-brand-green-600 hover:bg-brand-green-700 active:bg-brand-green-800 disabled:bg-neutral-300 disabled:cursor-not-allowed transition-all shadow-md hover:shadow-lg flex items-center justify-center gap-2"
            >
              {loading ? (
                <>
                  <Loader2 className="w-5 h-5 animate-spin" />
                  Signing in…
                </>
              ) : (
                'Sign in'
              )}
            </button>
          </form>

          <div className="mt-8 pt-6 border-t border-neutral-200 text-center text-sm text-neutral-600">
            New to GCC?{' '}
            <Link href="/worker/signup" className="font-semibold text-brand-green-700 hover:underline">
              Sign up to earn
            </Link>
            {' · '}
            <Link href="/sponsor/signup" className="font-semibold text-brand-green-700 hover:underline">
              Sponsor a block
            </Link>
          </div>

          <div className="mt-6 text-center">
            <Link href="/" className="text-xs text-neutral-500 hover:text-neutral-700">
              &larr; Back to homepage
            </Link>
          </div>
        </div>
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
