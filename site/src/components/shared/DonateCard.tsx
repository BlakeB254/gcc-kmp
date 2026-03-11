'use client'

import { useState, useEffect } from 'react'
import { useRouter } from 'next/navigation'
import { InlineError } from '@/components/shared/InlineError'

const PRESET_AMOUNTS = [5, 25, 50, 100]

interface DonateCardProps {
  /** Preset dollar amounts to show as quick-select buttons */
  presets?: number[]
  /** Whether to show the custom amount input */
  showCustom?: boolean
  /** Whether to show frequency toggle (one-time / monthly) */
  showFrequency?: boolean
  /** Visual variant */
  variant?: 'default' | 'compact'
  /** Override the checkout handler (for testing or alternate flows) */
  onCheckout?: (amount: number, frequency: string, formData: DonorFormData) => Promise<void>
}

export interface DonorFormData {
  name: string
  email: string
  displayName: string
  isAnonymous: boolean
  message: string
}

export function DonateCard({
  presets = PRESET_AMOUNTS,
  showCustom = true,
  showFrequency = false,
  variant = 'default',
  onCheckout,
}: DonateCardProps) {
  const router = useRouter()
  const [selectedPreset, setSelectedPreset] = useState<number | null>(null)
  const [customAmount, setCustomAmount] = useState('1')
  const [frequency, setFrequency] = useState<'one_time' | 'monthly'>('one_time')
  const [isLoading, setIsLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [showModal, setShowModal] = useState(false)
  const [formData, setFormData] = useState<DonorFormData>({
    name: '',
    email: '',
    displayName: '',
    isAnonymous: false,
    message: '',
  })

  const amount = selectedPreset !== null ? selectedPreset : (Number(customAmount) || 0)
  const blocksEquivalent = Math.floor(amount / 100)

  const handleDonateClick = () => {
    if (amount < 1) return
    setShowModal(true)
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (isLoading || amount < 1) return

    setIsLoading(true)
    setError(null)
    try {
      if (onCheckout) {
        await onCheckout(amount, frequency, formData)
      } else {
        const response = await fetch('/api/contribute/checkout', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            amount,
            frequency,
            name: formData.name,
            email: formData.email,
            displayName: formData.displayName || formData.name,
            isAnonymous: formData.isAnonymous,
            message: formData.message,
          }),
        })

        const data = await response.json()

        if (data.checkoutUrl) {
          window.location.href = data.checkoutUrl
        } else if (data.demo) {
          router.push('/support/success?demo=true')
        } else {
          console.error('Checkout failed:', data.error)
          setError('Failed to start checkout. Please try again.')
        }
      }
    } catch (error) {
      console.error('Checkout error:', error)
      setError('Failed to start checkout. Please try again.')
    } finally {
      setIsLoading(false)
    }
  }

  const isCompact = variant === 'compact'

  return (
    <>
      <div className={`bg-white rounded-2xl ${isCompact ? 'p-5' : 'p-8'} text-neutral-900`}>
        {/* Preset amounts */}
        <div className={`grid grid-cols-${presets.length > 4 ? '3' : String(presets.length)} gap-3 mb-4`}>
          {presets.map((preset) => (
            <button
              key={preset}
              onClick={() => {
                setSelectedPreset(preset)
                setCustomAmount(String(preset))
              }}
              className={`
                py-3 rounded-lg font-semibold transition-all
                ${selectedPreset === preset
                  ? 'bg-brand-green-600 text-white'
                  : 'bg-neutral-100 text-neutral-700 hover:bg-neutral-200'
                }
              `}
            >
              ${preset}
            </button>
          ))}
        </div>

        {/* Custom amount input */}
        {showCustom && (
          <div className="relative mb-4">
            <span className="absolute left-4 top-1/2 -translate-y-1/2 text-neutral-400 font-medium">$</span>
            <input
              type="number"
              min="1"
              step="1"
              placeholder="Enter amount"
              value={customAmount}
              onChange={(e) => {
                const value = e.target.value
                setCustomAmount(value)
                const numValue = Number(value)
                if (presets.includes(numValue)) {
                  setSelectedPreset(numValue)
                } else {
                  setSelectedPreset(null)
                }
              }}
              onFocus={() => {
                const input = document.activeElement as HTMLInputElement
                input?.select()
              }}
              className={`input pl-8 text-center text-lg ${selectedPreset === null && customAmount ? 'ring-2 ring-brand-green-500' : ''}`}
            />
          </div>
        )}

        {/* Frequency toggle */}
        {showFrequency && (
          <div className="mb-4">
            <div className="flex gap-2">
              <button
                onClick={() => setFrequency('one_time')}
                className={`flex-1 py-2 rounded-lg text-sm font-medium transition-all ${
                  frequency === 'one_time'
                    ? 'bg-brand-green-100 text-brand-green-700 border-2 border-brand-green-500'
                    : 'bg-neutral-100 text-neutral-600 border-2 border-transparent hover:bg-neutral-200'
                }`}
              >
                One-time
              </button>
              <button
                onClick={() => setFrequency('monthly')}
                className={`flex-1 py-2 rounded-lg text-sm font-medium transition-all ${
                  frequency === 'monthly'
                    ? 'bg-brand-green-100 text-brand-green-700 border-2 border-brand-green-500'
                    : 'bg-neutral-100 text-neutral-600 border-2 border-transparent hover:bg-neutral-200'
                }`}
              >
                Monthly
              </button>
            </div>
          </div>
        )}

        {/* Impact preview */}
        {amount > 0 && (
          <div className="bg-brand-green-50 rounded-lg p-4 mb-6">
            <p className="text-brand-green-800">
              <span className="font-semibold">${amount}</span>
              {frequency === 'monthly' && showFrequency && <span>/month</span>}
              {' '}can fund{' '}
              <span className="font-semibold">
                {blocksEquivalent > 0
                  ? `${blocksEquivalent} block${blocksEquivalent > 1 ? 's' : ''}`
                  : 'a portion of a block'}
              </span>{' '}
              cleaned
              {blocksEquivalent > 0 && (
                <span> = ${blocksEquivalent * 100} in worker wages</span>
              )}
            </p>
          </div>
        )}

        {/* Donate button */}
        <button
          onClick={handleDonateClick}
          disabled={amount < 1}
          className="btn-primary w-full disabled:opacity-50 disabled:cursor-not-allowed"
        >
          Donate ${amount || 0}{frequency === 'monthly' && showFrequency ? '/month' : ''}
        </button>

        {/* Tax note */}
        <p className="text-sm text-neutral-500 mt-4 text-center">
          GCC is a 501(c)(3) nonprofit. Your donation is tax-deductible.
        </p>
      </div>

      {/* Donation Modal */}
      {showModal && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center p-4 z-50">
          <div className="bg-white rounded-2xl p-6 max-w-md w-full max-h-[90vh] overflow-y-auto">
            <div className="flex justify-between items-center mb-6">
              <h3 className="text-xl font-bold text-neutral-900">Complete Your Donation</h3>
              <button
                onClick={() => setShowModal(false)}
                className="text-neutral-400 hover:text-neutral-600"
              >
                <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>

            <div className="bg-brand-green-50 rounded-lg p-4 mb-6">
              <p className="text-brand-green-800 text-center">
                <span className="font-semibold text-lg">${amount}</span>
                {frequency === 'monthly' && showFrequency && <span className="text-sm ml-2">/ month</span>}
                <span className="text-sm ml-2">General Fund Donation</span>
              </p>
            </div>

            <form onSubmit={handleSubmit} className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-neutral-700 mb-1">Your Name *</label>
                <input
                  type="text"
                  required
                  value={formData.name}
                  onChange={(e) => setFormData(prev => ({ ...prev, name: e.target.value }))}
                  className="input"
                  placeholder="John Smith"
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-neutral-700 mb-1">Email Address *</label>
                <input
                  type="email"
                  required
                  value={formData.email}
                  onChange={(e) => setFormData(prev => ({ ...prev, email: e.target.value }))}
                  className="input"
                  placeholder="john@example.com"
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-neutral-700 mb-1">Display Name (optional)</label>
                <input
                  type="text"
                  value={formData.displayName}
                  onChange={(e) => setFormData(prev => ({ ...prev, displayName: e.target.value }))}
                  className="input"
                  placeholder="Name shown on support wall"
                />
                <p className="text-xs text-neutral-500 mt-1">Leave blank to use your real name</p>
              </div>

              <div className="flex items-center gap-2">
                <input
                  type="checkbox"
                  id="donate-anonymous"
                  checked={formData.isAnonymous}
                  onChange={(e) => setFormData(prev => ({ ...prev, isAnonymous: e.target.checked }))}
                  className="rounded border-neutral-300"
                />
                <label htmlFor="donate-anonymous" className="text-sm text-neutral-700">
                  Keep my donation anonymous on the support wall
                </label>
              </div>

              <div>
                <label className="block text-sm font-medium text-neutral-700 mb-1">Message (optional)</label>
                <textarea
                  value={formData.message}
                  onChange={(e) => setFormData(prev => ({ ...prev, message: e.target.value }))}
                  className="input min-h-[80px]"
                  placeholder="Share why you're supporting GCC..."
                  maxLength={280}
                />
              </div>

              <InlineError message={error} />

              <button
                type="submit"
                disabled={isLoading || !formData.name || !formData.email}
                className="btn-primary w-full disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {isLoading ? (
                  <span className="flex items-center justify-center gap-2">
                    <svg className="animate-spin h-5 w-5" viewBox="0 0 24 24">
                      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
                      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                    </svg>
                    Processing...
                  </span>
                ) : (
                  `Donate $${amount}`
                )}
              </button>

              <p className="text-xs text-neutral-500 text-center">
                You&apos;ll be redirected to Stripe for secure payment processing.
              </p>
            </form>
          </div>
        </div>
      )}
    </>
  )
}
