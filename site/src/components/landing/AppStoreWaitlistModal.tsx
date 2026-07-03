'use client'

import { useState } from 'react'
import { X, Smartphone, Apple, Check, Loader2 } from 'lucide-react'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { cn } from '@/lib/utils'

interface AppStoreWaitlistModalProps {
  isOpen: boolean
  onClose: () => void
}

type Platform = 'ios' | 'android' | 'both'

export function AppStoreWaitlistModal({ isOpen, onClose }: AppStoreWaitlistModalProps) {
  const [email, setEmail] = useState('')
  const [phone, setPhone] = useState('')
  const [platform, setPlatform] = useState<Platform>('android')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [isSuccess, setIsSuccess] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const formatPhoneNumber = (value: string) => {
    // Remove all non-digits
    const digits = value.replace(/\D/g, '')
    // Format as (XXX) XXX-XXXX
    if (digits.length <= 3) return digits
    if (digits.length <= 6) return `(${digits.slice(0, 3)}) ${digits.slice(3)}`
    return `(${digits.slice(0, 3)}) ${digits.slice(3, 6)}-${digits.slice(6, 10)}`
  }

  const handlePhoneChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setPhone(formatPhoneNumber(e.target.value))
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    setIsSubmitting(true)

    try {
      const response = await fetch('/api/waitlist/join', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          email,
          phone: phone || undefined,
          source: 'mobile-app',
          mobileAppPlatform: platform,
          location: {
            country: 'United States',
            countryCode: 'US',
            state: 'Illinois',
            stateCode: 'IL',
            city: 'Chicago',
          },
          preferences: {
            wantsToBeWorker: true,
            emailUpdates: true,
          },
        }),
      })

      const data = await response.json()

      if (!response.ok) {
        throw new Error(data.error || 'Failed to join waitlist')
      }

      setIsSuccess(true)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong. Please try again.')
    } finally {
      setIsSubmitting(false)
    }
  }

  const handleClose = () => {
    // Reset form state
    if (isSuccess) {
      setEmail('')
      setPhone('')
      setPlatform('android')
      setIsSuccess(false)
    }
    setError(null)
    onClose()
  }

  return (
    <Dialog open={isOpen} onOpenChange={handleClose}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <div className="flex items-center justify-between">
            <DialogTitle className="flex items-center gap-2">
              <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-brand-green-400 to-brand-green-600 flex items-center justify-center">
                <Smartphone className="w-5 h-5 text-white" />
              </div>
              <div>
                <span className="text-lg font-bold">GCC Mobile App</span>
                <p className="text-sm font-normal text-neutral-500">
                  iOS is live now · Android launching soon
                </p>
              </div>
            </DialogTitle>
          </div>
        </DialogHeader>

        {isSuccess ? (
          <div className="py-8 text-center">
            <div className="w-16 h-16 mx-auto mb-4 rounded-full bg-brand-green-100 flex items-center justify-center">
              <Check className="w-8 h-8 text-brand-green-600" />
            </div>
            <h3 className="text-xl font-bold text-neutral-900 mb-2">You're on the list!</h3>
            <p className="text-neutral-600 mb-6">
              We'll notify you at <span className="font-medium">{email}</span> when the GCC app is available
              {platform !== 'both' && (
                <> on {platform === 'ios' ? 'iOS' : 'Android'}</>
              )}
              .
            </p>
            <div className="flex flex-col sm:flex-row gap-3">
              <button
                onClick={handleClose}
                className="flex-1 py-3 px-4 bg-brand-green-500 hover:bg-brand-green-400 text-neutral-900 font-semibold rounded-lg transition-colors"
              >
                Done
              </button>
              <a
                href="https://twitter.com/intent/tweet?text=I%20just%20signed%20up%20for%20the%20GCC%20mobile%20app%20launching%20Spring%202026!%20%F0%9F%93%B1%20Clean%20streets%2C%20real%20money%2C%20your%20neighborhood.%20%40GCCmovement"
                target="_blank"
                rel="noopener noreferrer"
                className="flex-1 py-3 px-4 bg-neutral-900 hover:bg-neutral-800 text-white font-semibold rounded-lg transition-colors flex items-center justify-center gap-2"
              >
                <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 24 24">
                  <path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 21.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.084 4.126H5.117z" />
                </svg>
                Share
              </a>
            </div>
          </div>
        ) : (
          <form onSubmit={handleSubmit} className="space-y-5">
            {/* Platform Selection */}
            <div>
              <label className="block text-sm font-medium text-neutral-700 mb-2">
                Which platform?
              </label>
              <div className="grid grid-cols-3 gap-2">
                <button
                  type="button"
                  onClick={() => setPlatform('ios')}
                  className={cn(
                    'flex flex-col items-center gap-2 p-3 rounded-lg border-2 transition-all',
                    platform === 'ios'
                      ? 'border-brand-green-500 bg-brand-green-50'
                      : 'border-neutral-200 hover:border-neutral-300'
                  )}
                >
                  <Apple className={cn('w-6 h-6', platform === 'ios' ? 'text-brand-green-600' : 'text-neutral-600')} />
                  <span className={cn('text-sm font-medium', platform === 'ios' ? 'text-brand-green-700' : 'text-neutral-600')}>
                    iOS
                  </span>
                </button>
                <button
                  type="button"
                  onClick={() => setPlatform('android')}
                  className={cn(
                    'flex flex-col items-center gap-2 p-3 rounded-lg border-2 transition-all',
                    platform === 'android'
                      ? 'border-brand-green-500 bg-brand-green-50'
                      : 'border-neutral-200 hover:border-neutral-300'
                  )}
                >
                  <svg
                    className={cn('w-6 h-6', platform === 'android' ? 'text-brand-green-600' : 'text-neutral-600')}
                    fill="currentColor"
                    viewBox="0 0 24 24"
                  >
                    <path d="M17.523 15.3414c-.5511 0-.9993-.4486-.9993-.9997s.4482-.9993.9993-.9993c.5511 0 .9993.4482.9993.9993.0001.5511-.4482.9997-.9993.9997m-11.046 0c-.5511 0-.9993-.4486-.9993-.9997s.4482-.9993.9993-.9993c.5511 0 .9993.4482.9993.9993 0 .5511-.4483.9997-.9993.9997m11.4045-6.02l1.9973-3.4592a.416.416 0 00-.1521-.5676.416.416 0 00-.5676.1521l-2.0223 3.503C15.5902 8.2439 13.8533 7.8508 12 7.8508s-3.5902.3931-5.1367 1.0989L4.841 5.4467a.4161.4161 0 00-.5677-.1521.4157.4157 0 00-.1521.5676l1.9973 3.4592C2.6889 11.1867.3432 14.6589 0 18.761h24c-.3435-4.1021-2.6892-7.5743-6.1185-9.4396" />
                  </svg>
                  <span className={cn('text-sm font-medium', platform === 'android' ? 'text-brand-green-700' : 'text-neutral-600')}>
                    Android
                  </span>
                </button>
                <button
                  type="button"
                  onClick={() => setPlatform('both')}
                  className={cn(
                    'flex flex-col items-center gap-2 p-3 rounded-lg border-2 transition-all',
                    platform === 'both'
                      ? 'border-brand-green-500 bg-brand-green-50'
                      : 'border-neutral-200 hover:border-neutral-300'
                  )}
                >
                  <Smartphone className={cn('w-6 h-6', platform === 'both' ? 'text-brand-green-600' : 'text-neutral-600')} />
                  <span className={cn('text-sm font-medium', platform === 'both' ? 'text-brand-green-700' : 'text-neutral-600')}>
                    Both
                  </span>
                </button>
              </div>
            </div>

            {/* Email */}
            <div>
              <label htmlFor="app-email" className="block text-sm font-medium text-neutral-700 mb-1">
                Email <span className="text-red-500">*</span>
              </label>
              <input
                id="app-email"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="your@email.com"
                required
                className="w-full px-4 py-3 rounded-lg border border-neutral-300 focus:border-brand-green-500 focus:ring-2 focus:ring-brand-green-500/20 outline-none transition-all"
              />
            </div>

            {/* Phone (optional) */}
            <div>
              <label htmlFor="app-phone" className="block text-sm font-medium text-neutral-700 mb-1">
                Phone <span className="text-neutral-400 font-normal">(optional - for SMS notification)</span>
              </label>
              <input
                id="app-phone"
                type="tel"
                value={phone}
                onChange={handlePhoneChange}
                placeholder="(555) 123-4567"
                className="w-full px-4 py-3 rounded-lg border border-neutral-300 focus:border-brand-green-500 focus:ring-2 focus:ring-brand-green-500/20 outline-none transition-all"
              />
            </div>

            {/* Error Message */}
            {error && (
              <div className="p-3 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm">
                {error}
              </div>
            )}

            {/* Submit */}
            <button
              type="submit"
              disabled={isSubmitting || !email}
              className="w-full py-4 bg-brand-green-500 hover:bg-brand-green-400 disabled:bg-neutral-200 disabled:text-neutral-500 text-neutral-900 font-bold rounded-lg transition-colors flex items-center justify-center gap-2"
            >
              {isSubmitting ? (
                <>
                  <Loader2 className="w-5 h-5 animate-spin" />
                  Joining...
                </>
              ) : (
                <>
                  <Smartphone className="w-5 h-5" />
                  Notify Me When It Launches
                </>
              )}
            </button>

            {/* Privacy note */}
            <p className="text-xs text-center text-neutral-500">
              We'll only contact you about the GCC mobile app launch. No spam, ever.
            </p>
          </form>
        )}
      </DialogContent>
    </Dialog>
  )
}
