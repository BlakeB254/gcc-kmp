'use client'

import { useState, useCallback, Suspense } from 'react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { cn } from '@/lib/utils'
import { Logo } from '@/components/brand/Logo'
import { FormInput } from '@/components/forms/FormInput'
import { LoadingFallback } from '@/components/shared/LoadingFallback'

interface FormData {
  name: string
  email: string
  phone: string
  password: string
  agreeToTerms: boolean
}

function formatPhoneNumber(value: string): string {
  const cleaned = value.replace(/\D/g, '')
  if (cleaned.length === 0) return ''
  if (cleaned.length <= 3) return `(${cleaned}`
  if (cleaned.length <= 6) return `(${cleaned.slice(0, 3)}) ${cleaned.slice(3)}`
  return `(${cleaned.slice(0, 3)}) ${cleaned.slice(3, 6)}-${cleaned.slice(6, 10)}`
}

function WorkerSignupForm() {
  const router = useRouter()
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [isCheckingDuplicate, setIsCheckingDuplicate] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  const [formData, setFormData] = useState<FormData>({
    name: '',
    email: '',
    phone: '',
    password: '',
    agreeToTerms: false,
  })

  const updateForm = useCallback((updates: Partial<FormData>) => {
    setFormData((prev) => ({ ...prev, ...updates }))
    setError(null)
    setFieldErrors((prev) => {
      const next = { ...prev }
      Object.keys(updates).forEach((key) => delete next[key])
      return next
    })
  }, [])

  const validate = (): boolean => {
    const errors: Record<string, string> = {}

    if (!formData.name.trim()) errors.name = 'Name is required'
    else if (formData.name.trim().length < 2) errors.name = 'Name must be at least 2 characters'

    if (!formData.email.trim()) errors.email = 'Email is required'
    else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(formData.email)) errors.email = 'Please enter a valid email'

    const digits = formData.phone.replace(/\D/g, '')
    if (digits.length < 10) errors.phone = 'Please enter a valid phone number'

    if (!formData.password) errors.password = 'Password is required'
    else if (formData.password.length < 8) errors.password = 'Password must be at least 8 characters'

    if (!formData.agreeToTerms) {
      setError('Please agree to the terms and conditions')
      return false
    }

    if (Object.keys(errors).length > 0) {
      setFieldErrors(errors)
      setError('Please fix the errors above')
      return false
    }

    return true
  }

  const checkForDuplicates = async (): Promise<boolean> => {
    setIsCheckingDuplicate(true)
    try {
      const response = await fetch('/api/workers/check-duplicate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          email: formData.email.trim().toLowerCase(),
          phone: formData.phone,
        }),
      })
      const data = await response.json()
      if (data.exists) {
        if (data.field === 'email') setFieldErrors((prev) => ({ ...prev, email: data.message }))
        else if (data.field === 'phone') setFieldErrors((prev) => ({ ...prev, phone: data.message }))
        setError('This account already exists. Please log in instead.')
        return false
      }
      return true
    } catch {
      return true // Allow to proceed if check fails
    } finally {
      setIsCheckingDuplicate(false)
    }
  }

  const handleSubmit = async () => {
    if (!validate()) return

    const canProceed = await checkForDuplicates()
    if (!canProceed) return

    setIsSubmitting(true)
    setError(null)

    try {
      const response = await fetch('/api/workers/apply', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: formData.name.trim(),
          email: formData.email.trim().toLowerCase(),
          phone: formData.phone,
          password: formData.password,
        }),
      })

      const data = await response.json()

      if (!response.ok) {
        throw new Error(data.message || 'Registration failed')
      }

      if (data.workerId) {
        localStorage.setItem('gcc_pending_worker_id', data.workerId)
      }

      router.push(`/worker/verify-email?id=${data.workerId}`)
    } catch (err) {
      console.error('Registration error:', err)
      setError(err instanceof Error ? err.message : 'Something went wrong')
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-brand-brown-50 via-white to-brand-gold-50 py-golden-lg sm:py-golden-xl px-4">
      <div className="max-w-lg mx-auto">
        {/* Header */}
        <div className="text-center mb-golden-md sm:mb-golden-lg">
          <div className="mb-golden-sm sm:mb-golden-md">
            <Logo size="md" className="mx-auto" />
          </div>
          <h1 className="text-2xl sm:text-3xl font-bold text-neutral-900 mb-2">
            Join GCC
          </h1>
          <p className="text-neutral-600">
            Create your worker account
          </p>
        </div>

        {/* Form */}
        <div className="bg-white rounded-2xl shadow-lg border border-neutral-200 p-6 sm:p-8 space-y-5">
          <FormInput
            label="Full Name"
            id="name"
            value={formData.name}
            onChange={(value) => updateForm({ name: value })}
            placeholder="Your full name"
            error={fieldErrors.name}
            autoComplete="name"
          />

          <FormInput
            label="Email"
            id="email"
            type="email"
            value={formData.email}
            onChange={(value) => updateForm({ email: value })}
            placeholder="you@example.com"
            error={fieldErrors.email}
            autoComplete="email"
          />

          <FormInput
            label="Phone"
            id="phone"
            type="tel"
            value={formData.phone}
            onChange={(value) => updateForm({ phone: formatPhoneNumber(value) })}
            placeholder="(312) 555-0123"
            error={fieldErrors.phone}
            hint="We'll text you when opportunities are available"
            maxLength={14}
            autoComplete="tel"
          />

          <FormInput
            label="Password"
            id="password"
            type="password"
            value={formData.password}
            onChange={(value) => updateForm({ password: value })}
            placeholder="8+ characters"
            error={fieldErrors.password}
            autoComplete="new-password"
          />

          {/* Terms */}
          <label className="flex items-start gap-3 cursor-pointer group">
            <input
              type="checkbox"
              checked={formData.agreeToTerms}
              onChange={(e) => updateForm({ agreeToTerms: e.target.checked })}
              className="mt-1 w-4 h-4 rounded border-neutral-300 text-brand-green-600 focus:ring-brand-green-500"
            />
            <span className="text-sm text-neutral-600 group-hover:text-neutral-900 transition-colors">
              I agree to the{' '}
              <Link href="/terms" className="text-brand-green-700 underline hover:text-brand-green-800">
                Terms of Service
              </Link>{' '}
              and{' '}
              <Link href="/privacy" className="text-brand-green-700 underline hover:text-brand-green-800">
                Privacy Policy
              </Link>
            </span>
          </label>

          {/* Error */}
          {error && (
            <div className="p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700">
              {error}
            </div>
          )}

          {/* Submit */}
          <button
            onClick={handleSubmit}
            disabled={isSubmitting || isCheckingDuplicate}
            className={cn(
              'w-full py-3 px-4 rounded-xl font-semibold text-white transition-all',
              isSubmitting || isCheckingDuplicate
                ? 'bg-neutral-400 cursor-not-allowed'
                : 'bg-brand-green-600 hover:bg-brand-green-700 active:bg-brand-green-800 shadow-md hover:shadow-lg',
            )}
          >
            {isSubmitting ? 'Creating Account...' : isCheckingDuplicate ? 'Checking...' : 'Create Account'}
          </button>

          {/* Login link */}
          <p className="text-center text-sm text-neutral-500">
            Already have an account?{' '}
            <Link href="/worker/login" className="text-brand-green-700 font-medium hover:underline">
              Sign in
            </Link>
          </p>
        </div>

        {/* Profile completion hint */}
        <p className="text-center text-xs text-neutral-400 mt-4">
          Complete your profile after signup to unlock paid cleaning routes
        </p>
      </div>
    </div>
  )
}

export default function WorkerSignupPage() {
  return (
    <Suspense fallback={<LoadingFallback />}>
      <WorkerSignupForm />
    </Suspense>
  )
}
