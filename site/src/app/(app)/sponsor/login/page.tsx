'use client'

import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { Logo } from '@/components/brand/Logo'
import { useAuthForm } from '@/hooks/useAuthForm'

export default function SponsorLoginPage() {
  const router = useRouter()
  const { formData, updateField, isLoading, error, handleSubmit: baseSubmit, setError } = useAuthForm({
    apiEndpoint: '/api/sponsors/login',
    successRedirect: '/sponsor/dashboard',
  })

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)

    try {
      const res = await fetch('/api/sponsors/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: formData.email || '', password: formData.password || '' }),
      })

      let data
      try {
        data = await res.json()
      } catch {
        setError('Unable to connect to server. Please try again.')
        return
      }

      if (!res.ok) {
        // Check for specific error codes
        if (data.code === 'EMAIL_NOT_VERIFIED') {
          // Redirect to verification page
          router.push(`/sponsor/verify-email?id=${data.sponsorId}`)
          return
        }
        setError(data.error || 'Login failed')
        return
      }

      // Redirect to dashboard on success
      router.push('/sponsor/dashboard')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Login failed')
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

        {/* Login Card */}
        <div className="bg-white rounded-2xl border border-neutral-200 shadow-lg p-6">
          <h1 className="text-2xl font-bold text-neutral-900 mb-2 text-center">
            Sponsor Login
          </h1>
          <p className="text-neutral-600 text-center mb-6">
            Access your sponsorship dashboard
          </p>

          {error && (
            <div className="mb-6 p-4 bg-red-50 border border-red-200 rounded-xl text-red-700 text-sm">
              {error}
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-neutral-700 mb-1">
                Email
              </label>
              <input
                type="email"
                required
                value={formData.email || ''}
                onChange={(e) => updateField('email', e.target.value)}
                className="input-field"
                placeholder="you@example.com"
              />
            </div>

            <div>
              <label className="block text-sm font-medium text-neutral-700 mb-1">
                Password
              </label>
              <input
                type="password"
                required
                value={formData.password || ''}
                onChange={(e) => updateField('password', e.target.value)}
                className="input-field"
                placeholder="Your password"
              />
            </div>

            <div className="flex items-center justify-between">
              <label className="flex items-center gap-2">
                <input type="checkbox" className="rounded border-neutral-300 text-brand-gold-600" />
                <span className="text-sm text-neutral-600">Remember me</span>
              </label>
              <Link href="/sponsor/forgot-password" className="text-sm text-brand-gold-600 hover:underline">
                Forgot password?
              </Link>
            </div>

            <button
              type="submit"
              disabled={isLoading}
              className="w-full py-3 px-6 rounded-xl font-semibold bg-brand-gold-500 text-white hover:bg-brand-gold-600 transition-all disabled:opacity-50"
            >
              {isLoading ? 'Signing in...' : 'Sign In'}
            </button>
          </form>

          <div className="mt-6 pt-6 border-t border-neutral-200 text-center">
            <p className="text-neutral-600">
              Don't have an account?{' '}
              <Link href="/sponsor/signup" className="text-brand-gold-600 font-medium hover:underline">
                Create one
              </Link>
            </p>
          </div>
        </div>

        {/* Quick sponsor link */}
        <p className="text-center text-neutral-500 text-sm mt-6">
          Just want to sponsor a block?{' '}
          <Link href="/sponsor" className="text-brand-gold-600 hover:underline">
            Sponsor without an account
          </Link>
        </p>

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
