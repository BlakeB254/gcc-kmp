'use client'

import { useState, Suspense } from 'react'
import { useSearchParams } from 'next/navigation'
import Link from 'next/link'
import { cn } from '@/lib/utils'
import { Logo } from '@/components/brand/Logo'

interface WaitlistFormData {
  email: string
  name: string
  phone: string
  city: string
  state: string
  zip: string
  wantsToBeWorker: boolean
  wantsToBeSponsor: boolean
  wantsToVolunteer: boolean
  emailUpdates: boolean
}

// Wrapper component to handle Suspense for useSearchParams
function WaitlistContent() {
  const searchParams = useSearchParams()
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [isSuccess, setIsSuccess] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [referralCode, setReferralCode] = useState<string | null>(null)

  const [formData, setFormData] = useState<WaitlistFormData>({
    email: '',
    name: '',
    phone: '',
    city: '',
    state: '',
    zip: searchParams.get('zip') || '',
    wantsToBeWorker: true,
    wantsToBeSponsor: false,
    wantsToVolunteer: false,
    emailUpdates: true,
  })

  const source = searchParams.get('source')
  const isFromWorkerSignup = source === 'worker-signup'

  // Phone number formatting
  const formatPhone = (value: string): string => {
    const cleaned = value.replace(/\D/g, '')
    if (cleaned.length === 0) return ''
    if (cleaned.length <= 3) return `(${cleaned}`
    if (cleaned.length <= 6) return `(${cleaned.slice(0, 3)}) ${cleaned.slice(3)}`
    return `(${cleaned.slice(0, 3)}) ${cleaned.slice(3, 6)}-${cleaned.slice(6, 10)}`
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setIsSubmitting(true)
    setError(null)

    try {
      const response = await fetch('/api/waitlist/join', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          email: formData.email,
          name: formData.name || undefined,
          phone: formData.phone || undefined,
          location: {
            country: 'United States',
            countryCode: 'US',
            state: formData.state || 'Illinois',
            stateCode: formData.state ? getStateCode(formData.state) : 'IL',
            city: formData.city || 'Unknown',
            neighborhood: formData.zip ? `ZIP: ${formData.zip}` : undefined,
          },
          preferences: {
            wantsToBeWorker: formData.wantsToBeWorker,
            wantsToBeSponsor: formData.wantsToBeSponsor,
            wantsToVolunteer: formData.wantsToVolunteer,
            emailUpdates: formData.emailUpdates,
          },
        }),
      })

      const data = await response.json()

      if (!response.ok) {
        throw new Error(data.error || 'Failed to join waitlist')
      }

      setIsSuccess(true)
      setReferralCode(data.entry?.referralCode || null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong')
    } finally {
      setIsSubmitting(false)
    }
  }

  const getStateCode = (state: string): string => {
    const codes: Record<string, string> = {
      'Illinois': 'IL', 'Indiana': 'IN', 'Wisconsin': 'WI', 'Michigan': 'MI',
      'Ohio': 'OH', 'Iowa': 'IA', 'Missouri': 'MO', 'Minnesota': 'MN',
    }
    return codes[state] || state.slice(0, 2).toUpperCase()
  }

  if (isSuccess) {
    return (
      <div className="min-h-screen bg-gradient-to-br from-brand-green-50 via-white to-brand-gold-50 py-12 px-4">
        <div className="max-w-lg mx-auto text-center">
          <div className="mb-8">
            <Logo variant="short" size="lg" />
          </div>

          <div className="bg-white rounded-2xl border border-brand-green-200 p-8 shadow-lg">
            <div className="w-16 h-16 mx-auto mb-6 rounded-full bg-brand-green-100 flex items-center justify-center animate-bounce-in">
              <svg className="w-8 h-8 text-brand-green-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
              </svg>
            </div>

            <h1 className="text-2xl font-bold text-neutral-900 mb-3">
              You're on the List!
            </h1>
            <p className="text-neutral-600 mb-6">
              We'll notify you as soon as GCC expands to your area. You're helping build a movement that's changing communities.
            </p>

            {referralCode && (
              <div className="bg-brand-gold-50 border border-brand-gold-200 rounded-xl p-4 mb-6">
                <p className="text-sm text-brand-gold-800 mb-2">
                  Share your referral code to move up the list:
                </p>
                <div className="flex items-center justify-center gap-2">
                  <span className="font-mono font-bold text-xl text-brand-gold-700 tracking-wide">
                    {referralCode}
                  </span>
                  <button
                    onClick={() => navigator.clipboard.writeText(referralCode)}
                    className="p-2 rounded-lg hover:bg-brand-gold-100 transition-colors"
                    title="Copy code"
                  >
                    <svg className="w-5 h-5 text-brand-gold-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
                    </svg>
                  </button>
                </div>
              </div>
            )}

            <div className="flex flex-col gap-3">
              <Link
                href="/"
                className="inline-flex items-center justify-center gap-2 bg-brand-gold-600 hover:bg-brand-gold-700 text-white font-semibold px-6 py-3 rounded-xl transition-all hover:shadow-lg"
              >
                Back to Home
              </Link>
              <Link
                href="/sponsor"
                className="text-brand-gold-600 hover:text-brand-gold-700 font-medium text-sm"
              >
                Want to sponsor a block instead?
              </Link>
            </div>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-brand-sky-50 via-white to-brand-gold-50 py-12 px-4">
      <div className="max-w-lg mx-auto">
        {/* Header */}
        <div className="text-center mb-8">
          <div className="mb-6">
            <Logo variant="short" size="lg" />
          </div>
          <h1 className="text-2xl sm:text-3xl font-bold text-neutral-900 mb-3">
            Join the Movement
          </h1>
          <p className="text-neutral-600">
            {isFromWorkerSignup
              ? "Be the first to know when GCC comes to your neighborhood. We're expanding soon!"
              : "Sign up to be notified when we launch in your area."
            }
          </p>
        </div>

        {/* Form Card */}
        <form onSubmit={handleSubmit} className="bg-white rounded-2xl border border-neutral-200 p-6 sm:p-8 shadow-lg">
          {/* Motivational banner */}
          <div className="mb-6 p-4 bg-gradient-to-r from-brand-sky-50 to-brand-gold-50 border border-brand-sky-200 rounded-xl">
            <div className="flex items-start gap-3">
              <div className="flex-shrink-0 w-10 h-10 rounded-full bg-brand-sky-100 flex items-center justify-center">
                <svg className="w-5 h-5 text-brand-sky-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M11 5.882V19.24a1.76 1.76 0 01-3.417.592l-2.147-6.15M18 13a3 3 0 100-6M5.436 13.683A4.001 4.001 0 017 6h1.832c4.1 0 7.625-1.234 9.168-3v14c-1.543-1.766-5.067-3-9.168-3H7a3.988 3.988 0 01-1.564-.317z" />
                </svg>
              </div>
              <div>
                <p className="text-sm font-medium text-brand-sky-800">
                  You're part of something big
                </p>
                <p className="text-xs text-brand-sky-700 mt-1">
                  Every signup helps us plan our expansion. The more interest from your area, the faster we'll get there.
                </p>
              </div>
            </div>
          </div>

          {/* Error message */}
          {error && (
            <div className="mb-6 p-4 bg-red-50 border border-red-200 rounded-xl text-red-700 text-sm flex items-start gap-3">
              <svg className="w-5 h-5 flex-shrink-0 mt-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
              <span>{error}</span>
            </div>
          )}

          {/* Form fields */}
          <div className="space-y-5">
            {/* Email - required */}
            <div>
              <label htmlFor="email" className="block text-sm font-medium text-neutral-700 mb-1.5">
                Email <span className="text-red-500">*</span>
              </label>
              <input
                type="email"
                id="email"
                required
                value={formData.email}
                onChange={(e) => setFormData({ ...formData, email: e.target.value })}
                placeholder="you@example.com"
                className="w-full rounded-xl border-2 border-neutral-200 px-4 py-3 text-base focus:border-brand-sky-400 focus:ring-4 focus:ring-brand-sky-50 focus:outline-none transition-all"
              />
            </div>

            {/* Name - optional */}
            <div>
              <label htmlFor="name" className="block text-sm font-medium text-neutral-700 mb-1.5">
                Name <span className="text-neutral-400">(optional)</span>
              </label>
              <input
                type="text"
                id="name"
                value={formData.name}
                onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                placeholder="Your name"
                className="w-full rounded-xl border-2 border-neutral-200 px-4 py-3 text-base focus:border-brand-sky-400 focus:ring-4 focus:ring-brand-sky-50 focus:outline-none transition-all"
              />
            </div>

            {/* Phone - optional */}
            <div>
              <label htmlFor="phone" className="block text-sm font-medium text-neutral-700 mb-1.5">
                Phone <span className="text-neutral-400">(optional)</span>
              </label>
              <input
                type="tel"
                id="phone"
                value={formData.phone}
                onChange={(e) => setFormData({ ...formData, phone: formatPhone(e.target.value) })}
                placeholder="(555) 123-4567"
                maxLength={14}
                className="w-full rounded-xl border-2 border-neutral-200 px-4 py-3 text-base focus:border-brand-sky-400 focus:ring-4 focus:ring-brand-sky-50 focus:outline-none transition-all"
              />
              <p className="text-xs text-neutral-500 mt-1">We'll text you when we launch in your area</p>
            </div>

            {/* Location row */}
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label htmlFor="city" className="block text-sm font-medium text-neutral-700 mb-1.5">
                  City
                </label>
                <input
                  type="text"
                  id="city"
                  value={formData.city}
                  onChange={(e) => setFormData({ ...formData, city: e.target.value })}
                  placeholder="Your city"
                  className="w-full rounded-xl border-2 border-neutral-200 px-4 py-3 text-base focus:border-brand-sky-400 focus:ring-4 focus:ring-brand-sky-50 focus:outline-none transition-all"
                />
              </div>
              <div>
                <label htmlFor="zip" className="block text-sm font-medium text-neutral-700 mb-1.5">
                  Zip Code
                </label>
                <input
                  type="text"
                  id="zip"
                  value={formData.zip}
                  onChange={(e) => setFormData({ ...formData, zip: e.target.value.replace(/\D/g, '').slice(0, 5) })}
                  placeholder="12345"
                  maxLength={5}
                  className="w-full rounded-xl border-2 border-neutral-200 px-4 py-3 text-base focus:border-brand-sky-400 focus:ring-4 focus:ring-brand-sky-50 focus:outline-none transition-all"
                />
              </div>
            </div>

            {/* Interest preferences */}
            <div className="pt-4 border-t border-neutral-100">
              <p className="text-sm font-medium text-neutral-700 mb-3">
                How would you like to participate?
              </p>
              <div className="space-y-3">
                <label className="flex items-center gap-3 cursor-pointer group">
                  <input
                    type="checkbox"
                    checked={formData.wantsToBeWorker}
                    onChange={(e) => setFormData({ ...formData, wantsToBeWorker: e.target.checked })}
                    className="w-5 h-5 rounded border-2 border-neutral-300 text-brand-green-600 focus:ring-brand-green-500"
                  />
                  <div>
                    <span className="text-sm font-medium text-neutral-900">Clean blocks & earn money</span>
                    <p className="text-xs text-neutral-500">Get paid $100 per block cleaned</p>
                  </div>
                </label>
                <label className="flex items-center gap-3 cursor-pointer group">
                  <input
                    type="checkbox"
                    checked={formData.wantsToBeSponsor}
                    onChange={(e) => setFormData({ ...formData, wantsToBeSponsor: e.target.checked })}
                    className="w-5 h-5 rounded border-2 border-neutral-300 text-brand-gold-600 focus:ring-brand-gold-500"
                  />
                  <div>
                    <span className="text-sm font-medium text-neutral-900">Sponsor a block</span>
                    <p className="text-xs text-neutral-500">Donate $100 to fund block cleaning in your area</p>
                  </div>
                </label>
                <label className="flex items-center gap-3 cursor-pointer group">
                  <input
                    type="checkbox"
                    checked={formData.wantsToVolunteer}
                    onChange={(e) => setFormData({ ...formData, wantsToVolunteer: e.target.checked })}
                    className="w-5 h-5 rounded border-2 border-neutral-300 text-brand-sky-600 focus:ring-brand-sky-500"
                  />
                  <div>
                    <span className="text-sm font-medium text-neutral-900">Volunteer my time</span>
                    <p className="text-xs text-neutral-500">Help organize and grow the movement</p>
                  </div>
                </label>
              </div>
            </div>

            {/* Email updates */}
            <label className="flex items-start gap-3 cursor-pointer pt-2">
              <input
                type="checkbox"
                checked={formData.emailUpdates}
                onChange={(e) => setFormData({ ...formData, emailUpdates: e.target.checked })}
                className="w-5 h-5 rounded border-2 border-neutral-300 text-brand-gold-600 focus:ring-brand-gold-500 mt-0.5"
              />
              <span className="text-sm text-neutral-600">
                Send me updates about GCC's expansion and community impact
              </span>
            </label>
          </div>

          {/* Submit button */}
          <button
            type="submit"
            disabled={isSubmitting || !formData.email}
            className={cn(
              'w-full mt-8 inline-flex items-center justify-center gap-2 rounded-xl px-6 py-4 text-base font-semibold text-white shadow-lg transition-all',
              'bg-brand-sky-600 hover:bg-brand-sky-700 hover:shadow-xl active:scale-[0.98]',
              'focus:outline-none focus:ring-2 focus:ring-brand-sky-500 focus:ring-offset-2',
              'disabled:opacity-50 disabled:cursor-not-allowed'
            )}
          >
            {isSubmitting ? (
              <>
                <svg className="animate-spin h-5 w-5 text-white" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                </svg>
                Joining...
              </>
            ) : (
              <>
                Reserve My Spot
                <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
                </svg>
              </>
            )}
          </button>
        </form>

        {/* Back link */}
        <p className="text-center text-sm text-neutral-600 mt-6">
          {isFromWorkerSignup ? (
            <>
              Live in North Lawndale?{' '}
              <Link href="/worker/signup" className="text-brand-gold-600 font-semibold hover:underline">
                Continue signing up
              </Link>
            </>
          ) : (
            <>
              Ready to get started?{' '}
              <Link href="/" className="text-brand-gold-600 font-semibold hover:underline">
                Back to home
              </Link>
            </>
          )}
        </p>
      </div>
    </div>
  )
}

// Loading fallback for Suspense
function WaitlistLoading() {
  return (
    <div className="min-h-screen bg-gradient-to-br from-brand-sky-50 via-white to-brand-gold-50 py-12 px-4">
      <div className="max-w-lg mx-auto">
        <div className="text-center mb-8">
          <div className="mb-6">
            <Logo variant="short" size="lg" />
          </div>
          <div className="h-8 w-48 bg-neutral-200 rounded-lg mx-auto animate-pulse" />
          <div className="h-4 w-64 bg-neutral-100 rounded mt-3 mx-auto animate-pulse" />
        </div>
        <div className="bg-white rounded-2xl border border-neutral-200 p-6 sm:p-8 shadow-lg">
          <div className="space-y-4">
            <div className="h-12 bg-neutral-100 rounded-xl animate-pulse" />
            <div className="h-12 bg-neutral-100 rounded-xl animate-pulse" />
            <div className="h-12 bg-neutral-100 rounded-xl animate-pulse" />
          </div>
        </div>
      </div>
    </div>
  )
}

// Default export with Suspense boundary
export default function WaitlistPage() {
  return (
    <Suspense fallback={<WaitlistLoading />}>
      <WaitlistContent />
    </Suspense>
  )
}
