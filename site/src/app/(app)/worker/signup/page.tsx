'use client'

import { useState, useCallback, useEffect, useRef, Suspense } from 'react'
import Link from 'next/link'
import { useRouter, useSearchParams } from 'next/navigation'
import { cn } from '@/lib/utils'
import { Logo } from '@/components/brand/Logo'
import { isLaunchAreaZip } from '@/lib/waitlist/rankings'
import { FormInput } from '@/components/forms/FormInput'
import { FileUpload } from '@/components/forms/FileUpload'
import { StepIndicator } from '@/components/forms/StepIndicator'
import { LoadingFallback } from '@/components/shared/LoadingFallback'

type Step = 'info' | 'address' | 'documents' | 'review'
type View = 'form' | 'outside-area'

interface FormData {
  name: string
  email: string
  phone: string
  street: string
  city: string
  state: string
  zip: string
  governmentId: File | null
  proofOfAddress: File | null
  agreeToTerms: boolean
}

// Phone number formatting helper
function formatPhoneNumber(value: string): string {
  const cleaned = value.replace(/\D/g, '')
  if (cleaned.length === 0) return ''
  if (cleaned.length <= 3) return `(${cleaned}`
  if (cleaned.length <= 6) return `(${cleaned.slice(0, 3)}) ${cleaned.slice(3)}`
  return `(${cleaned.slice(0, 3)}) ${cleaned.slice(3, 6)}-${cleaned.slice(6, 10)}`
}

