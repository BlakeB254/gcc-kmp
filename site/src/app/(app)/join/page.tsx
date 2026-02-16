'use client'

import { useState, useEffect, Suspense, useMemo } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'
import Link from 'next/link'
import {
  US_STATES,
  getStateName,
  getStateFromZip,
  COUNTRIES,
  getCountryInfo,
  CHICAGO_COMMUNITY_AREAS,
  getTierInfo,
  isLaunchAreaZip,
  getContinent,
  LAUNCH_ZIP_CODES,
  type EarlyAdopterTier
} from '@/lib/waitlist/rankings'

interface FormData {
  email: string
  name: string
  country: string
  stateCode: string
  stateName: string
  city: string
  zipCode: string
  communityArea: string
  customCommunityArea: string
  wantsToBeWorker: boolean
  wantsToBeSponsor: boolean
  emailUpdates: boolean
}

function JoinForm() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const referralCode = searchParams.get('ref')

  // Get pre-fill parameters from URL (e.g., from worker signup diverter)
  const urlZip = searchParams.get('zip')
  const urlEmail = searchParams.get('email')
  const urlName = searchParams.get('name')
  const urlCity = searchParams.get('city')
  const urlState = searchParams.get('state')
  const urlSource = searchParams.get('source')
  const urlIntent = searchParams.get('intent')

  // Determine if we came from worker signup with all required data
  const isFromWorkerSignup = urlSource === 'worker-signup'
  const hasCompleteData = urlEmail && urlZip && urlCity

  const [formData, setFormData] = useState<FormData>({
    email: urlEmail || '',
    name: urlName || '',
    country: 'US',
    stateCode: urlState || (urlZip?.startsWith('606') ? 'IL' : ''),
    stateName: '',
    city: urlCity || '',
    zipCode: urlZip || '',
    communityArea: '',
    customCommunityArea: '',
    wantsToBeWorker: urlIntent === 'worker' || true,
    wantsToBeSponsor: urlIntent === 'sponsor' || false,
    emailUpdates: true,
  })

  const [isSubmitting, setIsSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // Skip to step 3 if coming from worker signup with complete data
  const [step, setStep] = useState(hasCompleteData && isFromWorkerSignup ? 3 : 1)
  const [estimatedRanks, setEstimatedRanks] = useState({
    global: 0,
    country: 0,
    state: 0,
    city: 0,
  })

  // Determine user's location context
  const isUSA = formData.country === 'US'
  const isChicago = formData.city.toLowerCase().includes('chicago')
  const isLaunchArea = isUSA && isLaunchAreaZip(formData.zipCode)

  // Get continent info for global stats
  const continentInfo = getContinent(formData.country)

  // Fetch real movement stats for estimated position
  useEffect(() => {
    const fetchEstimatedRanks = async () => {
      try {
        const response = await fetch('/api/movement/stats')
        if (response.ok) {
          const data = await response.json()
          const currentTotal = data.stats?.total || 0
          // User's estimated position is current total + 1 (they'll be next)
          const estimatedGlobal = currentTotal + 1
          setEstimatedRanks({
            global: estimatedGlobal,
            // Regional estimates - for now approximate based on typical distribution
            // These will be accurate after submission when we count by location
            country: Math.max(1, Math.ceil(estimatedGlobal * 0.7)), // ~70% are US-based
            state: Math.max(1, Math.ceil(estimatedGlobal * 0.4)),   // ~40% in same state
            city: Math.max(1, Math.ceil(estimatedGlobal * 0.2)),    // ~20% in same city
          })
        }
      } catch (error) {
        // Fallback to position 1 if API fails (launch day)
        setEstimatedRanks({ global: 1, country: 1, state: 1, city: 1 })
      }
    }
    fetchEstimatedRanks()
  }, [])

  // Determine tier based on estimated global rank
  const estimatedTier: EarlyAdopterTier = useMemo(() => {
    if (estimatedRanks.global <= 100) return 'founder'
    if (estimatedRanks.global <= 1000) return 'pioneer'
    if (estimatedRanks.global <= 10000) return 'early'
    return 'standard'
  }, [estimatedRanks.global])

  const tierInfo = getTierInfo(estimatedTier)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    setIsSubmitting(true)

    try {
      const countryInfo = getCountryInfo(formData.country)
      const finalCommunityArea = formData.communityArea === 'Other (specify)'
        ? formData.customCommunityArea
        : formData.communityArea

      const response = await fetch('/api/waitlist/join', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          email: formData.email,
          name: formData.name || undefined,
          location: {
            country: countryInfo.name,
            countryCode: formData.country,
            continent: continentInfo.name,
            continentCode: continentInfo.code,
            state: isUSA ? getStateName(formData.stateCode) : formData.stateName,
            stateCode: isUSA ? formData.stateCode : formData.stateName.substring(0, 2).toUpperCase(),
            city: formData.city,
            zipCode: isUSA ? formData.zipCode : undefined,
            // Only include community area for launch area signups
            communityArea: isLaunchArea ? finalCommunityArea : undefined,
          },
          isLaunchArea, // Flag to distinguish local vs global waitlist
          referralCode: referralCode || undefined,
          preferences: {
            wantsToBeWorker: formData.wantsToBeWorker,
            wantsToBeSponsor: formData.wantsToBeSponsor,
            wantsToVolunteer: false,
            emailUpdates: formData.emailUpdates,
          },
        }),
      })

      const data = await response.json()

      if (!response.ok) {
        if (response.status === 409 && data.existingEntry) {
          router.push(`/join/success?id=${data.existingEntry.id}&existing=true`)
          return
        }
        throw new Error(data.error || 'Failed to join waitlist')
      }

      router.push(`/join/success?id=${data.entry.id}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong')
    } finally {
      setIsSubmitting(false)
    }
  }

  const canProceedStep1 = formData.email && formData.country && formData.city && (isUSA ? formData.zipCode.length >= 5 : true)
  const canProceedStep2 = isUSA ? formData.stateCode : (formData.stateName || true)

  return (
    <main className="min-h-screen bg-gradient-to-br from-brand-green-900 via-brand-brown-900 to-neutral-900 relative overflow-hidden">
      {/* Decorative background elements */}
      <div className="absolute inset-0 overflow-hidden pointer-events-none">
        {/* Globe grid lines */}
        <div className="absolute top-1/4 -right-1/4 w-[800px] h-[800px] opacity-5">
          <svg viewBox="0 0 400 400" className="w-full h-full text-brand-gold-400">
            <circle cx="200" cy="200" r="180" fill="none" stroke="currentColor" strokeWidth="0.5" />
            <circle cx="200" cy="200" r="140" fill="none" stroke="currentColor" strokeWidth="0.5" />
            <circle cx="200" cy="200" r="100" fill="none" stroke="currentColor" strokeWidth="0.5" />
            <circle cx="200" cy="200" r="60" fill="none" stroke="currentColor" strokeWidth="0.5" />
            <ellipse cx="200" cy="200" rx="180" ry="60" fill="none" stroke="currentColor" strokeWidth="0.5" />
            <ellipse cx="200" cy="200" rx="60" ry="180" fill="none" stroke="currentColor" strokeWidth="0.5" />
            <line x1="20" y1="200" x2="380" y2="200" stroke="currentColor" strokeWidth="0.5" />
            <line x1="200" y1="20" x2="200" y2="380" stroke="currentColor" strokeWidth="0.5" />
          </svg>
        </div>
        {/* Gradient orbs */}
        <div className="absolute -top-40 -left-40 w-80 h-80 bg-brand-gold-500/10 rounded-full blur-3xl" />
        <div className="absolute -bottom-40 -right-40 w-96 h-96 bg-brand-green-500/10 rounded-full blur-3xl" />
      </div>

      <div className="relative z-10 py-8 px-4">
        <div className="max-w-xl mx-auto">
          {/* Hero section */}
          <div className="text-center mb-8">
            <div className="inline-flex items-center gap-2 rounded-full bg-brand-gold-500/20 border border-brand-gold-500/30 px-4 py-1.5 mb-6">
              <span className="text-xl">🌍</span>
              <span className="text-sm font-medium text-brand-gold-300">Global Movement</span>
            </div>

            <h1 className="text-3xl sm:text-4xl lg:text-5xl font-bold text-white mb-4 leading-tight">
              Secure Your Position
              <br />
              <span className="text-brand-gold-400">In the Movement</span>
            </h1>

            <p className="text-lg text-neutral-300 max-w-md mx-auto">
              Lock in your permanent rank before we launch in your area.
              Earlier joiners stay higher forever.
            </p>

            {referralCode && (
              <div className="mt-4 inline-flex items-center gap-2 bg-brand-green-500/20 border border-brand-green-500/30 text-brand-green-300 px-4 py-2 rounded-full text-sm font-medium animate-fade-in-up">
                <span>✨</span>
                <span>Invited by a friend — you get priority!</span>
              </div>
            )}

            {urlSource === 'worker-signup' && (
              <div className="mt-4 p-4 rounded-xl bg-brand-sky-500/10 border border-brand-sky-500/20 text-center animate-fade-in-up">
                <p className="text-brand-sky-300 text-sm">
                  <span className="font-semibold">💼 Future Worker Detected!</span>
                  <br />
                  <span className="text-brand-sky-400/80">We'll prioritize you when GCC expands to your area</span>
                </p>
                {(urlEmail || urlName) && (
                  <p className="text-brand-sky-400/60 text-xs mt-2">
                    ✓ Your info from signup has been preserved
                  </p>
                )}
              </div>
            )}
          </div>

          {/* Estimated Position Preview */}
          <div className="mb-8 p-4 rounded-2xl bg-white/5 backdrop-blur-sm border border-white/10">
            <div className="flex items-center justify-between mb-3">
              <span className="text-sm text-neutral-400">Your estimated position</span>
              <div className={`flex items-center gap-2 px-3 py-1 rounded-full text-sm font-medium ${tierInfo.bgColor} text-white`}>
                <span>{tierInfo.emoji}</span>
                <span>{tierInfo.label}</span>
              </div>
            </div>
            <div className="grid grid-cols-4 gap-2">
              {[
                { label: 'Global', value: estimatedRanks.global, icon: '🌍' },
                { label: 'Country', value: estimatedRanks.country, icon: getCountryInfo(formData.country).flag },
                { label: 'State', value: estimatedRanks.state, icon: '📍' },
                { label: 'City', value: estimatedRanks.city, icon: '🏙️' },
              ].map((rank) => (
                <div key={rank.label} className="text-center">
                  <div className="text-lg mb-1">{rank.icon}</div>
                  <div className="text-xl font-bold text-white">#{rank.value}</div>
                  <div className="text-xs text-neutral-500">{rank.label}</div>
                </div>
              ))}
            </div>
          </div>

          {/* Progress indicator */}
          <div className="flex items-center justify-center gap-2 mb-6">
            {[1, 2, 3].map((s) => (
              <div
                key={s}
                className={`h-2 rounded-full transition-all duration-300 ${
                  s === step
                    ? 'w-8 bg-brand-gold-500'
                    : s < step
                    ? 'w-2 bg-brand-gold-500'
                    : 'w-2 bg-white/20'
                }`}
              />
            ))}
          </div>

          {/* Form card */}
          <div className="bg-white/10 backdrop-blur-md rounded-3xl border border-white/20 shadow-2xl overflow-hidden">
            <form onSubmit={handleSubmit}>
              {/* Step 1: Basic Info */}
              {step === 1 && (
                <div className="p-6 sm:p-8 space-y-6 animate-fade-in-up">
                  <div className="text-center mb-6">
                    <h2 className="text-xl font-bold text-white mb-2">Where are you from?</h2>
                    <p className="text-neutral-400 text-sm">This determines your regional rankings</p>
                  </div>

                  {/* Email */}
                  <div>
                    <label htmlFor="email" className="block text-sm font-medium text-neutral-300 mb-2">
                      Email address <span className="text-brand-gold-400">*</span>
                    </label>
                    <input
                      type="email"
                      id="email"
                      required
                      value={formData.email}
                      onChange={(e) => setFormData({ ...formData, email: e.target.value })}
                      className="w-full px-4 py-3.5 rounded-xl bg-white/10 border border-white/20 text-white placeholder-neutral-500 focus:ring-2 focus:ring-brand-gold-500 focus:border-transparent transition-all"
                      placeholder="you@example.com"
                    />
                  </div>

                  {/* Country */}
                  <div>
                    <label htmlFor="country" className="block text-sm font-medium text-neutral-300 mb-2">
                      Country <span className="text-brand-gold-400">*</span>
                    </label>
                    <select
                      id="country"
                      required
                      value={formData.country}
                      onChange={(e) => setFormData({ ...formData, country: e.target.value, stateCode: '', stateName: '' })}
                      className="w-full px-4 py-3.5 rounded-xl bg-white/10 border border-white/20 text-white focus:ring-2 focus:ring-brand-gold-500 focus:border-transparent transition-all"
                    >
                      {COUNTRIES.map((country) => (
                        <option key={country.code} value={country.code} className="bg-neutral-800">
                          {country.flag} {country.name}
                        </option>
                      ))}
                    </select>
                  </div>

                  {/* City */}
                  <div>
                    <label htmlFor="city" className="block text-sm font-medium text-neutral-300 mb-2">
                      City <span className="text-brand-gold-400">*</span>
                    </label>
                    <input
                      type="text"
                      id="city"
                      required
                      value={formData.city}
                      onChange={(e) => setFormData({ ...formData, city: e.target.value })}
                      className="w-full px-4 py-3.5 rounded-xl bg-white/10 border border-white/20 text-white placeholder-neutral-500 focus:ring-2 focus:ring-brand-gold-500 focus:border-transparent transition-all"
                      placeholder={isUSA ? "Chicago, New York, etc." : "London, Toronto, etc."}
                    />
                  </div>

                  {/* Zip Code (US only) - auto-detects state */}
                  {isUSA && (
                    <div>
                      <label htmlFor="zipCode" className="block text-sm font-medium text-neutral-300 mb-2">
                        Zip Code <span className="text-brand-gold-400">*</span>
                      </label>
                      <input
                        type="text"
                        id="zipCode"
                        required
                        maxLength={5}
                        pattern="[0-9]{5}"
                        value={formData.zipCode}
                        onChange={(e) => {
                          const value = e.target.value.replace(/\D/g, '').slice(0, 5)
                          // Auto-detect state from zip code
                          const detectedState = value.length >= 3 ? getStateFromZip(value) : null
                          setFormData({
                            ...formData,
                            zipCode: value,
                            stateCode: detectedState || formData.stateCode,
                          })
                        }}
                        className="w-full px-4 py-3.5 rounded-xl bg-white/10 border border-white/20 text-white placeholder-neutral-500 focus:ring-2 focus:ring-brand-gold-500 focus:border-transparent transition-all"
                        placeholder="60623"
                      />
                      {formData.zipCode.length === 5 && formData.stateCode && (
                        <div className="mt-2 p-3 rounded-lg bg-white/5 border border-white/10 text-neutral-300 text-sm flex items-center gap-2">
                          <span>📍</span>
                          <span>{getStateName(formData.stateCode)}</span>
                          {isLaunchAreaZip(formData.zipCode) && (
                            <span className="ml-auto text-brand-green-400 font-medium">🎉 Launch Area!</span>
                          )}
                        </div>
                      )}
                    </div>
                  )}

                  <button
                    type="button"
                    onClick={() => {
                      // For US users, auto-set state from zip and skip to step 3
                      // Only show step 2 for non-US users (state/province entry)
                      if (isUSA) {
                        // Ensure state is set from zip
                        const detectedState = getStateFromZip(formData.zipCode) || 'IL'
                        setFormData(prev => ({ ...prev, stateCode: detectedState }))
                        setStep(3) // Skip step 2 entirely for US users
                      } else {
                        setStep(2) // Non-US users need to enter state/province
                      }
                    }}
                    disabled={!canProceedStep1}
                    className={`
                      w-full py-4 px-6 rounded-xl font-semibold text-lg
                      transition-all transform
                      ${
                        canProceedStep1
                          ? 'bg-brand-gold-500 text-white hover:bg-brand-gold-400 hover:scale-[1.02] active:scale-[0.98]'
                          : 'bg-white/10 text-neutral-500 cursor-not-allowed'
                      }
                    `}
                  >
                    Continue →
                  </button>
                </div>
              )}

              {/* Step 2: Location Details - DIVERTER based on zip code */}
              {step === 2 && (
                <div className="p-6 sm:p-8 space-y-6 animate-fade-in-up">
                  {/* LAUNCH AREA FLOW (60623/60624) - Redirect to worker signup */}
                  {isLaunchArea ? (
                    <>
                      <div className="text-center mb-6">
                        <div className="inline-flex items-center gap-2 rounded-full bg-brand-green-500/20 border border-brand-green-500/30 px-4 py-2 mb-4">
                          <span className="text-xl">🎯</span>
                          <span className="text-sm font-medium text-brand-green-300">Launch Area Resident</span>
                        </div>
                        <h2 className="text-xl font-bold text-white mb-2">You're in the Launch Area!</h2>
                        <p className="text-neutral-400 text-sm">
                          Great news! GCC is live in North Lawndale & Little Village.
                          <br />
                          <strong className="text-brand-green-400">You can start earning $100/block today!</strong>
                        </p>
                      </div>

                      {/* Direct CTA to worker signup */}
                      <div className="p-4 rounded-xl bg-brand-green-500/20 border border-brand-green-500/30 mb-4">
                        <p className="text-brand-green-300 text-sm mb-4 text-center">
                          Skip the waitlist - become a worker now and start earning immediately!
                        </p>
                        <Link
                          href={`/worker/signup?zip=${formData.zipCode}&email=${encodeURIComponent(formData.email)}&city=${encodeURIComponent(formData.city)}`}
                          className="block w-full py-4 px-6 rounded-xl font-semibold text-lg bg-brand-green-500 text-white hover:bg-brand-green-400 transition-all transform hover:scale-[1.02] active:scale-[0.98] text-center"
                        >
                          💪 Become a Worker Now
                        </Link>
                        <p className="text-center text-xs text-brand-green-400/60 mt-3">
                          Or continue below to join the waitlist instead
                        </p>
                      </div>

                      {/* State - pre-selected for launch area */}
                      <div>
                        <label htmlFor="state" className="block text-sm font-medium text-neutral-300 mb-2">
                          State
                        </label>
                        <select
                          id="state"
                          value={formData.stateCode || 'IL'}
                          onChange={(e) => setFormData({ ...formData, stateCode: e.target.value })}
                          className="w-full px-4 py-3.5 rounded-xl bg-white/10 border border-white/20 text-white focus:ring-2 focus:ring-brand-gold-500 focus:border-transparent transition-all"
                        >
                          {US_STATES.map((state) => (
                            <option key={state.code} value={state.code} className="bg-neutral-800">
                              {state.name}
                            </option>
                          ))}
                        </select>
                      </div>

                      {/* Community Area - Chicago's 77 official areas */}
                      <div>
                        <label htmlFor="communityArea" className="block text-sm font-medium text-neutral-300 mb-2">
                          Community Area
                          <span className="ml-2 text-xs text-brand-green-400">(Chicago's 77 official areas)</span>
                        </label>
                        <select
                          id="communityArea"
                          value={formData.communityArea}
                          onChange={(e) => setFormData({ ...formData, communityArea: e.target.value })}
                          className="w-full px-4 py-3.5 rounded-xl bg-white/10 border border-white/20 text-white focus:ring-2 focus:ring-brand-gold-500 focus:border-transparent transition-all"
                        >
                          <option value="" className="bg-neutral-800">Select community area</option>
                          {CHICAGO_COMMUNITY_AREAS.map((area) => (
                            <option
                              key={area.id}
                              value={area.name}
                              className="bg-neutral-800"
                            >
                              {area.name} {'isLaunchArea' in area && area.isLaunchArea ? '⭐' : ''}
                            </option>
                          ))}
                          <option value="Other (specify)" className="bg-neutral-800">Other (specify)</option>
                        </select>

                        {formData.communityArea === 'Other (specify)' && (
                          <input
                            type="text"
                            value={formData.customCommunityArea}
                            onChange={(e) => setFormData({ ...formData, customCommunityArea: e.target.value })}
                            className="mt-3 w-full px-4 py-3.5 rounded-xl bg-white/10 border border-white/20 text-white placeholder-neutral-500 focus:ring-2 focus:ring-brand-gold-500 focus:border-transparent transition-all"
                            placeholder="Enter your community area"
                          />
                        )}

                        {(formData.communityArea === 'North Lawndale' || formData.communityArea === 'South Lawndale') && (
                          <div className="mt-3 p-3 rounded-lg bg-brand-green-500/20 border border-brand-green-500/30 text-brand-green-300 text-sm">
                            🚀 <strong>Active launch zone!</strong> Workers can start earning $100/block immediately.
                          </div>
                        )}
                      </div>

                      {/* Local stats preview */}
                      <div className="p-4 rounded-xl bg-white/5 border border-white/10">
                        <p className="text-sm text-neutral-400 mb-2">Your rankings will include:</p>
                        <div className="flex flex-wrap gap-2">
                          {['🌍 Global', '🌎 Continent', '🇺🇸 USA', '📍 Illinois', '🏙️ Chicago', '🏘️ Community Area'].map((tag) => (
                            <span key={tag} className="px-2 py-1 rounded-full bg-white/10 text-xs text-white">
                              {tag}
                            </span>
                          ))}
                        </div>
                      </div>
                    </>
                  ) : (
                    /* GLOBAL WAITLIST FLOW (non-launch zip codes) */
                    <>
                      <div className="text-center mb-6">
                        <div className="inline-flex items-center gap-2 rounded-full bg-brand-gold-500/20 border border-brand-gold-500/30 px-4 py-2 mb-4">
                          <span className="text-xl">🌍</span>
                          <span className="text-sm font-medium text-brand-gold-300">Global Waitlist</span>
                        </div>
                        <h2 className="text-xl font-bold text-white mb-2">Join the Global Movement</h2>
                        <p className="text-neutral-400 text-sm">
                          We're launching in Chicago first, but expanding worldwide.
                          <br />Lock in your global position now!
                        </p>
                      </div>

                      {/* State/Province */}
                      {isUSA ? (
                        <div>
                          <label htmlFor="state" className="block text-sm font-medium text-neutral-300 mb-2">
                            State <span className="text-brand-gold-400">*</span>
                          </label>
                          <select
                            id="state"
                            required
                            value={formData.stateCode}
                            onChange={(e) => setFormData({ ...formData, stateCode: e.target.value })}
                            className="w-full px-4 py-3.5 rounded-xl bg-white/10 border border-white/20 text-white focus:ring-2 focus:ring-brand-gold-500 focus:border-transparent transition-all"
                          >
                            <option value="" className="bg-neutral-800">Select state</option>
                            {US_STATES.map((state) => (
                              <option key={state.code} value={state.code} className="bg-neutral-800">
                                {state.name}
                              </option>
                            ))}
                          </select>
                        </div>
                      ) : (
                        <div>
                          <label htmlFor="stateName" className="block text-sm font-medium text-neutral-300 mb-2">
                            State/Province/Region <span className="text-neutral-500">(optional)</span>
                          </label>
                          <input
                            type="text"
                            id="stateName"
                            value={formData.stateName}
                            onChange={(e) => setFormData({ ...formData, stateName: e.target.value })}
                            className="w-full px-4 py-3.5 rounded-xl bg-white/10 border border-white/20 text-white placeholder-neutral-500 focus:ring-2 focus:ring-brand-gold-500 focus:border-transparent transition-all"
                            placeholder="Ontario, England, etc."
                          />
                        </div>
                      )}

                      {/* Global stats preview */}
                      <div className="p-4 rounded-xl bg-white/5 border border-white/10">
                        <p className="text-sm text-neutral-400 mb-3">Your rankings will include:</p>
                        <div className="grid grid-cols-2 gap-3">
                          <div className="text-center p-3 rounded-lg bg-white/5">
                            <span className="text-2xl">{continentInfo.emoji}</span>
                            <p className="text-sm text-white mt-1">{continentInfo.name}</p>
                          </div>
                          <div className="text-center p-3 rounded-lg bg-white/5">
                            <span className="text-2xl">{getCountryInfo(formData.country).flag}</span>
                            <p className="text-sm text-white mt-1">{getCountryInfo(formData.country).name}</p>
                          </div>
                          {(isUSA ? formData.stateCode : formData.stateName) && (
                            <div className="text-center p-3 rounded-lg bg-white/5">
                              <span className="text-2xl">📍</span>
                              <p className="text-sm text-white mt-1">
                                {isUSA ? getStateName(formData.stateCode) : formData.stateName}
                              </p>
                            </div>
                          )}
                          <div className="text-center p-3 rounded-lg bg-white/5">
                            <span className="text-2xl">🏙️</span>
                            <p className="text-sm text-white mt-1">{formData.city}</p>
                          </div>
                        </div>
                      </div>

                      {/* Expansion interest note */}
                      <div className="p-3 rounded-lg bg-brand-gold-500/10 border border-brand-gold-500/20 text-brand-gold-200 text-sm">
                        💡 <strong>Tip:</strong> The more signups from your city, the faster we expand there!
                      </div>
                    </>
                  )}

                  <div className="flex gap-3">
                    <button
                      type="button"
                      onClick={() => setStep(1)}
                      className="px-6 py-4 rounded-xl font-semibold text-neutral-400 hover:text-white transition-colors"
                    >
                      ← Back
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        // Auto-set Illinois for launch area users
                        if (isLaunchArea && !formData.stateCode) {
                          setFormData(prev => ({ ...prev, stateCode: 'IL' }))
                        }
                        setStep(3)
                      }}
                      disabled={!canProceedStep2}
                      className={`
                        flex-1 py-4 px-6 rounded-xl font-semibold text-lg
                        transition-all transform
                        ${
                          canProceedStep2
                            ? 'bg-brand-gold-500 text-white hover:bg-brand-gold-400 hover:scale-[1.02] active:scale-[0.98]'
                            : 'bg-white/10 text-neutral-500 cursor-not-allowed'
                        }
                      `}
                    >
                      Continue →
                    </button>
                  </div>
                </div>
              )}

              {/* Step 3: Final Details & Submit */}
              {step === 3 && (
                <div className="p-6 sm:p-8 space-y-6 animate-fade-in-up">
                  <div className="text-center mb-6">
                    <h2 className="text-xl font-bold text-white mb-2">
                      {isFromWorkerSignup ? 'Confirm Your Info' : 'Almost there!'}
                    </h2>
                    <p className="text-neutral-400 text-sm">
                      {isFromWorkerSignup
                        ? 'We\'ve saved your details from signup'
                        : 'Tell us how you want to participate'}
                    </p>
                  </div>

                  {/* Show location summary if coming from worker signup */}
                  {isFromWorkerSignup && hasCompleteData && (
                    <div className="p-4 rounded-xl bg-white/5 border border-white/10 mb-2">
                      <div className="flex items-center justify-between">
                        <div>
                          <p className="text-xs text-neutral-500 mb-1">Your location</p>
                          <p className="text-white font-medium">
                            {formData.city}{formData.stateCode ? `, ${formData.stateCode}` : ''} {formData.zipCode}
                          </p>
                        </div>
                        <button
                          type="button"
                          onClick={() => setStep(1)}
                          className="text-xs text-brand-gold-400 hover:text-brand-gold-300"
                        >
                          Edit
                        </button>
                      </div>
                    </div>
                  )}

                  {/* Name - show as editable summary if pre-filled, otherwise full input */}
                  {formData.name && isFromWorkerSignup ? (
                    <div className="p-4 rounded-xl bg-white/5 border border-white/10">
                      <div className="flex items-center justify-between">
                        <div>
                          <p className="text-xs text-neutral-500 mb-1">Name</p>
                          <p className="text-white font-medium">{formData.name}</p>
                        </div>
                        <button
                          type="button"
                          onClick={() => {
                            const name = prompt('Enter your name:', formData.name)
                            if (name !== null) setFormData({ ...formData, name })
                          }}
                          className="text-xs text-brand-gold-400 hover:text-brand-gold-300"
                        >
                          Edit
                        </button>
                      </div>
                    </div>
                  ) : (
                    <div>
                      <label htmlFor="name" className="block text-sm font-medium text-neutral-300 mb-2">
                        Your name <span className="text-neutral-500">(optional)</span>
                      </label>
                      <input
                        type="text"
                        id="name"
                        value={formData.name}
                        onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                        className="w-full px-4 py-3.5 rounded-xl bg-white/10 border border-white/20 text-white placeholder-neutral-500 focus:ring-2 focus:ring-brand-gold-500 focus:border-transparent transition-all"
                        placeholder="John Doe"
                      />
                    </div>
                  )}

                  {/* Interests */}
                  <div>
                    <p className="text-sm font-medium text-neutral-300 mb-3">
                      I'm interested in...
                    </p>
                    <div className="space-y-3">
                      <label className="flex items-center gap-4 p-4 rounded-xl bg-white/5 border border-white/10 hover:border-brand-gold-500/30 cursor-pointer transition-all group">
                        <div className="relative">
                          <input
                            type="checkbox"
                            checked={formData.wantsToBeWorker}
                            onChange={(e) => setFormData({ ...formData, wantsToBeWorker: e.target.checked })}
                            className="sr-only"
                          />
                          <div className={`w-6 h-6 rounded-lg border-2 flex items-center justify-center transition-all ${
                            formData.wantsToBeWorker
                              ? 'bg-brand-gold-500 border-brand-gold-500'
                              : 'border-white/30 group-hover:border-white/50'
                          }`}>
                            {formData.wantsToBeWorker && (
                              <svg className="w-4 h-4 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" />
                              </svg>
                            )}
                          </div>
                        </div>
                        <div className="flex-1">
                          <p className="font-medium text-white">Becoming a worker</p>
                          <p className="text-sm text-neutral-400">Earn $100 per block in your community</p>
                        </div>
                        <span className="text-2xl">💪</span>
                      </label>

                      <label className="flex items-center gap-4 p-4 rounded-xl bg-white/5 border border-white/10 hover:border-brand-gold-500/30 cursor-pointer transition-all group">
                        <div className="relative">
                          <input
                            type="checkbox"
                            checked={formData.wantsToBeSponsor}
                            onChange={(e) => setFormData({ ...formData, wantsToBeSponsor: e.target.checked })}
                            className="sr-only"
                          />
                          <div className={`w-6 h-6 rounded-lg border-2 flex items-center justify-center transition-all ${
                            formData.wantsToBeSponsor
                              ? 'bg-brand-gold-500 border-brand-gold-500'
                              : 'border-white/30 group-hover:border-white/50'
                          }`}>
                            {formData.wantsToBeSponsor && (
                              <svg className="w-4 h-4 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" />
                              </svg>
                            )}
                          </div>
                        </div>
                        <div className="flex-1">
                          <p className="font-medium text-white">Sponsoring cleanings</p>
                          <p className="text-sm text-neutral-400">Fund cleaner streets in your area</p>
                        </div>
                        <span className="text-2xl">❤️</span>
                      </label>
                    </div>
                  </div>

                  {/* Email updates */}
                  <label className="flex items-start gap-3 cursor-pointer">
                    <div className="relative mt-0.5">
                      <input
                        type="checkbox"
                        checked={formData.emailUpdates}
                        onChange={(e) => setFormData({ ...formData, emailUpdates: e.target.checked })}
                        className="sr-only"
                      />
                      <div className={`w-5 h-5 rounded border-2 flex items-center justify-center transition-all ${
                        formData.emailUpdates
                          ? 'bg-brand-gold-500 border-brand-gold-500'
                          : 'border-white/30'
                      }`}>
                        {formData.emailUpdates && (
                          <svg className="w-3 h-3 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" />
                          </svg>
                        )}
                      </div>
                    </div>
                    <span className="text-sm text-neutral-400">
                      Notify me when GCC launches in my area
                    </span>
                  </label>

                  {/* Error message */}
                  {error && (
                    <div className="p-4 bg-red-500/20 border border-red-500/30 rounded-xl text-red-300 text-sm">
                      {error}
                    </div>
                  )}

                  <div className="flex gap-3">
                    {/* Only show back button if not coming from worker signup with complete data */}
                    {!(isFromWorkerSignup && hasCompleteData) && (
                      <button
                        type="button"
                        onClick={() => {
                          // US users skipped step 2, so go back to step 1
                          // Non-US users go back to step 2
                          setStep(isUSA ? 1 : 2)
                        }}
                        className="px-6 py-4 rounded-xl font-semibold text-neutral-400 hover:text-white transition-colors"
                      >
                        ← Back
                      </button>
                    )}
                    <button
                      type="submit"
                      disabled={isSubmitting}
                      className={`
                        flex-1 py-4 px-6 rounded-xl font-semibold text-lg
                        transition-all transform
                        ${
                          isSubmitting
                            ? 'bg-white/10 text-neutral-500 cursor-not-allowed'
                            : 'bg-brand-gold-500 text-white hover:bg-brand-gold-400 hover:scale-[1.02] active:scale-[0.98] shadow-lg shadow-brand-gold-500/20'
                        }
                      `}
                    >
                      {isSubmitting ? (
                        <span className="flex items-center justify-center gap-2">
                          <svg className="animate-spin h-5 w-5" viewBox="0 0 24 24">
                            <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
                            <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                          </svg>
                          Securing your spot...
                        </span>
                      ) : (
                        '🎯 Lock In My Position'
                      )}
                    </button>
                  </div>
                </div>
              )}
            </form>

            {/* Footer */}
            <div className="px-6 sm:px-8 py-4 bg-white/5 border-t border-white/10">
              <p className="text-center text-sm text-neutral-500">
                {isLaunchArea ? (
                  <>
                    Active in <span className="text-brand-green-400 font-medium">North Lawndale & Little Village, Chicago</span>.
                    <br />
                    Workers can start earning immediately!
                  </>
                ) : (
                  <>
                    Launching in <span className="text-brand-green-400 font-medium">Chicago</span>, expanding globally.
                    <br />
                    Your rank is permanent and will never change.
                  </>
                )}
              </p>
            </div>
          </div>

          {/* Back link */}
          <div className="mt-8 text-center">
            <Link href="/" className="text-brand-gold-400 hover:text-brand-gold-300 font-medium transition-colors">
              ← Back to homepage
            </Link>
          </div>
        </div>
      </div>
    </main>
  )
}

export default function JoinPage() {
  return (
    <Suspense fallback={
      <main className="min-h-screen bg-gradient-to-br from-brand-green-900 via-brand-brown-900 to-neutral-900 flex items-center justify-center">
        <div className="flex flex-col items-center gap-4">
          <div className="w-12 h-12 border-4 border-brand-gold-500 border-t-transparent rounded-full animate-spin" />
          <p className="text-neutral-400">Loading...</p>
        </div>
      </main>
    }>
      <JoinForm />
    </Suspense>
  )
}
