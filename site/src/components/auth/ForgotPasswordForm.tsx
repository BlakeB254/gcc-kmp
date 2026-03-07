'use client'

import { useState } from 'react'
import Link from 'next/link'
import { Logo } from '@/components/brand/Logo'
import { Mail, ArrowLeft, CheckCircle } from 'lucide-react'

interface ForgotPasswordFormProps {
  apiEndpoint: string
  loginPath: string
  userType: string
  extraLinks?: React.ReactNode
}

export function ForgotPasswordForm({ apiEndpoint, loginPath, userType, extraLinks }: ForgotPasswordFormProps) {
  const [email, setEmail] = useState('')
  const [isLoading, setIsLoading] = useState(false)
  const [isSubmitted, setIsSubmitted] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setIsLoading(true)
    setError(null)

    try {
      const response = await fetch(apiEndpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email }),
      })

      const data = await response.json()

      if (!response.ok) {
        throw new Error(data.error || 'Failed to send reset email')
      }

      setIsSubmitted(true)
    } catch (err) {
      // Don't reveal if email exists or not for security
      setIsSubmitted(true)
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-gradient-to-b from-brand-brown-50 to-white flex items-center justify-center px-4">
      <div className="w-full max-w-md">
        {/* Logo */}
        <div className="text-center mb-8">
          <Link href="/" className="inline-block">
            <Logo variant="full" size="md" />
          </Link>
        </div>

        {/* Card */}
        <div className="bg-white rounded-2xl border border-neutral-200 shadow-lg p-6">
          {isSubmitted ? (
            <div className="text-center py-4">
              <div className="w-16 h-16 bg-brand-green-100 rounded-full flex items-center justify-center mx-auto mb-4">
                <CheckCircle className="w-8 h-8 text-brand-green-600" />
              </div>
              <h1 className="text-2xl font-bold text-neutral-900 mb-2">
                Check Your Email
              </h1>
              <p className="text-neutral-600 mb-6">
                If an account exists with <strong>{email}</strong>, we&apos;ve sent password reset instructions.
              </p>
              <p className="text-sm text-neutral-500 mb-6">
                Didn&apos;t receive the email? Check your spam folder or try again.
              </p>
              <Link
                href={loginPath}
                className="btn-primary inline-flex items-center gap-2 px-6 py-3"
              >
                <ArrowLeft className="w-4 h-4" />
                Back to Login
              </Link>
            </div>
          ) : (
            <>
              <h1 className="text-2xl font-bold text-neutral-900 mb-2 text-center">
                Forgot Password?
              </h1>
              <p className="text-neutral-600 text-center mb-6">
                Enter your email and we&apos;ll send you reset instructions.
              </p>

              {error && (
                <div className="mb-6 p-4 bg-red-50 border border-red-200 rounded-xl text-red-700 text-sm">
                  {error}
                </div>
              )}

              <form onSubmit={handleSubmit} className="space-y-4">
                <div>
                  <label className="block text-sm font-medium text-neutral-700 mb-1">
                    Email Address
                  </label>
                  <div className="relative">
                    <Mail className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-neutral-400" />
                    <input
                      type="email"
                      required
                      value={email}
                      onChange={(e) => setEmail(e.target.value)}
                      className="input-field pl-10"
                      placeholder="you@example.com"
                    />
                  </div>
                </div>

                <button
                  type="submit"
                  disabled={isLoading}
                  className="w-full py-3 px-6 rounded-xl font-semibold bg-brand-gold-500 text-white hover:bg-brand-gold-600 transition-all disabled:opacity-50"
                >
                  {isLoading ? 'Sending...' : 'Send Reset Link'}
                </button>
              </form>

              <div className="mt-6 pt-6 border-t border-neutral-200 text-center">
                <Link
                  href={loginPath}
                  className="inline-flex items-center gap-2 text-neutral-600 hover:text-neutral-900"
                >
                  <ArrowLeft className="w-4 h-4" />
                  Back to Login
                </Link>
              </div>
            </>
          )}
        </div>

        {/* Extra links (e.g., sponsor-specific links) */}
        {extraLinks}

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
