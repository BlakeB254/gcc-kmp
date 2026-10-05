'use client'

import { useEffect, useState, Suspense } from 'react'
import { useSearchParams, useRouter } from 'next/navigation'
import Link from 'next/link'

function VerifyContent() {
  const searchParams = useSearchParams()
  const router = useRouter()
  const token = searchParams.get('token')

  const [status, setStatus] = useState<'verifying' | 'success' | 'error' | 'new_user'>('verifying')
  const [message, setMessage] = useState('')
  const [registrationToken, setRegistrationToken] = useState('')

  useEffect(() => {
    if (!token) {
      setStatus('error')
      setMessage('Invalid link. Please request a new sign-in link.')
      return
    }

    async function verify() {
      try {
        const response = await fetch('/api/auth/verify-magic-link', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ token }),
        })

        const data = await response.json()

        if (!response.ok) {
          setStatus('error')
          setMessage(data.error || 'Verification failed')
          return
        }

        if (data.isNewUser) {
          setStatus('new_user')
          setMessage('Email verified! Complete your registration to continue.')
          setRegistrationToken(data.registrationToken)
        } else {
          setStatus('success')
          setMessage('Signed in successfully!')
          // Redirect to dashboard after a moment
          setTimeout(() => {
            router.push('/worker/dashboard')
          }, 1500)
        }

      } catch (err) {
        setStatus('error')
        setMessage('Something went wrong. Please try again.')
      }
    }

    verify()
  }, [token, router])

  return (
    <div className="min-h-screen bg-neutral-50 flex items-center justify-center p-4">
      <div className="max-w-md w-full bg-white rounded-2xl shadow-lg p-8 text-center">
        {status === 'verifying' && (
          <>
            <div className="w-16 h-16 border-4 border-brand-green-500 border-t-transparent rounded-full animate-spin mx-auto mb-6" />
            <h1 className="text-2xl font-bold text-neutral-900 mb-2">Verifying...</h1>
            <p className="text-neutral-600">Please wait while we sign you in.</p>
          </>
        )}

        {status === 'success' && (
          <>
            <div className="w-16 h-16 bg-green-100 rounded-full flex items-center justify-center mx-auto mb-6">
              <svg className="w-8 h-8 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
              </svg>
            </div>
            <h1 className="text-2xl font-bold text-neutral-900 mb-2">Welcome Back!</h1>
            <p className="text-neutral-600 mb-6">{message}</p>
            <p className="text-sm text-neutral-500">Redirecting to your dashboard...</p>
          </>
        )}

        {status === 'new_user' && (
          <>
            <div className="w-16 h-16 bg-brand-green-100 rounded-full flex items-center justify-center mx-auto mb-6">
              <svg className="w-8 h-8 text-brand-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z" />
              </svg>
            </div>
            <h1 className="text-2xl font-bold text-neutral-900 mb-2">Email Verified!</h1>
            <p className="text-neutral-600 mb-6">{message}</p>
            <Link
              href={`/worker/signup?token=${registrationToken}`}
              className="inline-block w-full py-3 px-6 bg-brand-green-500 text-brand-green-900 font-semibold rounded-xl hover:bg-brand-green-400 transition-colors"
            >
              Complete Registration
            </Link>
          </>
        )}

        {status === 'error' && (
          <>
            <div className="w-16 h-16 bg-red-100 rounded-full flex items-center justify-center mx-auto mb-6">
              <svg className="w-8 h-8 text-red-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
              </svg>
            </div>
            <h1 className="text-2xl font-bold text-neutral-900 mb-2">Verification Failed</h1>
            <p className="text-neutral-600 mb-6">{message}</p>
            <Link
              href="/worker/login"
              className="inline-block w-full py-3 px-6 bg-brand-green-500 text-brand-green-900 font-semibold rounded-xl hover:bg-brand-green-400 transition-colors"
            >
              Request New Link
            </Link>
          </>
        )}
      </div>
    </div>
  )
}

function LoadingSpinner() {
  return (
    <div className="min-h-screen bg-neutral-50 flex items-center justify-center p-4">
      <div className="max-w-md w-full bg-white rounded-2xl shadow-lg p-8 text-center">
        <div className="w-16 h-16 border-4 border-brand-green-500 border-t-transparent rounded-full animate-spin mx-auto mb-6" />
        <h1 className="text-2xl font-bold text-neutral-900 mb-2">Loading...</h1>
      </div>
    </div>
  )
}

export default function VerifyMagicLinkPage() {
  return (
    <Suspense fallback={<LoadingSpinner />}>
      <VerifyContent />
    </Suspense>
  )
}