// Outside Service Area Diverter - Full-page experience (no rank display)
function OutsideAreaDiverter({
  formData,
  onGoBack,
}: {
  formData: FormData
  onGoBack: () => void
}) {
  const router = useRouter()

  const handleJoinWaitlist = () => {
    // Pass all available data to minimize re-entry
    const params = new URLSearchParams({
      zip: formData.zip,
      email: formData.email,
      name: formData.name,
      city: formData.city || 'Chicago',
      state: formData.state || 'IL',
      source: 'worker-signup',
      intent: 'worker',
    })
    router.push(`/join?${params.toString()}`)
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-brand-green-900 via-brand-brown-900 to-neutral-900 relative overflow-hidden">
      {/* Decorative elements */}
      <div className="absolute inset-0 overflow-hidden pointer-events-none">
        <div className="absolute -top-40 -left-40 w-80 h-80 bg-brand-gold-500/10 rounded-full blur-3xl" />
        <div className="absolute -bottom-40 -right-40 w-96 h-96 bg-brand-green-500/10 rounded-full blur-3xl" />
        <div className="absolute top-1/4 right-1/4 w-64 h-64 bg-brand-sky-500/5 rounded-full blur-3xl" />
      </div>

      <div className="relative z-10 min-h-screen flex items-center justify-center px-4 py-12">
        <div className="max-w-lg w-full">
          {/* Back button */}
          <button
            onClick={onGoBack}
            className="mb-8 flex items-center gap-2 text-neutral-400 hover:text-white transition-colors"
          >
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
            </svg>
            <span>Change location</span>
          </button>

          {/* Main content */}
          <div className="text-center mb-8 animate-fade-in-up">
            {/* Globe icon */}
            <div className="inline-flex items-center justify-center w-24 h-24 mb-6 rounded-3xl bg-gradient-to-br from-brand-gold-500/20 to-brand-gold-600/10 border border-brand-gold-500/20">
              <svg className="w-12 h-12 text-brand-gold-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M21 12a9 9 0 01-9 9m9-9a9 9 0 00-9-9m9 9H3m9 9a9 9 0 01-9-9m9 9c1.657 0 3-4.03 3-9s-1.343-9-3-9m0 18c-1.657 0-3-4.03-3-9s1.343-9 3-9m-9 9a9 9 0 019-9" />
              </svg>
            </div>

            <h1 className="text-3xl sm:text-4xl font-bold text-white mb-4">
              You're Exactly Who
              <br />
              <span className="text-brand-gold-400">We Need!</span>
            </h1>

            <p className="text-lg text-neutral-300 max-w-md mx-auto">
              We're launching in Chicago and expanding fast. Join the global waitlist to be first in line when GCC comes to your area.
            </p>
          </div>

          {/* Benefits - without rank-specific language */}
          <div className="space-y-3 mb-8 animate-fade-in-up" style={{ animationDelay: '0.1s' }}>
            {[
              { icon: '🔔', text: 'Get notified first when we launch in your area' },
              { icon: '🚀', text: 'Higher demand from your city = faster expansion' },
              { icon: '💰', text: 'Early supporters get priority for opportunities' },
              { icon: '🌍', text: 'Help bring community cleaning to your neighborhood' },
            ].map((benefit, i) => (
              <div key={i} className="flex items-center gap-3 p-3 rounded-xl bg-white/5 border border-white/10">
                <span className="text-xl">{benefit.icon}</span>
                <span className="text-sm text-neutral-300">{benefit.text}</span>
              </div>
            ))}
          </div>

          {/* CTA Buttons */}
          <div className="space-y-4 animate-fade-in-up" style={{ animationDelay: '0.2s' }}>
            <button
              onClick={handleJoinWaitlist}
              className="w-full py-4 px-6 rounded-xl font-semibold text-lg bg-brand-gold-500 text-white hover:bg-brand-gold-400 transition-all transform hover:scale-[1.02] active:scale-[0.98] shadow-lg shadow-brand-gold-500/20 flex items-center justify-center gap-2"
            >
              <span>🎯</span>
              <span>Join the Waitlist</span>
              <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7l5 5m0 0l-5 5m5-5H6" />
              </svg>
            </button>

            <div className="relative">
              <div className="absolute inset-0 flex items-center">
                <div className="w-full border-t border-white/10" />
              </div>
              <div className="relative flex justify-center text-sm">
                <span className="px-4 bg-transparent text-neutral-500">or</span>
              </div>
            </div>

            <Link
              href="/sponsor"
              className="w-full py-3 px-6 rounded-xl font-medium text-neutral-300 hover:text-white border border-white/20 hover:border-white/40 transition-all flex items-center justify-center gap-2"
            >
              <span>❤️</span>
              <span>Sponsor a block in North Lawndale instead</span>
            </Link>
          </div>

          {/* Know someone local? */}
          <div className="mt-8 p-4 rounded-xl bg-brand-green-500/10 border border-brand-green-500/20 text-center animate-fade-in-up" style={{ animationDelay: '0.3s' }}>
            <p className="text-sm text-brand-green-300 mb-2">
              <strong>Know someone in North Lawndale?</strong>
            </p>
            <p className="text-xs text-brand-green-400/80">
              Share GCC with them! They can start earning $100/block immediately.
            </p>
            <button
              onClick={() => {
                if (navigator.share) {
                  navigator.share({
                    title: 'Join GCC - Earn $100 per block!',
                    text: 'GCC is hiring in North Lawndale, Chicago. Earn $100 cleaning your neighborhood!',
                    url: 'https://globalcommunitycleaners.org/worker/signup',
                  })
                }
              }}
              className="mt-3 inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-brand-green-500/20 hover:bg-brand-green-500/30 text-brand-green-300 text-sm font-medium transition-colors"
            >
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8.684 13.342C8.886 12.938 9 12.482 9 12c0-.482-.114-.938-.316-1.342m0 2.684a3 3 0 110-2.684m0 2.684l6.632 3.316m-6.632-6l6.632-3.316m0 0a3 3 0 105.367-2.684 3 3 0 00-5.367 2.684zm0 9.316a3 3 0 105.368 2.684 3 3 0 00-5.368-2.684z" />
              </svg>
              Share with a friend
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}

// Main Worker Signup Form - starts directly (no location gate)
function WorkerSignupForm() {
  const router = useRouter()
  const [currentStep, setCurrentStep] = useState<Step>('info')
  const [currentView, setCurrentView] = useState<View>('form')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const formRef = useRef<HTMLDivElement>(null)

  const [formData, setFormData] = useState<FormData>({
    name: '',
    email: '',
    phone: '',
    street: '',
    city: '',
    state: '',
    zip: '',
    governmentId: null,
    proofOfAddress: null,
    agreeToTerms: false,
  })

  const steps: { id: Step; label: string }[] = [
    { id: 'info', label: 'Basic Info' },
    { id: 'address', label: 'Address' },
    { id: 'documents', label: 'Documents' },
    { id: 'review', label: 'Review' },
  ]

  const currentStepIndex = steps.findIndex((s) => s.id === currentStep)

  // Scroll to top of form when step changes
  useEffect(() => {
    if (formRef.current) {
      formRef.current.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }
  }, [currentStep])

  const updateForm = useCallback((updates: Partial<FormData>) => {
    setFormData((prev) => ({ ...prev, ...updates }))
    setError(null)
    Object.keys(updates).forEach(key => {
      setFieldErrors(prev => {
        const next = { ...prev }
        delete next[key]
        return next
      })
    })
  }, [])

  const validateField = useCallback((field: keyof FormData, value: any): string | null => {
    switch (field) {
      case 'name':
        if (!value?.trim()) return 'Name is required'
        if (value.trim().length < 2) return 'Name must be at least 2 characters'
        return null
      case 'email':
        if (!value?.trim()) return 'Email is required'
        if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) return 'Please enter a valid email'
        return null
      case 'phone':
        const digits = value?.replace(/\D/g, '') || ''
        if (digits.length < 10) return 'Please enter a 10-digit phone number'
        return null
      case 'street':
        if (!value?.trim()) return 'Street address is required'
        return null
      case 'city':
        if (!value?.trim()) return 'City is required'
        return null
      case 'state':
        if (!value?.trim()) return 'State is required'
        return null
      case 'zip':
        if (!value?.trim()) return 'Zip code is required'
        if (value.length !== 5) return 'Please enter a 5-digit zip code'
        return null
      default:
        return null
    }
  }, [])

  const validateStep = (): boolean => {
    const errors: Record<string, string> = {}

    switch (currentStep) {
      case 'info':
        const nameError = validateField('name', formData.name)
        const emailError = validateField('email', formData.email)
        const phoneError = validateField('phone', formData.phone)
        if (nameError) errors.name = nameError
        if (emailError) errors.email = emailError
        if (phoneError) errors.phone = phoneError
        break

      case 'address':
        const streetError = validateField('street', formData.street)
        const cityError = validateField('city', formData.city)
        const stateError = validateField('state', formData.state)
        const zipError = validateField('zip', formData.zip)
        if (streetError) errors.street = streetError
        if (cityError) errors.city = cityError
        if (stateError) errors.state = stateError
        if (zipError) errors.zip = zipError
        break

      case 'documents':
        if (!formData.governmentId) {
          setError('Please upload your government ID')
          return false
        }
        if (!formData.proofOfAddress) {
          setError('Please upload proof of address')
          return false
        }
        break

      case 'review':
        if (!formData.agreeToTerms) {
          setError('Please agree to the terms and conditions')
          return false
        }
        break
    }

    if (Object.keys(errors).length > 0) {
      setFieldErrors(errors)
      setError('Please fix the errors above')
      return false
    }

    return true
  }

  const [isCheckingDuplicate, setIsCheckingDuplicate] = useState(false)

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
        if (data.field === 'email') {
          setFieldErrors((prev) => ({ ...prev, email: data.message }))
        } else if (data.field === 'phone') {
          setFieldErrors((prev) => ({ ...prev, phone: data.message }))
        }
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

  const nextStep = async () => {
    if (!validateStep()) return

    // Special handling for Step 1 (info): check for duplicate email/phone
    if (currentStep === 'info') {
      const canProceed = await checkForDuplicates()
      if (!canProceed) return
    }

    // Special handling for Step 2 (address): check zip code
    if (currentStep === 'address') {
      if (!isLaunchAreaZip(formData.zip)) {
        // Divert to outside area view
        setCurrentView('outside-area')
        return
      }
    }

    const nextIndex = currentStepIndex + 1
    if (nextIndex < steps.length) {
      setCurrentStep(steps[nextIndex].id)
    }
  }

  const prevStep = () => {
    const prevIndex = currentStepIndex - 1
    if (prevIndex >= 0) {
      setCurrentStep(steps[prevIndex].id)
      setError(null)
      setFieldErrors({})
    }
  }

  const handleGoBack = () => {
    setCurrentView('form')
    // Stay on address step so they can change their zip
  }

  // Compress image to reduce file size (mobile photos are often 5-10MB)
  const compressImage = (file: File, maxWidth = 1600, quality = 0.8): Promise<string> => {
    return new Promise((resolve, reject) => {
      // If not an image, just convert to base64
      if (!file.type.startsWith('image/')) {
        const reader = new FileReader()
        reader.onload = () => resolve(reader.result as string)
        reader.onerror = () => reject(new Error('Unable to read file'))
        reader.readAsDataURL(file)
        return
      }

      const img = new Image()
      const canvas = document.createElement('canvas')
      const ctx = canvas.getContext('2d')

      img.onload = () => {
        // Calculate new dimensions
        let width = img.width
        let height = img.height

        if (width > maxWidth) {
          height = (height * maxWidth) / width
          width = maxWidth
        }

        canvas.width = width
        canvas.height = height

        // Draw and compress
        ctx?.drawImage(img, 0, 0, width, height)

        // Convert to JPEG for better compression (unless it's a PNG with transparency)
        const outputType = file.type === 'image/png' ? 'image/png' : 'image/jpeg'
        const dataUrl = canvas.toDataURL(outputType, quality)

        // Check compressed size
        const compressedSize = Math.round((dataUrl.length * 3) / 4) // Approximate decoded size
        console.log(`[compress] ${file.name}: ${(file.size / 1024).toFixed(0)}KB → ${(compressedSize / 1024).toFixed(0)}KB`)

        resolve(dataUrl)
      }

      img.onerror = () => {
        // Fallback to original file if image loading fails
        const reader = new FileReader()
        reader.onload = () => resolve(reader.result as string)
        reader.onerror = () => reject(new Error('Unable to read file'))
        reader.readAsDataURL(file)
      }

      // Read the file as a data URL for the image
      const reader = new FileReader()
      reader.onload = (e) => {
        img.src = e.target?.result as string
      }
      reader.onerror = () => reject(new Error('Unable to read file'))
      reader.readAsDataURL(file)
    })
  }

  // Helper to convert File to base64 (with compression for images)
  const fileToBase64 = async (file: File): Promise<string> => {
    // Check file size - warn if too large
    const maxSize = 10 * 1024 * 1024 // 10MB
    if (file.size > maxSize) {
      throw new Error(
        `File "${file.name}" is too large (${(file.size / 1024 / 1024).toFixed(1)}MB). Please use a smaller file (under 10MB).`
      )
    }

    // Compress images to reduce upload size
    if (file.type.startsWith('image/')) {
      return compressImage(file)
    }

    // For non-images (PDFs), use regular base64
    return new Promise((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(reader.result as string)
      reader.onerror = () => {
        console.error('FileReader error:', reader.error)
        reject(new Error(
          `Unable to read file "${file.name}". Please re-select the file and try again.`
        ))
      }
      reader.onabort = () => {
        reject(new Error('File reading was cancelled. Please try again.'))
      }
      try {
        reader.readAsDataURL(file)
      } catch (err) {
        reject(new Error(
          `Unable to read file "${file.name}". The file may have been moved or deleted.`
        ))
      }
    })
  }

  const handleSubmit = async () => {
    if (!validateStep()) return

    setIsSubmitting(true)
    setError(null)

    try {
      // Convert files to base64 to avoid FormData issues with some browsers/extensions
      let governmentIdData = null
      let proofOfAddressData = null

      if (formData.governmentId) {
        try {
          const base64 = await fileToBase64(formData.governmentId)
          governmentIdData = {
            data: base64,
            name: formData.governmentId.name,
            type: formData.governmentId.type,
          }
        } catch (fileErr) {
          throw new Error(
            `Could not read your Government ID file. Please go back to the Documents step and re-upload it.`
          )
        }
      }

      if (formData.proofOfAddress) {
        try {
          const base64 = await fileToBase64(formData.proofOfAddress)
          proofOfAddressData = {
            data: base64,
            name: formData.proofOfAddress.name,
            type: formData.proofOfAddress.type,
          }
        } catch (fileErr) {
          throw new Error(
            `Could not read your Proof of Address file. Please go back to the Documents step and re-upload it.`
          )
        }
      }

      const submitData = {
        name: formData.name,
        email: formData.email,
        phone: formData.phone,
        street: formData.street,
        city: formData.city,
        state: formData.state,
        zip: formData.zip,
        governmentId: governmentIdData,
        proofOfAddress: proofOfAddressData,
      }

      let response: Response
      try {
        response = await fetch('/api/workers/apply', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
          body: JSON.stringify(submitData),
        })
      } catch (networkError) {
        // Network-level error (blocked by browser, extension, or firewall)
        console.error('Network error during registration:', networkError)
        throw new Error(
          'Unable to connect. Please disable any ad blockers or try a different browser.'
        )
      }

      let data
      try {
        const text = await response.text()
        try {
          data = JSON.parse(text)
        } catch {
          // If we got HTML or other non-JSON response, provide helpful error
          console.error('Non-JSON response:', text.substring(0, 500))
          if (text.includes('<!DOCTYPE') || text.includes('<html')) {
            // Check for common error indicators
            if (text.includes('413') || text.includes('Payload Too Large') || text.includes('FUNCTION_PAYLOAD_TOO_LARGE')) {
              throw new Error('Your files are too large. Please use smaller images (under 5MB each) and try again.')
            }
            if (text.includes('504') || text.includes('Gateway Timeout') || text.includes('FUNCTION_INVOCATION_TIMEOUT')) {
              throw new Error('The upload is taking too long. Please try with smaller files or a stronger connection.')
            }
            throw new Error('Our servers are temporarily unavailable. Please try again in a few minutes.')
          }
          throw new Error('Unable to process the response. Please refresh and try again.')
        }
      } catch (parseErr) {
        if (parseErr instanceof Error && (
          parseErr.message.includes('servers') ||
          parseErr.message.includes('files are too large') ||
          parseErr.message.includes('taking too long')
        )) {
          throw parseErr
        }
        throw new Error('Unable to complete registration. Please try with smaller images or a Wi-Fi connection.')
      }

      if (!response.ok) {
        throw new Error(data.message || 'Registration failed')
      }

      // Store worker ID for verification page
      if (data.workerId) {
        localStorage.setItem('gcc_pending_worker_id', data.workerId)
      }

      // Redirect to email verification page
      router.push(`/worker/verify-email?id=${data.workerId}`)
    } catch (err) {
      console.error('Registration error:', err)
      setError(err instanceof Error ? err.message : 'Something went wrong')
    } finally {
      setIsSubmitting(false)
    }
  }

  // Show outside area diverter
  if (currentView === 'outside-area') {
    return (
      <OutsideAreaDiverter
        formData={formData}
        onGoBack={handleGoBack}
      />
    )
  }

  // Main form view
  return (
    <div className="min-h-screen bg-gradient-to-br from-brand-brown-50 via-white to-brand-gold-50 py-golden-lg sm:py-golden-xl px-4">
      <div className="max-w-lg mx-auto" ref={formRef}>
        {/* Header */}
        <div className="text-center mb-golden-md sm:mb-golden-lg">
          <div className="mb-golden-sm sm:mb-golden-md">
            <Logo variant="short" size="lg" />
          </div>
          <h1 className="text-2xl sm:text-3xl font-bold text-neutral-900">Become a GCC Worker</h1>
          <p className="text-neutral-600 mt-golden-xs text-sm sm:text-base">
            Sign up to clean blocks and earn <span className="font-semibold text-brand-green-600">$100</span> each
          </p>
          <p className="text-xs text-neutral-500 mt-2">
            Currently serving <span className="font-medium text-brand-green-600">60623</span> &amp; <span className="font-medium text-brand-green-600">60624</span> in Chicago
          </p>
        </div>

        {/* Progress Indicator */}
        <StepIndicator steps={steps} currentStepIndex={currentStepIndex} />

        {/* Form Card */}
        <div className="bg-white rounded-2xl border border-neutral-200 p-golden-md sm:p-golden-md shadow-lg shadow-neutral-100">
          {/* Global Error */}
          {error && (
            <div className="mb-golden-md p-golden-sm bg-red-50 border border-red-200 rounded-xl text-red-700 text-sm flex items-start gap-golden-xs animate-slide-down">
              <div className="flex-shrink-0 w-5 h-5 rounded-full bg-red-100 flex items-center justify-center mt-0.5">
                <svg className="w-3 h-3 text-red-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
                </svg>
              </div>
              <span>{error}</span>
            </div>
          )}

          {/* Step 1: Basic Info */}
          {currentStep === 'info' && (
            <div className="space-y-4 animate-fade-in-up">
              <FormInput
                label="Full Name"
                id="name"
                value={formData.name}
                onChange={(value) => updateForm({ name: value })}
                placeholder="As it appears on your ID"
                error={fieldErrors.name || (validateField('name', formData.name) || undefined)}
                autoComplete="name"
              />
              <FormInput
                label="Email"
                id="email"
                type="email"
                value={formData.email}
                onChange={(value) => updateForm({ email: value })}
                placeholder="you@example.com"
                error={fieldErrors.email || (validateField('email', formData.email) || undefined)}
                autoComplete="email"
              />
              <FormInput
                label="Phone Number"
                id="phone"
                type="tel"
                value={formData.phone}
                onChange={(value) => updateForm({ phone: formatPhoneNumber(value) })}
                placeholder="(312) 555-0123"
                error={fieldErrors.phone || (validateField('phone', formData.phone) || undefined)}
                hint="We'll text you when blocks become available"
                maxLength={14}
                autoComplete="tel"
              />
            </div>
          )}

          {/* Step 2: Address - now with editable city/state/zip */}
          {currentStep === 'address' && (
            <div className="space-y-4 animate-fade-in-up">
              <div className="p-4 bg-brand-gold-50 border border-brand-gold-200 rounded-xl">
                <div className="flex items-start gap-3">
                  <div className="flex-shrink-0 w-8 h-8 rounded-full bg-brand-gold-100 flex items-center justify-center">
                    <svg className="w-4 h-4 text-brand-gold-700" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                    </svg>
                  </div>
                  <p className="text-sm text-brand-gold-800">
                    <strong>Address verification required.</strong> We verify addresses to ensure
                    opportunities go to the community.
                  </p>
                </div>
              </div>

              <FormInput
                label="Street Address"
                id="street"
                value={formData.street}
                onChange={(value) => updateForm({ street: value })}
                placeholder="1234 W Roosevelt Rd"
                error={fieldErrors.street || (validateField('street', formData.street) || undefined)}
                autoComplete="street-address"
              />

              <div className="grid grid-cols-3 gap-3">
                <FormInput
                  label="City"
                  id="city"
                  value={formData.city}
                  onChange={(value) => updateForm({ city: value })}
                  placeholder="Chicago"
                  error={fieldErrors.city || (validateField('city', formData.city) || undefined)}
                  autoComplete="address-level2"
                />
                <FormInput
                  label="State"
                  id="state"
                  value={formData.state}
                  onChange={(value) => updateForm({ state: value.toUpperCase().slice(0, 2) })}
                  placeholder="IL"
                  maxLength={2}
                  error={fieldErrors.state || (validateField('state', formData.state) || undefined)}
                  autoComplete="address-level1"
                />
                <FormInput
                  label="Zip"
                  id="zip"
                  value={formData.zip}
                  onChange={(value) => updateForm({ zip: value.replace(/\D/g, '').slice(0, 5) })}
                  placeholder="60623"
                  maxLength={5}
                  error={fieldErrors.zip || (validateField('zip', formData.zip) || undefined)}
                  autoComplete="postal-code"
                />
              </div>

              {/* Launch area indicator */}
              {formData.zip.length === 5 && (
                <div className={cn(
                  "p-3 rounded-lg text-sm animate-fade-in-up",
                  isLaunchAreaZip(formData.zip)
                    ? "bg-brand-green-50 border border-brand-green-200 text-brand-green-700"
                    : "bg-brand-sky-50 border border-brand-sky-200 text-brand-sky-700"
                )}>
                  {isLaunchAreaZip(formData.zip) ? (
                    <div className="flex items-center gap-2">
                      <span>🎉</span>
                      <span><strong>You're in our active launch area!</strong> North Lawndale, Chicago</span>
                    </div>
                  ) : (
                    <div className="flex items-center gap-2">
                      <span>🌍</span>
                      <span>Not in current service area - you'll be directed to join our waitlist</span>
                    </div>
                  )}
                </div>
              )}
            </div>
          )}

          {/* Step 3: Documents */}
          {currentStep === 'documents' && (
            <div className="space-y-6 animate-fade-in-up">
              <FileUpload
                label="Government ID"
                hint="Driver's license, state ID, or passport"
                file={formData.governmentId}
                onFileSelect={(file) => updateForm({ governmentId: file })}
              />

              <FileUpload
                label="Proof of Address"
                hint="Utility bill, bank statement, or official mail (dated within 60 days)"
                file={formData.proofOfAddress}
                onFileSelect={(file) => updateForm({ proofOfAddress: file })}
              />
            </div>
          )}

          {/* Step 4: Review */}
          {currentStep === 'review' && (
            <div className="space-y-6 animate-fade-in-up">
              <div className="bg-neutral-50 rounded-xl p-4 sm:p-5 space-y-4">
                <h3 className="font-semibold text-neutral-900 text-sm uppercase tracking-wide">
                  Application Summary
                </h3>
                <div className="space-y-3 divide-y divide-neutral-200">
                  <div className="flex flex-col sm:flex-row sm:justify-between py-2 gap-1">
                    <span className="text-neutral-500 text-sm">Name</span>
                    <span className="font-medium text-neutral-900">{formData.name}</span>
                  </div>
                  <div className="flex flex-col sm:flex-row sm:justify-between py-2 gap-1">
                    <span className="text-neutral-500 text-sm">Email</span>
                    <span className="font-medium text-neutral-900 break-all">{formData.email}</span>
                  </div>
                  <div className="flex flex-col sm:flex-row sm:justify-between py-2 gap-1">
                    <span className="text-neutral-500 text-sm">Phone</span>
                    <span className="font-medium text-neutral-900">{formData.phone}</span>
                  </div>
                  <div className="flex flex-col sm:flex-row sm:justify-between py-2 gap-1">
                    <span className="text-neutral-500 text-sm">Address</span>
                    <span className="font-medium text-neutral-900 sm:text-right">
                      {formData.street}<br />
                      {formData.city}, {formData.state} {formData.zip}
                    </span>
                  </div>
                  <div className="flex justify-between py-2">
                    <span className="text-neutral-500 text-sm">Documents</span>
                    <span className="font-medium text-brand-green-600 flex items-center gap-1">
                      <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                      </svg>
                      2 uploaded
                    </span>
                  </div>
                </div>
              </div>

              <label className="flex items-start gap-3 cursor-pointer group p-4 rounded-xl border-2 border-transparent hover:border-brand-gold-100 hover:bg-brand-gold-50/50 transition-all">
                <div className="relative mt-0.5">
                  <input
                    type="checkbox"
                    checked={formData.agreeToTerms}
                    onChange={(e) => updateForm({ agreeToTerms: e.target.checked })}
                    className="peer w-5 h-5 rounded border-2 border-neutral-300 text-brand-gold-600 focus:ring-brand-gold-500 focus:ring-2 focus:ring-offset-0 transition-colors cursor-pointer"
                  />
                </div>
                <span className="text-sm text-neutral-600 leading-relaxed">
                  I agree to the{' '}
                  <Link href="/terms" target="_blank" rel="noopener noreferrer" className="text-brand-gold-600 font-medium hover:underline">
                    Terms of Service
                  </Link>{' '}
                  and{' '}
                  <Link href="/privacy" target="_blank" rel="noopener noreferrer" className="text-brand-gold-600 font-medium hover:underline">
                    Privacy Policy
                  </Link>
                  . I certify that the information provided is accurate and I am eligible to work
                  in the United States.
                </span>
              </label>
            </div>
          )}

          {/* Navigation */}
          <div className="flex gap-golden-xs sm:gap-golden-sm mt-golden-lg">
            {currentStepIndex > 0 && (
              <button
                type="button"
                onClick={prevStep}
                className="flex-1 sm:flex-none inline-flex items-center justify-center rounded-xl border-2 border-neutral-300 bg-white px-golden-md py-3.5 text-base font-semibold text-neutral-700 transition-all hover:bg-neutral-50 hover:border-neutral-400 active:scale-[0.98]"
              >
                <svg className="w-4 h-4 mr-2" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
                </svg>
                Back
              </button>
            )}
            {currentStepIndex < steps.length - 1 ? (
              <button
                type="button"
                onClick={nextStep}
                disabled={isCheckingDuplicate}
                className="group flex-1 inline-flex items-center justify-center rounded-xl bg-brand-gold-600 px-golden-md py-3.5 text-base font-semibold text-white shadow-lg shadow-brand-gold-200 transition-all hover:bg-brand-gold-700 hover:shadow-xl active:scale-[0.98] disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {isCheckingDuplicate ? (
                  <>
                    <svg className="animate-spin -ml-1 mr-2 h-4 w-4 text-white" fill="none" viewBox="0 0 24 24">
                      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                    </svg>
                    Checking...
                  </>
                ) : (
                  <>
                    Continue
                    <svg className="w-4 h-4 ml-2 group-hover:translate-x-1 transition-transform" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
                    </svg>
                  </>
                )}
              </button>
            ) : (
              <button
                type="button"
                onClick={handleSubmit}
                disabled={isSubmitting}
                className="group flex-1 inline-flex items-center justify-center rounded-xl bg-brand-green-600 px-golden-md py-3.5 text-base font-semibold text-white shadow-lg shadow-brand-green-200 transition-all hover:bg-brand-green-700 hover:shadow-xl active:scale-[0.98] disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {isSubmitting ? (
                  <>
                    <svg className="animate-spin -ml-1 mr-3 h-5 w-5 text-white" fill="none" viewBox="0 0 24 24">
                      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                    </svg>
                    Submitting...
                  </>
                ) : (
                  <>
                    Submit Application
                    <svg className="w-4 h-4 ml-2" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                    </svg>
                  </>
                )}
              </button>
            )}
          </div>
        </div>

        {/* Login link */}
        <p className="text-center text-sm text-neutral-600 mt-golden-md">
          Already have an account?{' '}
          <Link href="/worker/login" className="text-brand-gold-600 font-semibold hover:underline">
            Sign in
          </Link>
        </p>

        {/* Sponsor redirect link */}
        <p className="text-center text-sm text-neutral-500 mt-3">
          Looking to sponsor a block instead?{' '}
          <Link href="/sponsor/signup" className="text-brand-green-600 font-semibold hover:underline">
            Become a Sponsor
          </Link>
        </p>
      </div>
    </div>
  )
}

// Main export - directly renders WorkerSignupForm (no location gate)
export default function WorkerSignupPage() {
  return (
    <Suspense fallback={<LoadingFallback />}>
      <WorkerSignupForm />
    </Suspense>
  )
}
