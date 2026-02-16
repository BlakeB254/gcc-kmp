'use client'

import { useState } from 'react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'

export default function SponsorSignupPage() {
  const router = useRouter()
  const [step, setStep] = useState(1)
  const [isLoading, setIsLoading] = useState(false)
  const [isCheckingDuplicate, setIsCheckingDuplicate] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [formData, setFormData] = useState({
    // Step 1: Basic Info
    name: '',
    email: '',
    password: '',
    confirmPassword: '',
    type: 'individual' as 'individual' | 'business' | 'organization' | 'foundation',
    // Step 2: Business Info (if not individual)
    companyName: '',
    website: '',
    // Step 3: Preferences
    preferredFrequency: 'one_time' as 'one_time' | 'monthly' | 'quarterly',
    isAnonymous: false,
    wantsUpdates: true,
  })

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setIsLoading(true)
    setError(null)

    if (formData.password !== formData.confirmPassword) {
      setError('Passwords do not match')
      setIsLoading(false)
      return
    }

    if (formData.password.length < 8) {
      setError('Password must be at least 8 characters')
      setIsLoading(false)
      return
    }

    try {
      const response = await fetch('/api/sponsors/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: formData.name,
          email: formData.email,
          password: formData.password,
          type: formData.type,
          business: formData.type !== 'individual' ? {
            companyName: formData.companyName,
            website: formData.website,
          } : undefined,
          preferences: {
            frequency: formData.preferredFrequency,
            isAnonymous: formData.isAnonymous,
            wantsUpdates: formData.wantsUpdates,
          },
        }),
      })

      const data = await response.json()

      if (!response.ok) {
        throw new Error(data.error || 'Registration failed')
      }

      // Check if email verification is required
      if (data.requiresVerification) {
        // Redirect to verification page with sponsor ID
        router.push(`/sponsor/verify-email?id=${data.sponsorId}`)
      } else {
        // Redirect to dashboard on success (legacy flow)
        router.push('/sponsor/dashboard')
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Registration failed')
    } finally {
      setIsLoading(false)
    }
  }

  const canProceedStep1 = formData.name && formData.email && formData.password && formData.confirmPassword
  const canProceedStep2 = formData.type === 'individual' || formData.companyName

  const checkForDuplicates = async (): Promise<boolean> => {
    setIsCheckingDuplicate(true)
    setError(null)
    setFieldErrors({})

    try {
      const response = await fetch('/api/sponsors/check-duplicate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          email: formData.email.trim().toLowerCase(),
        }),
      })

      const data = await response.json()

      if (data.exists) {
        setFieldErrors({ email: data.message })
        setError('This account already exists. Please log in instead.')
        return false
      }

      return true
    } catch (err) {
      console.error('Duplicate check failed:', err)
      // Allow to proceed if check fails (will be caught at submission)
      return true
    } finally {
      setIsCheckingDuplicate(false)
    }
  }

  const handleContinueStep1 = async () => {
    if (!canProceedStep1) return

    // Password validation
    if (formData.password !== formData.confirmPassword) {
      setFieldErrors({ confirmPassword: 'Passwords do not match' })
      return
    }

    if (formData.password.length < 8) {
      setFieldErrors({ password: 'Password must be at least 8 characters' })
      return
    }

    const canProceed = await checkForDuplicates()
    if (canProceed) {
      setStep(2)
    }
  }

  return (
    <div className="bg-gradient-to-b from-brand-brown-50 to-white">
      <div className="section-container py-12">
        <div className="max-w-md mx-auto">
          {/* Progress indicator */}
          <div className="flex items-center justify-center gap-2 mb-8">
            {[1, 2, 3].map((s) => (
              <div
                key={s}
                className={`h-2 rounded-full transition-all duration-300 ${
                  s === step
                    ? 'w-8 bg-brand-gold-500'
                    : s < step
                    ? 'w-2 bg-brand-gold-500'
                    : 'w-2 bg-neutral-200'
                }`}
              />
            ))}
          </div>

          <div className="text-center mb-8">
            <h1 className="text-2xl font-bold text-neutral-900 mb-2">
              {step === 1 && 'Create Your Sponsor Account'}
              {step === 2 && (formData.type === 'individual' ? 'Almost Done!' : 'Tell Us About Your Organization')}
              {step === 3 && 'Set Your Preferences'}
            </h1>
            <p className="text-neutral-600">
              {step === 1 && 'Manage your sponsorships, set up auto-donate, and track impact.'}
              {step === 2 && (formData.type === 'individual' ? 'Just a few more details' : 'This helps us recognize your support')}
              {step === 3 && 'Customize how you want to support GCC'}
            </p>
          </div>

          <form onSubmit={handleSubmit} className="bg-white rounded-2xl border border-neutral-200 shadow-lg overflow-hidden">
            {/* Step 1: Basic Info */}
            {step === 1 && (
              <div className="p-6 space-y-4">
                <div>
                  <label className="block text-sm font-medium text-neutral-700 mb-1">Full Name *</label>
                  <input
                    type="text"
                    required
                    value={formData.name}
                    onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                    className="input-field"
                    placeholder="John Smith"
                  />
                </div>

                <div>
                  <label className="block text-sm font-medium text-neutral-700 mb-1">Email *</label>
                  <input
                    type="email"
                    required
                    value={formData.email}
                    onChange={(e) => {
                      setFormData({ ...formData, email: e.target.value })
                      setFieldErrors((prev) => ({ ...prev, email: '' }))
                    }}
                    className={`input-field ${fieldErrors.email ? 'border-red-400 focus:border-red-500' : ''}`}
                    placeholder="you@example.com"
                  />
                  {fieldErrors.email && (
                    <p className="text-xs text-red-600 mt-1">{fieldErrors.email}</p>
                  )}
                </div>

                <div>
                  <label className="block text-sm font-medium text-neutral-700 mb-1">Password *</label>
                  <input
                    type="password"
                    required
                    minLength={8}
                    value={formData.password}
                    onChange={(e) => {
                      setFormData({ ...formData, password: e.target.value })
                      setFieldErrors((prev) => ({ ...prev, password: '' }))
                    }}
                    className={`input-field ${fieldErrors.password ? 'border-red-400 focus:border-red-500' : ''}`}
                    placeholder="At least 8 characters"
                  />
                  {fieldErrors.password && (
                    <p className="text-xs text-red-600 mt-1">{fieldErrors.password}</p>
                  )}
                </div>

                <div>
                  <label className="block text-sm font-medium text-neutral-700 mb-1">Confirm Password *</label>
                  <input
                    type="password"
                    required
                    value={formData.confirmPassword}
                    onChange={(e) => {
                      setFormData({ ...formData, confirmPassword: e.target.value })
                      setFieldErrors((prev) => ({ ...prev, confirmPassword: '' }))
                    }}
                    className={`input-field ${fieldErrors.confirmPassword ? 'border-red-400 focus:border-red-500' : ''}`}
                    placeholder="Confirm your password"
                  />
                  {fieldErrors.confirmPassword && (
                    <p className="text-xs text-red-600 mt-1">{fieldErrors.confirmPassword}</p>
                  )}
                </div>

                <div>
                  <label className="block text-sm font-medium text-neutral-700 mb-2">I am a(n)...</label>
                  <div className="grid grid-cols-2 gap-2">
                    {[
                      { value: 'individual', label: 'Individual', icon: '👤' },
                      { value: 'business', label: 'Business', icon: '🏢' },
                      { value: 'organization', label: 'Organization', icon: '🏛️' },
                      { value: 'foundation', label: 'Foundation', icon: '🎗️' },
                    ].map((option) => (
                      <button
                        key={option.value}
                        type="button"
                        onClick={() => setFormData({ ...formData, type: option.value as any })}
                        className={`p-3 rounded-xl border-2 text-center transition-all ${
                          formData.type === option.value
                            ? 'border-brand-gold-500 bg-brand-gold-50'
                            : 'border-neutral-200 hover:border-brand-gold-300'
                        }`}
                      >
                        <span className="text-xl block mb-1">{option.icon}</span>
                        <span className="text-sm font-medium">{option.label}</span>
                      </button>
                    ))}
                  </div>
                </div>

                {error && step === 1 && (
                  <div className="p-4 bg-red-50 border border-red-200 rounded-xl text-red-700 text-sm">
                    {error}
                  </div>
                )}

                <button
                  type="button"
                  onClick={handleContinueStep1}
                  disabled={!canProceedStep1 || isCheckingDuplicate}
                  className={`w-full py-3 px-6 rounded-xl font-semibold transition-all flex items-center justify-center gap-2 ${
                    canProceedStep1 && !isCheckingDuplicate
                      ? 'bg-brand-gold-500 text-white hover:bg-brand-gold-600'
                      : 'bg-neutral-200 text-neutral-400 cursor-not-allowed'
                  }`}
                >
                  {isCheckingDuplicate ? (
                    <>
                      <svg className="animate-spin h-4 w-4 text-white" fill="none" viewBox="0 0 24 24">
                        <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                        <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                      </svg>
                      Checking...
                    </>
                  ) : (
                    'Continue'
                  )}
                </button>
              </div>
            )}

            {/* Step 2: Business Info (or skip if individual) */}
            {step === 2 && (
              <div className="p-6 space-y-4">
                {formData.type !== 'individual' ? (
                  <>
                    <div>
                      <label className="block text-sm font-medium text-neutral-700 mb-1">
                        {formData.type === 'business' ? 'Company Name' : 'Organization Name'} *
                      </label>
                      <input
                        type="text"
                        required
                        value={formData.companyName}
                        onChange={(e) => setFormData({ ...formData, companyName: e.target.value })}
                        className="input-field"
                        placeholder="Your organization name"
                      />
                    </div>

                    <div>
                      <label className="block text-sm font-medium text-neutral-700 mb-1">Website (optional)</label>
                      <input
                        type="url"
                        value={formData.website}
                        onChange={(e) => setFormData({ ...formData, website: e.target.value })}
                        className="input-field"
                        placeholder="https://yourcompany.com"
                      />
                    </div>
                  </>
                ) : (
                  <div className="text-center py-8">
                    <div className="w-16 h-16 mx-auto mb-4 rounded-full bg-brand-green-100 flex items-center justify-center">
                      <svg className="w-8 h-8 text-brand-green-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                      </svg>
                    </div>
                    <p className="text-neutral-600">Great! Let's set your preferences.</p>
                  </div>
                )}

                <div className="flex gap-3">
                  <button
                    type="button"
                    onClick={() => setStep(1)}
                    className="px-6 py-3 rounded-xl font-medium text-neutral-600 hover:text-neutral-900"
                  >
                    Back
                  </button>
                  <button
                    type="button"
                    onClick={() => setStep(3)}
                    disabled={!canProceedStep2}
                    className={`flex-1 py-3 px-6 rounded-xl font-semibold transition-all ${
                      canProceedStep2
                        ? 'bg-brand-gold-500 text-white hover:bg-brand-gold-600'
                        : 'bg-neutral-200 text-neutral-400 cursor-not-allowed'
                    }`}
                  >
                    Continue
                  </button>
                </div>
              </div>
            )}

            {/* Step 3: Preferences */}
            {step === 3 && (
              <div className="p-6 space-y-4">
                <div>
                  <label className="block text-sm font-medium text-neutral-700 mb-2">Default Sponsorship Frequency</label>
                  <div className="space-y-2">
                    {[
                      { value: 'one_time', label: 'One-time', description: 'Sponsor blocks individually' },
                      { value: 'monthly', label: 'Monthly', description: 'Auto-sponsor a block every month', badge: 'Popular' },
                      { value: 'quarterly', label: 'Quarterly', description: 'Auto-sponsor every 3 months' },
                    ].map((option) => (
                      <button
                        key={option.value}
                        type="button"
                        onClick={() => setFormData({ ...formData, preferredFrequency: option.value as any })}
                        className={`w-full p-4 rounded-xl border-2 text-left transition-all ${
                          formData.preferredFrequency === option.value
                            ? 'border-brand-gold-500 bg-brand-gold-50'
                            : 'border-neutral-200 hover:border-brand-gold-300'
                        }`}
                      >
                        <div className="flex items-center justify-between">
                          <div>
                            <p className="font-medium text-neutral-900">{option.label}</p>
                            <p className="text-sm text-neutral-500">{option.description}</p>
                          </div>
                          {option.badge && (
                            <span className="px-2 py-1 rounded-full bg-brand-green-100 text-brand-green-700 text-xs font-medium">
                              {option.badge}
                            </span>
                          )}
                        </div>
                      </button>
                    ))}
                  </div>
                </div>

                <div className="space-y-3 pt-4 border-t">
                  <label className="flex items-center gap-3 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={formData.wantsUpdates}
                      onChange={(e) => setFormData({ ...formData, wantsUpdates: e.target.checked })}
                      className="rounded border-neutral-300 text-brand-gold-600 focus:ring-brand-gold-500"
                    />
                    <span className="text-sm text-neutral-600">
                      Notify me when my sponsored blocks are cleaned
                    </span>
                  </label>

                  <label className="flex items-center gap-3 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={formData.isAnonymous}
                      onChange={(e) => setFormData({ ...formData, isAnonymous: e.target.checked })}
                      className="rounded border-neutral-300 text-brand-gold-600 focus:ring-brand-gold-500"
                    />
                    <span className="text-sm text-neutral-600">
                      Keep my sponsorships anonymous
                    </span>
                  </label>
                </div>

                {error && (
                  <div className="p-4 bg-red-50 border border-red-200 rounded-xl text-red-700 text-sm">
                    {error}
                  </div>
                )}

                <div className="flex gap-3 pt-4">
                  <button
                    type="button"
                    onClick={() => setStep(2)}
                    className="px-6 py-3 rounded-xl font-medium text-neutral-600 hover:text-neutral-900"
                  >
                    Back
                  </button>
                  <button
                    type="submit"
                    disabled={isLoading}
                    className="flex-1 py-3 px-6 rounded-xl font-semibold bg-brand-gold-500 text-white hover:bg-brand-gold-600 transition-all disabled:opacity-50"
                  >
                    {isLoading ? 'Creating Account...' : 'Create Account'}
                  </button>
                </div>
              </div>
            )}
          </form>

          {/* Login link */}
          <p className="text-center text-neutral-600 mt-6">
            Already have an account?{' '}
            <Link href="/sponsor/login" className="text-brand-gold-600 font-medium hover:underline">
              Sign in
            </Link>
          </p>

          {/* One-time sponsor link */}
          <p className="text-center text-neutral-500 text-sm mt-4">
            Just want to sponsor a block?{' '}
            <Link href="/sponsor" className="text-brand-gold-600 hover:underline">
              Sponsor without an account
            </Link>
          </p>

          {/* Worker redirect link */}
          <p className="text-center text-neutral-500 text-sm mt-3">
            Looking to earn money cleaning blocks?{' '}
            <Link href="/worker/signup" className="text-brand-gold-600 font-semibold hover:underline">
              Become a Worker
            </Link>
          </p>
        </div>
      </div>
    </div>
  )
}
