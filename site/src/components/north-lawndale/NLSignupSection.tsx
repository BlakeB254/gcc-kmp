'use client'

import { useState } from 'react'

type FormState = 'idle' | 'submitting' | 'success' | 'error'

export function NLSignupSection() {
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [phone, setPhone] = useState('')
  const [formState, setFormState] = useState<FormState>('idle')
  const [errorMessage, setErrorMessage] = useState('')

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!email.trim()) return

    setFormState('submitting')
    setErrorMessage('')

    try {
      const response = await fetch('/api/waitlist/join', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          email: email.trim(),
          name: name.trim() || undefined,
          phone: phone.trim() || undefined,
          source: 'north-lawndale',
          location: {
            country: 'United States',
            countryCode: 'US',
            state: 'Illinois',
            stateCode: 'IL',
            city: 'Chicago',
            neighborhood: 'North Lawndale',
          },
          preferences: {
            wantsToBeWorker: true,
            wantsToBeSponsor: false,
            wantsToVolunteer: false,
            emailUpdates: true,
          },
        }),
      })

      const data = await response.json()

      if (data.success) {
        setFormState('success')
      } else {
        setFormState('error')
        setErrorMessage(data.error || 'Something went wrong. Please try again.')
      }
    } catch {
      setFormState('error')
      setErrorMessage('Network error. Please check your connection and try again.')
    }
  }

  if (formState === 'success') {
    return (
      <section id="join" className="py-golden-2xl bg-brand-green-50">
        <div className="section-container">
          <div className="max-w-lg mx-auto text-center">
            <div className="inline-flex items-center justify-center w-16 h-16 rounded-full bg-brand-green-100 mb-6 animate-bounce-in">
              <svg className="w-8 h-8 text-brand-green-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
              </svg>
            </div>
            <h2 className="text-3xl font-bold text-brand-green-900 mb-4">
              You're In!
            </h2>
            <p className="text-lg text-brand-green-700 mb-2">
              Welcome to the North Lawndale movement.
            </p>
            <p className="text-brand-green-600">
              We'll be in touch with details about the next community cleaning event.
              Check your email for confirmation.
            </p>
          </div>
        </div>
      </section>
    )
  }

  return (
    <section id="join" className="py-golden-2xl bg-white">
      <div className="section-container">
        <div className="max-w-lg mx-auto">
          {/* Header */}
          <div className="text-center mb-10">
            <p className="text-sm font-semibold text-brand-gold-600 uppercase tracking-[0.2em] mb-3">
              Join Us
            </p>
            <h2 className="text-3xl sm:text-4xl font-bold text-neutral-900 mb-4">
              Be Part of the Movement
            </h2>
            <p className="text-neutral-600">
              Sign up to join the next North Lawndale community cleaning event. No commitment required — just your name and a way to reach you.
            </p>
          </div>

          {/* Form */}
          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label htmlFor="nl-name" className="block text-sm font-medium text-neutral-700 mb-1">
                Name
              </label>
              <input
                id="nl-name"
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="Your name"
                className="input"
              />
            </div>

            <div>
              <label htmlFor="nl-email" className="block text-sm font-medium text-neutral-700 mb-1">
                Email <span aria-hidden="true" className="text-red-500">*</span>
              </label>
              <input
                id="nl-email"
                type="email"
                required
                aria-required="true"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="your@email.com"
                className="input"
              />
            </div>

            <div>
              <label htmlFor="nl-phone" className="block text-sm font-medium text-neutral-700 mb-1">
                Phone <span className="text-neutral-400">(optional)</span>
              </label>
              <input
                id="nl-phone"
                type="tel"
                value={phone}
                onChange={(e) => setPhone(e.target.value)}
                placeholder="(312) 555-0000"
                className="input"
              />
            </div>

            {formState === 'error' && (
              <div role="alert" className="rounded-lg bg-red-50 border border-red-200 p-3">
                <p className="text-sm text-red-700">{errorMessage}</p>
              </div>
            )}

            <button
              type="submit"
              disabled={formState === 'submitting' || !email.trim()}
              className="btn-primary w-full py-4 text-lg disabled:opacity-50"
            >
              {formState === 'submitting' ? (
                <span className="flex items-center justify-center gap-2">
                  <svg className="animate-spin h-5 w-5" viewBox="0 0 24 24">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                  </svg>
                  Joining...
                </span>
              ) : (
                'Join North Lawndale GCC'
              )}
            </button>

            <p className="text-xs text-neutral-400 text-center">
              By signing up you agree to our{' '}
              <a href="https://globalcommunitycleaners.org/privacy" className="underline hover:text-neutral-600">
                privacy policy
              </a>
              . We'll only contact you about GCC events.
            </p>
          </form>
        </div>
      </div>
    </section>
  )
}
