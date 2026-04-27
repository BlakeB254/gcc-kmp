'use client'

import { useState, useEffect, Suspense } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'
import Link from 'next/link'
import { Logo } from '@/components/brand/Logo'
import { Eye, EyeOff, Lock, CheckCircle, Loader2 } from 'lucide-react'

function VerifyEmailContent() {
  const router = useRouter()
  const searchParams = useSearchParams()

  const success = searchParams.get('success')
  const error = searchParams.get('error')
  const workerId = searchParams.get('id')

  const [verificationCode, setVerificationCode] = useState('')
  const [isVerifying, setIsVerifying] = useState(false)
  const [verifyError, setVerifyError] = useState<string | null>(null)
  const [isVerified, setIsVerified] = useState(false)
  const [currentWorkerId, setCurrentWorkerId] = useState<string | null>(null)

  // Password state
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [isSettingPassword, setIsSettingPassword] = useState(false)
  const [passwordError, setPasswordError] = useState<string | null>(null)
  const [isComplete, setIsComplete] = useState(false)

  // Resend state
  const [isResending, setIsResending] = useState(false)
  const [resendSuccess, setResendSuccess] = useState(false)
  const [resendError, setResendError] = useState<string | null>(null)
  const [resendCooldown, setResendCooldown] = useState(0) // seconds remaining

  // Check for existing cooldown on mount
  useEffect(() => {
    const lastResend = localStorage.getItem('gcc_last_resend_time')
    if (lastResend) {
      const elapsed = Math.floor((Date.now() - parseInt(lastResend)) / 1000)
      const remaining = 300 - elapsed // 5 minutes = 300 seconds
      if (remaining > 0) {
        setResendCooldown(remaining)
      }
    }
  }, [])

  // Countdown timer for resend cooldown
  useEffect(() => {
    if (resendCooldown > 0) {
      const timer = setTimeout(() => {
        setResendCooldown(resendCooldown - 1)
      }, 1000)
      return () => clearTimeout(timer)
    }
  }, [resendCooldown])

  // Handle URL params from redirect
  useEffect(() => {
    if (success === 'verified' || success === 'already_verified') {
      setIsVerified(true)
      if (workerId) {
        setCurrentWorkerId(workerId)
      }
    }
    if (error) {
      const errorMessages: Record<string, string> = {
        invalid_link: 'Invalid verification link. Please check your email.',
        not_found: 'Account not found. Please sign up again.',
        expired: 'Verification link has expired. Please request a new one.',
        invalid_token: 'Invalid verification token.',
        failed: 'Verification failed. Please try again.',
      }
      setVerifyError(errorMessages[error] || 'An error occurred.')
    }
    // Get workerId from URL or localStorage
    if (workerId) {
      setCurrentWorkerId(workerId)
    } else {
      const stored = localStorage.getItem('gcc_pending_worker_id')
      if (stored) setCurrentWorkerId(stored)
    }
  }, [success, error, workerId])

  const handleVerifyCode = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!verificationCode || verificationCode.length !== 6) {
      setVerifyError('Please enter a 6-digit verification code')
      return
    }

    const storedWorkerId = currentWorkerId || localStorage.getItem('gcc_pending_worker_id')
    if (!storedWorkerId) {
      setVerifyError('Worker ID not found. Please use the link from your email.')
      return
    }

    setIsVerifying(true)
    setVerifyError(null)

    try {
      const response = await fetch('/api/workers/verify-email', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          code: verificationCode,
          workerId: storedWorkerId,
        }),
      })

      const data = await response.json()

      if (!response.ok) {
        throw new Error(data.error || 'Verification failed')
      }

      setIsVerified(true)
      setCurrentWorkerId(storedWorkerId)
    } catch (err) {
      setVerifyError(err instanceof Error ? err.message : 'Verification failed')
    } finally {
      setIsVerifying(false)
    }
  }

  const handleSetPassword = async (e: React.FormEvent) => {
    e.preventDefault()
    setPasswordError(null)

    if (password.length < 8) {
      setPasswordError('Password must be at least 8 characters')
      return
    }

    if (password !== confirmPassword) {
      setPasswordError('Passwords do not match')
      return
    }

    if (!currentWorkerId) {
      setPasswordError('Worker ID not found. Please try again.')
      return
    }

    setIsSettingPassword(true)

    try {
      const response = await fetch('/api/workers/set-password', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          workerId: currentWorkerId,
          password,
        }),
      })

      const data = await response.json()

      if (!response.ok) {
        throw new Error(data.error || 'Failed to set password')
      }

      // Clear stored worker ID
      localStorage.removeItem('gcc_pending_worker_id')

      // Mark as complete
      setIsComplete(true)

      // Send the worker to the document upload step before the review screen.
      setTimeout(() => {
        router.push('/worker/documents')
      }, 1500)
    } catch (err) {
      setPasswordError(err instanceof Error ? err.message : 'Failed to set password')
    } finally {
      setIsSettingPassword(false)
    }
  }

  // Complete state - password set, redirecting
  if (isComplete) {
    return (
      <div className="min-h-screen bg-gradient-to-br from-brand-green-50 via-white to-brand-green-50 flex items-center justify-center px-4">
        <div className="max-w-md w-full text-center">
          <div className="mb-8">
            <Logo variant="short" size="lg" />
          </div>

          <div className="bg-white rounded-2xl border border-brand-green-200 shadow-lg p-8 animate-fade-in-up">
            <div className="w-16 h-16 mx-auto mb-6 rounded-full bg-brand-green-100 flex items-center justify-center">
              <CheckCircle className="w-8 h-8 text-brand-green-600" />
            </div>

            <h1 className="text-2xl font-bold text-neutral-900 mb-2">
              Account Ready!
            </h1>
            <p className="text-neutral-600 mb-4">
              One more step — let's verify your identity so you can get paid.
            </p>
            <Loader2 className="w-6 h-6 animate-spin text-brand-green-500 mx-auto" />
          </div>
        </div>
      </div>
    )
  }

  // Password setup state - after email verified
  if (isVerified) {
    return (
      <div className="min-h-screen bg-gradient-to-br from-brand-green-50 via-white to-brand-green-50 flex items-center justify-center px-4">
        <div className="max-w-md w-full">
          <div className="text-center mb-8">
            <Logo variant="short" size="lg" />
          </div>

          <div className="bg-white rounded-2xl border border-brand-green-200 shadow-lg p-8">
            <div className="text-center mb-6">
              <div className="w-16 h-16 mx-auto mb-4 rounded-full bg-brand-green-100 flex items-center justify-center">
                <CheckCircle className="w-8 h-8 text-brand-green-600" />
              </div>
              <h1 className="text-2xl font-bold text-neutral-900 mb-2">
                Email Verified!
              </h1>
              <p className="text-neutral-600">
                Now create a password to secure your account.
              </p>
            </div>

            {passwordError && (
              <div className="mb-6 p-4 rounded-xl bg-red-50 border border-red-200 text-red-700 text-sm">
                {passwordError}
              </div>
            )}

            <form onSubmit={handleSetPassword} className="space-y-4">
              <div>
                <label htmlFor="password" className="block text-sm font-medium text-neutral-700 mb-2">
                  Create Password
                </label>
                <div className="relative">
                  <Lock className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-neutral-400" />
                  <input
                    type={showPassword ? 'text' : 'password'}
                    id="password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder="At least 8 characters"
                    minLength={8}
                    required
                    className="w-full pl-10 pr-10 py-3 rounded-xl border-2 border-neutral-200 focus:border-brand-green-400 focus:ring-4 focus:ring-brand-green-50 transition-all"
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword(!showPassword)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-neutral-400 hover:text-neutral-600"
                  >
                    {showPassword ? <EyeOff className="w-5 h-5" /> : <Eye className="w-5 h-5" />}
                  </button>
                </div>
              </div>

              <div>
                <label htmlFor="confirmPassword" className="block text-sm font-medium text-neutral-700 mb-2">
                  Confirm Password
                </label>
                <div className="relative">
                  <Lock className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-neutral-400" />
                  <input
                    type={showPassword ? 'text' : 'password'}
                    id="confirmPassword"
                    value={confirmPassword}
                    onChange={(e) => setConfirmPassword(e.target.value)}
                    placeholder="Confirm your password"
                    minLength={8}
                    required
                    className="w-full pl-10 py-3 rounded-xl border-2 border-neutral-200 focus:border-brand-green-400 focus:ring-4 focus:ring-brand-green-50 transition-all"
                  />
                </div>
              </div>

              <button
                type="submit"
                disabled={isSettingPassword || password.length < 8}
                className="w-full py-3 px-6 rounded-xl font-semibold text-white bg-brand-green-600 hover:bg-brand-green-700 disabled:bg-neutral-300 disabled:cursor-not-allowed transition-colors flex items-center justify-center gap-2"
              >
                {isSettingPassword ? (
                  <>
                    <Loader2 className="w-5 h-5 animate-spin" />
                    Setting up...
                  </>
                ) : (
                  'Complete Setup'
                )}
              </button>
            </form>
          </div>

          <div className="mt-6 text-center">
            <Link
              href="/"
              className="text-sm text-neutral-500 hover:text-neutral-700"
            >
              &larr; Back to Home
            </Link>
          </div>
        </div>
      </div>
    )
  }

  // Verification form
  return (
    <div className="min-h-screen bg-gradient-to-br from-brand-green-50 via-white to-brand-green-50 flex items-center justify-center px-4">
      <div className="max-w-md w-full">
        <div className="text-center mb-8">
          <Logo variant="short" size="lg" />
        </div>

        <div className="bg-white rounded-2xl border border-neutral-200 shadow-lg p-8">
          <div className="text-center mb-6">
            <div className="w-16 h-16 mx-auto mb-4 rounded-full bg-brand-green-100 flex items-center justify-center">
              <svg className="w-8 h-8 text-brand-green-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
              </svg>
            </div>
            <h1 className="text-2xl font-bold text-neutral-900 mb-2">
              Verify Your Email
            </h1>
            <p className="text-neutral-600">
              Check your inbox for a verification code or click the link in your email.
            </p>
          </div>

          {verifyError && (
            <div className="mb-6 p-4 rounded-xl bg-red-50 border border-red-200 text-red-700 text-sm">
              {verifyError}
            </div>
          )}

          <form onSubmit={handleVerifyCode} className="space-y-4">
            <div>
              <label htmlFor="code" className="block text-sm font-medium text-neutral-700 mb-2">
                Verification Code
              </label>
              <input
                type="text"
                id="code"
                value={verificationCode}
                onChange={(e) => {
                  const value = e.target.value.replace(/\D/g, '').slice(0, 6)
                  setVerificationCode(value)
                }}
                placeholder="000000"
                maxLength={6}
                className="w-full text-center text-2xl font-mono tracking-[0.5em] px-4 py-4 rounded-xl border-2 border-neutral-200 focus:border-brand-green-400 focus:ring-4 focus:ring-brand-green-50 transition-all"
                autoComplete="one-time-code"
              />
            </div>

            <button
              type="submit"
              disabled={isVerifying || verificationCode.length !== 6}
              className="w-full py-3 px-6 rounded-xl font-semibold text-white bg-brand-green-600 hover:bg-brand-green-700 disabled:bg-neutral-300 disabled:cursor-not-allowed transition-colors flex items-center justify-center gap-2"
            >
              {isVerifying ? (
                <>
                  <Loader2 className="w-5 h-5 animate-spin" />
                  Verifying...
                </>
              ) : (
                'Verify Email'
              )}
            </button>
          </form>

          <div className="mt-6 pt-6 border-t border-neutral-200 text-center">
            {resendSuccess ? (
              <div className="p-4 rounded-lg bg-green-50 border border-green-200 text-green-700 text-sm">
                <p className="font-semibold mb-1">✓ New verification email sent!</p>
                <p className="text-green-600">Check your inbox for the <strong>new 6-digit code</strong>.</p>
                {resendCooldown > 0 && (
                  <p className="mt-2 text-xs text-green-600">
                    Can resend again in {Math.floor(resendCooldown / 60)}:{(resendCooldown % 60).toString().padStart(2, '0')}
                  </p>
                )}
              </div>
            ) : (
              <>
                <p className="text-sm text-neutral-500 mb-2">
                  Didn't receive the email?
                </p>
                {resendError && (
                  <p className="text-sm text-red-600 mb-2">{resendError}</p>
                )}
                {resendCooldown > 0 ? (
                  <p className="text-sm text-neutral-400">
                    Resend available in {Math.floor(resendCooldown / 60)}:{(resendCooldown % 60).toString().padStart(2, '0')}
                  </p>
                ) : (
                  <button
                    onClick={async () => {
                      const storedWorkerId = currentWorkerId || localStorage.getItem('gcc_pending_worker_id')
                      if (!storedWorkerId) {
                        setResendError('Worker ID not found. Please sign up again.')
                        return
                      }

                      setIsResending(true)
                      setResendError(null)

                      try {
                        const response = await fetch('/api/workers/resend-verification', {
                          method: 'POST',
                          headers: { 'Content-Type': 'application/json' },
                          body: JSON.stringify({ workerId: storedWorkerId }),
                        })

                        const data = await response.json()

                        if (!response.ok) {
                          throw new Error(data.error || 'Failed to resend')
                        }

                        if (data.alreadyVerified) {
                          setIsVerified(true)
                        } else {
                          // Clear old code and show success message
                          setVerificationCode('')
                          setVerifyError(null)
                          setResendSuccess(true)
                          // Start 5-minute cooldown
                          setResendCooldown(300)
                          localStorage.setItem('gcc_last_resend_time', Date.now().toString())
                        }
                      } catch (err) {
                        setResendError(err instanceof Error ? err.message : 'Failed to resend email')
                      } finally {
                        setIsResending(false)
                      }
                    }}
                    disabled={isResending}
                    className="text-sm text-brand-green-600 hover:text-brand-green-700 font-medium disabled:text-neutral-400"
                  >
                    {isResending ? 'Sending...' : 'Resend verification email'}
                  </button>
                )}
              </>
            )}
          </div>
        </div>

        <div className="mt-6 text-center">
          <Link
            href="/"
            className="text-sm text-neutral-500 hover:text-neutral-700"
          >
            &larr; Back to Home
          </Link>
        </div>
      </div>
    </div>
  )
}

export default function VerifyEmailPage() {
  return (
    <Suspense fallback={
      <div className="min-h-screen bg-gradient-to-br from-brand-green-50 via-white to-brand-green-50 flex items-center justify-center">
        <div className="flex flex-col items-center gap-4">
          <div className="w-12 h-12 border-4 border-brand-green-500 border-t-transparent rounded-full animate-spin" />
          <p className="text-neutral-500">Loading...</p>
        </div>
      </div>
    }>
      <VerifyEmailContent />
    </Suspense>
  )
}
