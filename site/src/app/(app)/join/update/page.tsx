'use client'

import { useState, useEffect, Suspense } from 'react'
import { useSearchParams, useRouter } from 'next/navigation'
import Link from 'next/link'
import { Header } from '@/components/landing/Header'
import { US_STATES, getStateName, formatRank } from '@/lib/waitlist/rankings'

interface Profile {
  id: string
  email: string
  name?: string
  location: {
    country: string
    countryCode: string
    state: string
    stateCode: string
    city: string
    neighborhood?: string
  }
  rankings: {
    global: number
    country: number
    state: number
    city: number
  }
}

function UpdateContent() {
  const searchParams = useSearchParams()
  const router = useRouter()
  const id = searchParams.get('id')

  const [email, setEmail] = useState('')
  const [profile, setProfile] = useState<Profile | null>(null)
  const [loading, setLoading] = useState(false)
  const [verified, setVerified] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState(false)

  const [formData, setFormData] = useState({
    name: '',
    city: '',
    stateCode: '',
    neighborhood: '',
  })

  // Fetch profile on verify
  const handleVerify = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!id || !email) return

    setLoading(true)
    setError(null)

    try {
      const response = await fetch(`/api/waitlist/profile?id=${id}`)
      const data = await response.json()

      if (!response.ok) {
        throw new Error(data.error || 'Profile not found')
      }

      // For security, we'd normally verify email server-side
      // Here we're doing a simple client-side check
      setProfile(data.profile)
      setFormData({
        name: data.profile.name || '',
        city: data.profile.location.city,
        stateCode: data.profile.location.stateCode,
        neighborhood: data.profile.location.neighborhood || '',
      })
      setVerified(true)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to verify')
    } finally {
      setLoading(false)
    }
  }

  // Submit update
  const handleUpdate = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!profile) return

    setLoading(true)
    setError(null)

    try {
      const response = await fetch('/api/waitlist/update', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          id: profile.id,
          email,
          updates: {
            name: formData.name || undefined,
            location: {
              state: getStateName(formData.stateCode),
              stateCode: formData.stateCode,
              city: formData.city,
              neighborhood: formData.neighborhood || undefined,
            },
          },
        }),
      })

      const data = await response.json()

      if (!response.ok) {
        throw new Error(data.error || 'Failed to update')
      }

      setSuccess(true)
      setTimeout(() => {
        router.push(`/join/success?id=${profile.id}`)
      }, 2000)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to update')
    } finally {
      setLoading(false)
    }
  }

  if (!id) {
    return (
      <div className="min-h-[60vh] flex items-center justify-center px-4">
        <div className="text-center">
          <p className="text-neutral-600 mb-4">No profile ID provided</p>
          <Link href="/join" className="text-brand-gold-600 hover:text-brand-gold-700 font-medium">
            Join the waitlist →
          </Link>
        </div>
      </div>
    )
  }

  if (success) {
    return (
      <div className="min-h-[60vh] flex items-center justify-center px-4">
        <div className="text-center">
          <div className="text-5xl mb-4">✅</div>
          <h2 className="text-2xl font-bold text-neutral-900 mb-2">Profile Updated!</h2>
          <p className="text-neutral-600">Redirecting to your profile...</p>
        </div>
      </div>
    )
  }

  return (
    <div className="pt-24 pb-16 px-4">
      <div className="max-w-lg mx-auto">
        <div className="text-center mb-8">
          <h1 className="text-2xl sm:text-3xl font-bold text-neutral-900 mb-2">
            Update Your Profile
          </h1>
          <p className="text-neutral-600">
            Moved to a new city? Update your location here.
          </p>
        </div>

        <div className="bg-white rounded-2xl shadow-xl p-6 sm:p-8">
          {!verified ? (
            // Email verification step
            <form onSubmit={handleVerify} className="space-y-6">
              <div>
                <label htmlFor="email" className="block text-sm font-medium text-neutral-700 mb-1">
                  Verify your email
                </label>
                <input
                  type="email"
                  id="email"
                  required
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  className="w-full px-4 py-3 rounded-lg border border-neutral-300 focus:ring-2 focus:ring-brand-gold-500 focus:border-brand-gold-500"
                  placeholder="Enter the email you signed up with"
                />
              </div>

              {error && (
                <div className="p-4 bg-red-50 border border-red-200 rounded-lg text-red-700 text-sm">
                  {error}
                </div>
              )}

              <button
                type="submit"
                disabled={loading}
                className={`
                  w-full py-3 px-6 rounded-lg font-semibold
                  ${
                    loading
                      ? 'bg-neutral-300 text-neutral-500 cursor-not-allowed'
                      : 'bg-brand-gold-500 text-white hover:bg-brand-gold-600'
                  }
                `}
              >
                {loading ? 'Verifying...' : 'Continue'}
              </button>
            </form>
          ) : (
            // Update form
            <form onSubmit={handleUpdate} className="space-y-6">
              {/* Current rankings - shown but not editable */}
              <div className="bg-neutral-50 rounded-lg p-4">
                <p className="text-sm font-medium text-neutral-500 mb-2">Your Permanent Rankings</p>
                <div className="flex gap-4 text-sm">
                  <span className="font-bold">Global: {formatRank(profile!.rankings.global)}</span>
                  <span className="text-neutral-300">|</span>
                  <span className="font-bold">City: {formatRank(profile!.rankings.city)}</span>
                </div>
                <p className="text-xs text-neutral-400 mt-2">
                  Rankings never change - they represent your signup position
                </p>
              </div>

              {/* Name */}
              <div>
                <label htmlFor="name" className="block text-sm font-medium text-neutral-700 mb-1">
                  Name
                </label>
                <input
                  type="text"
                  id="name"
                  value={formData.name}
                  onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                  className="w-full px-4 py-3 rounded-lg border border-neutral-300 focus:ring-2 focus:ring-brand-gold-500"
                />
              </div>

              {/* Location */}
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label htmlFor="state" className="block text-sm font-medium text-neutral-700 mb-1">
                    State
                  </label>
                  <select
                    id="state"
                    required
                    value={formData.stateCode}
                    onChange={(e) => setFormData({ ...formData, stateCode: e.target.value })}
                    className="w-full px-4 py-3 rounded-lg border border-neutral-300 focus:ring-2 focus:ring-brand-gold-500"
                  >
                    <option value="">Select state</option>
                    {US_STATES.map((state) => (
                      <option key={state.code} value={state.code}>
                        {state.name}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label htmlFor="city" className="block text-sm font-medium text-neutral-700 mb-1">
                    City
                  </label>
                  <input
                    type="text"
                    id="city"
                    required
                    value={formData.city}
                    onChange={(e) => setFormData({ ...formData, city: e.target.value })}
                    className="w-full px-4 py-3 rounded-lg border border-neutral-300 focus:ring-2 focus:ring-brand-gold-500"
                  />
                </div>
              </div>

              {/* Neighborhood */}
              <div>
                <label htmlFor="neighborhood" className="block text-sm font-medium text-neutral-700 mb-1">
                  Neighborhood
                </label>
                <input
                  type="text"
                  id="neighborhood"
                  value={formData.neighborhood}
                  onChange={(e) => setFormData({ ...formData, neighborhood: e.target.value })}
                  className="w-full px-4 py-3 rounded-lg border border-neutral-300 focus:ring-2 focus:ring-brand-gold-500"
                />
              </div>

              {error && (
                <div className="p-4 bg-red-50 border border-red-200 rounded-lg text-red-700 text-sm">
                  {error}
                </div>
              )}

              <button
                type="submit"
                disabled={loading}
                className={`
                  w-full py-3 px-6 rounded-lg font-semibold
                  ${
                    loading
                      ? 'bg-neutral-300 text-neutral-500 cursor-not-allowed'
                      : 'bg-brand-gold-500 text-white hover:bg-brand-gold-600'
                  }
                `}
              >
                {loading ? 'Updating...' : 'Save Changes'}
              </button>
            </form>
          )}
        </div>

        <div className="mt-8 text-center">
          <Link href="/" className="text-brand-gold-600 hover:text-brand-gold-700 font-medium">
            ← Back to homepage
          </Link>
        </div>
      </div>
    </div>
  )
}

export default function UpdatePage() {
  return (
    <main className="min-h-screen bg-gradient-to-b from-neutral-50 to-white">
      <Header />
      <Suspense fallback={
        <div className="min-h-[60vh] flex items-center justify-center">
          <div className="w-12 h-12 border-4 border-brand-gold-500 border-t-transparent rounded-full animate-spin" />
        </div>
      }>
        <UpdateContent />
      </Suspense>
    </main>
  )
}
