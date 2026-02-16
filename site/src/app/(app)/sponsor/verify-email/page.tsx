'use client'

import { useEffect, useState, Suspense } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'
import Link from 'next/link'

function VerifyEmailContent() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const [code, setCode] = useState('')
  const [isVerifying, setIsVerifying] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState(false)
  const [autoVerified, setAutoVerified] = useState(false)

  const sponsorId = searchParams.get('id')
  const token = searchParams.get('token')

  // Auto-verify if token is present in URL
  useEffect(() => {
    if (token && sponsorId && !autoVerified) {
      setAutoVerified(true)
      verifyWithToken(token)
    }
  }, [token, sponsorId])

  async function verifyWithToken(verificationToken: string) {
    setIsVerifying(true)
    setError(null)

    try {
      const response = await fetch('/api/sponsors/verify-email', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          sponsorId: parseInt(sponsorId!, 10),
          token: verificationToken,
        }),
      })

      const data = await response.json()

      if (!response.ok) {
        throw new Error(data.error || 'Verification failed')
      }

      setSuccess(true)
      // Redirect to dashboard after a brief delay
      setTimeout(() => {
        router.push('/sponsor/dashboard')
      }, 2000)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Verification failed')
    } finally {
      setIsVerifying(false)
    }
  }

  async function handleCodeSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!code || !sponsorId) return

    setIsVerifying(true)
    setError(null)

    try {
      const response = await fetch('/api/sponsors/verify-email', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          sponsorId: parseInt(sponsorId, 10),
          code: code.trim(),
        }),
      })

      const data = await response.json()

      if (!response.ok) {
        throw new Error(data.error || 'Verification failed')
      }

      setSuccess(true)
      // Redirect to dashboard after a brief delay
      setTimeout(() => {
        router.push('/sponsor/dashboard')
      }, 2000)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Verification failed')
    } finally {
      setIsVerifying(false)
    }
  }

  if (!sponsorId) {
    return (
      <div className="bg-gradient-to-b from-brand-brown-50 to-white min-h-[70vh]">
        <div className="section-container py-12">
          <div className="max-w-md mx-auto text-center">
            <div className="bg-white rounded-2xl border border-neutral-200 shadow-lg p-8">
              <div className="w-16 h-16 mx-auto mb-4 rounded-full bg-red-100 flex items-center justify-center">
                <svg className="w-8 h-8 text-red-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
                </svg>
              </div>
              <h1 className="text-xl font-bold text-neutral-900 mb-2">Invalid Link</h1>
              <p className="text-neutral-600 mb-6">
                This verification link appears to be incomplete. Please use the link from your email or sign up again.
              </p>
              <Link
                href="/sponsor/signup"
                className="inline-block px-6 py-3 bg-brand-gold-500 text-white font-semibold rounded-xl hover:bg-brand-gold-600 transition-all"
              >
                Sign Up Again
              </Link>
            </div>
          </div>
        </div>
      </div>
    )
  }

  if (success) {
    return (
      <div className="bg-gradient-to-b from-brand-brown-50 to-white min-h-[70vh]">
        <div className="section-container py-12">
          <div className="max-w-md mx-auto text-center">
            <div className="bg-white rounded-2xl border border-neutral-200 shadow-lg p-8">
              <div className="w-20 h-20 mx-auto mb-6 rounded-full bg-brand-green-100 flex items-center justify-center">
                <svg className="w-10 h-10 text-brand-green-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                </svg>
              </div>
              <h1 className="text-2xl font-bold text-neutral-900 mb-2">Email Verified!</h1>
              <p className="text-neutral-600 mb-6">
                Your email has been verified successfully. Redirecting you to your dashboard...
              </p>
              <div className="flex justify-center">
                <div className="w-8 h-8 border-4 border-brand-gold-500 border-t-transparent rounded-full animate-spin" />
              </div>
            </div>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="bg-gradient-to-b from-brand-brown-50 to-white min-h-[70vh]">
      <div className="section-container py-12">
        <div className="max-w-md mx-auto">
          <div className="text-center mb-8">
            <div className="w-20 h-20 mx-auto mb-6 rounded-full bg-brand-gold-100 flex items-center justify-center">
              <svg className="w-10 h-10 text-brand-gold-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
              </svg>
            </div>
            <h1 className="text-2xl font-bold text-neutral-900 mb-2">Check Your Email</h1>
            <p className="text-neutral-600">
              We've sent a verification email to your inbox. Click the link in the email or enter the 6-digit code below.
            </p>
          </div>

          {isVerifying && token ? (
            <div className="bg-white rounded-2xl border border-neutral-200 shadow-lg p-8 text-center">
              <div className="w-12 h-12 mx-auto mb-4 border-4 border-brand-gold-500 border-t-transparent rounded-full animate-spin" />
              <p className="text-neutral-600">Verifying your email...</p>
            </div>
          ) : (
            <form onSubmit={handleCodeSubmit} className="bg-white rounded-2xl border border-neutral-200 shadow-lg p-6 space-y-4">
              <div>
                <label className="block text-sm font-medium text-neutral-700 mb-2">
                  Verification Code
                </label>
                <input
                  type="text"
                  maxLength={6}
                  value={code}
                  onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
                  className="input-field text-center text-2xl tracking-widest font-mono"
                  placeholder="000000"
                  autoComplete="one-time-code"
                />
                <p className="text-sm text-neutral-500 mt-2">
                  Enter the 6-digit code from your verification email
                </p>
              </div>

              {error && (
                <div className="p-4 bg-red-50 border border-red-200 rounded-xl text-red-700 text-sm">
                  {error}
                </div>
              )}

              <button
                type="submit"
                disabled={code.length !== 6 || isVerifying}
                className={`w-full py-3 px-6 rounded-xl font-semibold transition-all ${
                  code.length === 6 && !isVerifying
                    ? 'bg-brand-gold-500 text-white hover:bg-brand-gold-600'
                    : 'bg-neutral-200 text-neutral-400 cursor-not-allowed'
                }`}
              >
                {isVerifying ? 'Verifying...' : 'Verify Email'}
              </button>

              <div className="border-t pt-4">
                <p className="text-sm text-neutral-500 text-center">
                  Didn't receive the email?{' '}
                  <button
                    type="button"
                    className="text-brand-gold-600 font-medium hover:underline"
                    onClick={() => {
                      // TODO: Implement resend verification email
                      alert('Please check your spam folder or contact support.')
                    }}
                  >
                    Resend
                  </button>
                </p>
              </div>
            </form>
          )}

          <p className="text-center text-neutral-500 text-sm mt-6">
            Wrong email?{' '}
            <Link href="/sponsor/signup" className="text-brand-gold-600 hover:underline">
              Sign up with a different email
            </Link>
          </p>
        </div>
      </div>
    </div>
  )
}

export default function SponsorVerifyEmailPage() {
  return (
    <Suspense fallback={
      <div className="bg-gradient-to-b from-brand-brown-50 to-white min-h-[70vh]">
        <div className="section-container py-12">
          <div className="max-w-md mx-auto text-center">
            <div className="w-12 h-12 mx-auto border-4 border-brand-gold-500 border-t-transparent rounded-full animate-spin" />
          </div>
        </div>
      </div>
    }>
      <VerifyEmailContent />
    </Suspense>
  )
}
