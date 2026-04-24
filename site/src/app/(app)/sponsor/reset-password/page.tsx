'use client'

import { useState, useEffect, Suspense } from 'react'
import { useSearchParams, useRouter } from 'next/navigation'
import Link from 'next/link'
import { Logo } from '@/components/brand/Logo'
import { Lock, Eye, EyeOff, CheckCircle, AlertCircle, ArrowLeft, Loader2 } from 'lucide-react'

function ResetPasswordContent() {
  const searchParams = useSearchParams()
  const router = useRouter()
  const token = searchParams.get('token')

  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [isLoading, setIsLoading] = useState(false)
  const [isVerifying, setIsVerifying] = useState(true)
  const [isSuccess, setIsSuccess] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [tokenError, setTokenError] = useState<string | null>(null)

  // Verify token on mount
  useEffect(() => {
    async function verifyToken() {
      if (!token) {
        setTokenError('No reset token provided. Please request a new password reset.')
        setIsVerifying(false)
        return
      }

      try {
        const response = await fetch(`/api/sponsors/reset-password?token=${token}`)
        const data = await response.json()

        if (!data.valid) {
          setTokenError(data.error || 'Invalid or expired reset link.')
        }
      } catch {
        setTokenError('Failed to verify reset link. Please try again.')
      } finally {
        setIsVerifying(false)
      }
    }

    verifyToken()
  }, [token])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)

    // Validate passwords match
    if (password !== confirmPassword) {
      setError('Passwords do not match')
      return
    }

    // Validate password length
    if (password.length < 8) {
      setError('Password must be at least 8 characters long')
      return
    }

    setIsLoading(true)

    try {
      const response = await fetch('/api/sponsors/reset-password', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token, password }),
      })

      const data = await response.json()

      if (!response.ok) {
        throw new Error(data.error || 'Failed to reset password')
      }

      setIsSuccess(true)

      // Redirect to login after 3 seconds
      setTimeout(() => {
        router.push('/sponsor/login')
      }, 3000)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to reset password')
    } finally {
      setIsLoading(false)
    }
  }

  // Loading state while verifying token
  if (isVerifying) {
    return (
      <div className="min-h-screen bg-gradient-to-b from-brand-green-50 to-white flex items-center justify-center px-4">
        <div className="text-center">
          <Loader2 className="w-8 h-8 animate-spin text-brand-green-500 mx-auto mb-4" />
          <p className="text-neutral-600">Verifying reset link...</p>
        </div>
      </div>
    )
  }

  // Token error state
  if (tokenError) {
    return (
      <div className="min-h-screen bg-gradient-to-b from-brand-green-50 to-white flex items-center justify-center px-4">
        <div className="w-full max-w-md">
          <div className="text-center mb-8">
            <Link href="/" className="inline-block">
              <Logo variant="full" size="md" linkToHome={false} />
            </Link>
          </div>

          <div className="bg-white rounded-2xl border border-neutral-200 shadow-lg p-6 text-center">
            <div className="w-16 h-16 bg-red-100 rounded-full flex items-center justify-center mx-auto mb-4">
              <AlertCircle className="w-8 h-8 text-red-600" />
            </div>
            <h1 className="text-2xl font-bold text-neutral-900 mb-2">
              Link Expired
            </h1>
            <p className="text-neutral-600 mb-6">
              {tokenError}
            </p>
            <Link
              href="/sponsor/forgot-password"
              className="btn-primary inline-flex items-center gap-2 px-6 py-3"
            >
              Request New Link
            </Link>
          </div>

          <div className="text-center mt-6">
            <Link href="/sponsor/login" className="text-sm text-neutral-500 hover:text-neutral-700">
              &larr; Back to login
            </Link>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-gradient-to-b from-brand-green-50 to-white flex items-center justify-center px-4">
      <div className="w-full max-w-md">
        {/* Logo */}
        <div className="text-center mb-8">
          <Link href="/" className="inline-block">
            <Logo variant="full" size="md" />
          </Link>
        </div>

        {/* Card */}
        <div className="bg-white rounded-2xl border border-neutral-200 shadow-lg p-6">
          {isSuccess ? (
            <div className="text-center py-4">
              <div className="w-16 h-16 bg-brand-green-100 rounded-full flex items-center justify-center mx-auto mb-4">
                <CheckCircle className="w-8 h-8 text-brand-green-600" />
              </div>
              <h1 className="text-2xl font-bold text-neutral-900 mb-2">
                Password Reset!
              </h1>
              <p className="text-neutral-600 mb-6">
                Your password has been reset successfully. Redirecting you to login...
              </p>
              <Link
                href="/sponsor/login"
                className="btn-primary inline-flex items-center gap-2 px-6 py-3"
              >
                Go to Login
              </Link>
            </div>
          ) : (
            <>
              <h1 className="text-2xl font-bold text-neutral-900 mb-2 text-center">
                Create New Password
              </h1>
              <p className="text-neutral-600 text-center mb-6">
                Enter your new password below.
              </p>

              {error && (
                <div className="mb-6 p-4 bg-red-50 border border-red-200 rounded-xl text-red-700 text-sm">
                  {error}
                </div>
              )}

              <form onSubmit={handleSubmit} className="space-y-4">
                <div>
                  <label className="block text-sm font-medium text-neutral-700 mb-1">
                    New Password
                  </label>
                  <div className="relative">
                    <Lock className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-neutral-400" />
                    <input
                      type={showPassword ? 'text' : 'password'}
                      required
                      minLength={8}
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      className="input-field pl-10 pr-10"
                      placeholder="At least 8 characters"
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
                  <label className="block text-sm font-medium text-neutral-700 mb-1">
                    Confirm Password
                  </label>
                  <div className="relative">
                    <Lock className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-neutral-400" />
                    <input
                      type={showPassword ? 'text' : 'password'}
                      required
                      minLength={8}
                      value={confirmPassword}
                      onChange={(e) => setConfirmPassword(e.target.value)}
                      className="input-field pl-10"
                      placeholder="Confirm your password"
                    />
                  </div>
                </div>

                <button
                  type="submit"
                  disabled={isLoading}
                  className="w-full py-3 px-6 rounded-xl font-semibold bg-brand-green-500 text-white hover:bg-brand-green-600 transition-all disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {isLoading ? (
                    <>
                      <Loader2 className="w-5 h-5 animate-spin" />
                      Resetting...
                    </>
                  ) : (
                    'Reset Password'
                  )}
                </button>
              </form>

              <div className="mt-6 pt-6 border-t border-neutral-200 text-center">
                <Link
                  href="/sponsor/login"
                  className="inline-flex items-center gap-2 text-neutral-600 hover:text-neutral-900"
                >
                  <ArrowLeft className="w-4 h-4" />
                  Back to Login
                </Link>
              </div>
            </>
          )}
        </div>

        {/* Back to home */}
        <div className="text-center mt-6">
          <Link href="/" className="text-sm text-neutral-500 hover:text-neutral-700">
            &larr; Back to homepage
          </Link>
        </div>
      </div>
    </div>
  )
}

function LoadingFallback() {
  return (
    <div className="min-h-screen bg-gradient-to-b from-brand-green-50 to-white flex items-center justify-center px-4">
      <div className="text-center">
        <Loader2 className="w-8 h-8 animate-spin text-brand-green-500 mx-auto mb-4" />
        <p className="text-neutral-600">Loading...</p>
      </div>
    </div>
  )
}

export default function SponsorResetPasswordPage() {
  return (
    <Suspense fallback={<LoadingFallback />}>
      <ResetPasswordContent />
    </Suspense>
  )
}
